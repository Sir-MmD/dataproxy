# Working in this repo

A SOCKS5 proxy for Android that pins outbound traffic to the cellular network.
Project directory is still `xlink/` (kept for git history); the Gradle project,
Android package, and brand are all `DataProxy` / `com.dataproxy`.

Latest released version is **v1.1.0** (versionCode 6). Fixes the long-running
"rapid stop→start leaves proxy stuck" bug, restores LAN inbound (which
v1.0.0 silently broke via `bindProcessToNetwork`), adds a live cellular
tech + operator header indicator, and overhauls the permissions dialog.

## Build & run

The user has Android Studio installed at `/opt/android-studio` (bundled JBR
Java 21) and the SDK at `/home/mmd/apps/Android/sdk` (only `android-36` /
`build-tools 36.x` are present — **do not** drop `compileSdk` back to 35).
There's a downloaded Gradle at `/home/mmd/apps/gradle-8.10.2/bin/gradle`.

```bash
# Release APK (the one shipped to GitHub Releases)
/home/mmd/apps/gradle-8.10.2/bin/gradle :app:assembleRelease \
  --no-daemon --console=plain 2>&1 | grep -E "^(e:|FAIL|BUILD)" | tail -15

# Install on the connected Samsung device
cp app/build/outputs/apk/release/app-release.apk /tmp/DataProxy-vX.Y.Z.apk
adb -s R5CY50CMNWM install -r /tmp/DataProxy-vX.Y.Z.apk

# Crash check
adb shell am force-stop com.dataproxy && adb logcat -c
adb shell monkey -p com.dataproxy -c android.intent.category.LAUNCHER 1
adb logcat -d AndroidRuntime:E "*:S" | tail -20

# End-to-end smoke test once the proxy is running (use time.ir, NOT 8.8.8.8 /
# example.com — the user's mtnirancell cellular network blocks those):
curl --socks5-hostname 127.0.0.1:1080 -s -o /dev/null \
  -w 'http=%{http_code} time=%{time_total}s\n' --max-time 25 https://time.ir
```

`org.gradle.java.home=/opt/android-studio/jbr` is set in `gradle.properties`
so command-line builds pick up the JBR automatically (system Java is 26,
which Gradle 8.10 doesn't officially support — it works but warns).

If multiple ADB devices show up (e.g. an emulator + the phone), pass
`-s R5CY50CMNWM` — that's the physical Samsung the user actually tests on.

## Release process

1. Bump `versionCode` + `versionName` in `app/build.gradle.kts`.
2. Bump `app_version` in `res/values/strings.xml` and the literal `v0.x.y` in
   `HomeScreen.kt`'s `Footer()`.
3. Build, install, smoke-test on device.
4. Commit, push, then:
   ```bash
   gh release create vX.Y.Z /tmp/DataProxy-vX.Y.Z.apk \
     --repo Sir-MmD/dataproxy \
     --title "DataProxy vX.Y.Z" \
     --notes "..."
   ```

**Never push without explicit instruction.** Default is to build + install
locally, hand the APK to the user, and let them test before any commit.
Commit messages must not include `Co-Authored-By` or any AI attribution.

The signing keystore is `app/keystore/dataproxy-release.jks` (gitignored,
password `dataproxy`). Same key has been used for every release — installs
upgrade in place. If the keystore file is missing, the build still succeeds
with an unsigned APK (signing config is conditional in `build.gradle.kts`).

## Architecture invariants

These are load-bearing — break them and the app stops working.

- **Never call `cm.bindProcessToNetwork(cellular)`.** It tags every socket
  the process creates — including the SOCKS5 listener — with the cellular
  netId. The kernel then routes the listener's SYN-ACK reply via the
  cellular route table, so external clients on Wi-Fi never finish the TCP
  handshake (SYN_RECV → retransmits → timeout). v0.2.1 → v1.0.0 had this
  bug; v1.1.0 removed all `bindProcessToNetwork` calls. The "DNS leak"
  rationale was theoretical only — every hostname resolution in this app
  goes through `Network.getAllByName()` via `cellular.resolveHost()`, and
  there is no `URL` / `OkHttp` / `HttpClient` anywhere on the data path.
- **Domain DNS goes through `Network.getAllByName`.** In `Socks5Connection
  .openRemote`, hostnames are resolved via `cellular.resolveHost(host)` —
  this is now the *only* DNS path, not belt-and-suspenders. If you ever
  add a code path that calls `InetAddress.getByName(hostname)` you've
  introduced a leak.
- **`CellularNetworkProvider` is a lazy singleton on `ProxyService`** (`val
  cellular by lazy { ... }`). It is *not* recreated per start/stop cycle.
  An attempt to make it per-cycle (during the v1.0.0 stale-state-after-
  `adb install -r` investigation) regressed rapid power-toggle: building a
  *new* `NetworkCallback` object every cycle exposes a race in
  `ConnectivityManager.unregisterNetworkCallback` → `requestNetwork` when
  the second call lands a few ms after the first. The same callback object
  re-registered is fine. **Don't reintroduce per-cycle recreation.**
- **`fullCleanup()` is called on BOTH start and stop.** `ProxyService` has
  a single helper that cancels every job (including the `startJob` that
  wraps `awaitAvailable`), stops the server, calls `cellular.stop()`,
  resets the registry/sampler, and releases the wake lock. It runs at the
  top of `startProxy` *before* `cellular.start()` and again inside
  `stopProxy`. Without the start-side call, a leftover `awaitAvailable`
  watcher from a partially-aborted previous cycle could fire `onAvailable`
  after the new launch had already built a `Socks5Server`, racing them on
  port 1080; the loser's `onFatal` called `cellular.stop()` and killed
  the winner's network handle. That was the "force-stop is the only cure"
  rapid-toggle bug; **the fix relies on `fullCleanup()` being idempotent
  and called from both sides — don't shortcut it.**
- **`startProxy`'s launch is tracked as `startJob`.** It's no longer a
  fire-and-forget `scope.launch { ... }`. The cancellation flows into
  `awaitAvailable`'s `invokeOnCancellation` which unregisters its private
  watcher `NetworkCallback`. Without the tracking the watcher leaked
  across stop→start cycles.
- **`ProxyService.State.Paused` is not Stopped.** When cellular drops, the
  service stays in the foreground and the listener stays bound. New outbound
  connects just fail with `REP_NETWORK_UNREACHABLE`. When cellular comes
  back, the watcher coroutine flips back to `Running`.
- **`State.Error(ErrorKind)` drives UI dialogs.** `ErrorKind
  .MobileDataUnavailable` is what makes `MainActivity` show the "mobile data
  is off" dialog. Don't collapse the kind enum into a bare message string.
- **`cellularState` is a computed property** (`get() = cellular.state`),
  not a `val =`. The `cellular by lazy { CellularNetworkProvider
  (applicationContext) }` block touches `applicationContext`, which is null
  during `ProxyService.<init>` — eagerly reading the lazy crashes the
  service. Verified by a prior NPE; don't undo it.
- **`cellular.awaitAvailable` timeout is 15 s** in `ProxyService.startProxy`.
  6 s was the original value; cold cellular activation on the user's
  Samsung / mtnirancell SIM regularly takes 8–12 s (acquire radio → PDP
  context → IP → validate), so 6 s was firing prematurely on the *first*
  start after a reboot or fresh install. Don't lower it.
- **No `cm.allNetworks` pre-check for mobile data.** Samsung's OneUI hides
  the cellular network from `allNetworks` when Wi-Fi is the active default,
  which made the v0.2 pre-check return false positives. v1.1.0 *does* use a
  fast pre-start check, but via `TelephonyManager.isDataEnabled()` (a
  user-toggleable setting, not a routing table snapshot) read by
  `CellularTechMonitor`. The 15 s `requestNetwork` timeout in
  `ProxyService.startProxy` is still the source of truth for the
  `MobileDataUnavailable` error state.
- **DNS resolver cache is disabled JVM-wide** in
  `DataProxyApplication.onCreate` via
  `Security.setProperty("networkaddress.cache.ttl", "0")` (+ negative).
  Cellular carriers in censorship regions periodically serve hijacked DNS;
  caching a poisoned answer until next force-stop would silently break TLS
  for the affected host until then.
- **Failed outbound TCP handshakes RST instead of FIN.** In
  `Socks5Connection.openRemote`'s `catch (IOException)`, the outbound socket
  is closed with `setSoLinger(true, 0)` so the carrier NAT entry for the
  failed 5-tuple is dropped immediately rather than lingering in `TIME_WAIT`.
- **`CellularTechMonitor` polls TelephonyManager every 2 s.** All calls are
  `runCatching`-wrapped so a missing permission or pre-API anomaly can't
  crash anything — the worst case is `TechState.Unknown`. The header
  consumes its `StateFlow` and is the only screen that uses it. Don't
  swap to a `TelephonyCallback`-based push API on pre-31 devices unless
  you also keep the polling path; the API didn't exist until S.

## SOCKS5 protocol surface (v1.1)

- **CONNECT (0x01)** — TCP tunnel, the original feature. Hostname ATYP
  resolves via cellular DNS as above.
- **UDP ASSOCIATE (0x03)** — `Socks5UdpRelay` opens two `DatagramSocket`s:
  a client-side one bound to the same address the TCP control arrived on,
  and a remote-side one bound to cellular via
  `CellularNetworkProvider.createBoundDatagramSocket()`. SOCKS5 UDP
  headers (RFC 1928 §7, `RSV(2) + FRAG(1) + ATYP + DST + DATA`) are parsed
  on ingress and re-emitted on egress. `FRAG != 0` is rejected (no
  fragmentation support, matches every common UDP client). The relay
  shuts down when the TCP control closes.
- **BIND (0x02)** — not implemented; returns `REP_COMMAND_NOT_SUPPORTED`.
- **Auth (RFC 1929)** — toggleable per-Auth-screen prefs (`auth_enabled`,
  `auth_username`, `auth_password` in `dataproxy_prefs`). Read live by
  `ProxyService.currentAuthConfig()` on each new connection so toggling
  doesn't need a proxy restart. When disabled, only method 0x00 (no-auth)
  is offered; when enabled, only 0x02 (user/pass).

## UI conventions

- **Home fits one screen, no scrolling.** Power button is 156 dp, paddings
  are tight, the battery-opt prompt is a one-time dialog (not a banner).
  Verified by screenshot on a 1080×2340 device. Adding more cards to Home
  will break the layout — put new things on a subscreen and add a nav tile.
- **Three nav tiles on Home** in a single row: Listen, Devices, Auth.
  Adding a fourth tile makes the labels wrap on 360-dp-wide devices.
- **Listen / Devices / Auth are separate screens** reached via nav tiles on
  Home, not embedded sections. Each one calls `BackHandler(onBack = onBack)`
  at the top so hardware back returns to Home instead of finishing the
  activity.
- **Tab navigation is manual** (`var tab by rememberSaveable { ... Tab.Home
  }` in `MainActivity` + `when (tab)` in `AppNav`). We deliberately do not
  pull in `androidx.navigation:navigation-compose` for four screens.
- **Devices tile subtitle counts *devices*, not connections.**
  `devices.count { it.activeConnections > 0 }` is what's displayed; "1
  online" / "3 seen" / "2 / 5 online". Don't fall back to
  `totals.active` — that's the open-TCP-socket count, which a single
  browser routinely pushes into the tens and means nothing to users.
- **Theme tokens live in `DPColors` + `LocalDPColors`** (composition local).
  Top-level vals like `Ink`, `SurfaceLow`, `TextPrimary` are `@Composable
  @ReadOnlyComposable get()` that resolve via the local. In non-composable
  scopes (e.g. inside a `Canvas` draw lambda) you must capture the colour
  into a local `val` at composable scope first — calling `OutlineSoft` from
  inside `Canvas { ... }` won't compile. The mint accent `#3DDC97`, warning
  amber, info blue, and danger red are theme-independent constants.
- **Theme toggle is a 36-dp icon button under the bolt logo** in the home
  header. Cycles `System → Light → Dark → System`. Persisted as
  `theme_mode` in `dataproxy_prefs`.
- **Header subtitle is dynamic.** When `CellularTechMonitor` reports
  `DataOff`, the subtitle text swaps from "Socks5 Over Cellular" to
  "Mobile data is OFF" in the danger colour. The right-side indicator
  shrinks to a single red `OFF` chip — the long phrase is intentionally
  *not* in the corner, that's how it used to wrap and look broken.
- **Header network indicator is clickable.** It opens the permissions
  dialog *only* when there's a missing permission worth granting —
  currently only on pre-33 devices where `READ_PHONE_STATE` would unlock
  the live tech label. On API 33+ the click is a no-op because
  `READ_BASIC_PHONE_STATE` (normal perm) is always granted.

## Permissions flow (v1.1)

Triggered only when the user taps the power button — never at launch. v1.1
**dropped the auto-chain** in favour of per-item *Allow* buttons inside the
dialog; the user explicitly clicks each one and the system prompt fires
only for that single permission.

1. `MainActivity.onPowerToggle` is the gatekeeper:
   - If `viewModel.cellularTech.value is TechState.DataOff` → show the
     `MobileDataDialog` (with "Open settings") and bail. This is the
     *pre-start* mobile-data check — it intercepts before the 15 s
     `requestNetwork` ever runs. The old behaviour (let the service start,
     fail, and surface the error 15 s later) is still the safety net via
     the `serviceState` `LaunchedEffect`.
   - If any of `needsNotifPermission()`, `!BatteryOptimizationHelper
     .isIgnoring()`, or `needsPhonePermission()` (pre-33 only) is true →
     show `PermissionsDialog` with `permsDialogStartsProxy = true`.
   - Otherwise → `actuallyStart()`.
2. `PermissionsDialog` (in `AppNav.kt`) always shows the items applicable
   to this Android version with a per-item state:
   - granted → green `CheckCircle` icon
   - not granted → `Allow` text button that invokes the matching launcher
     (`requestNotifPermission`, `requestBatteryOptIgnore`,
     `requestPhonePermission`)
   - the polling `LaunchedEffect` recomputes `*Granted` booleans every
     1.5 s, so the dialog updates without any extra signalling
3. The dialog's bottom button is **always `Done`** — there is no `Cancel`.
   On dismiss, if `permsDialogStartsProxy` was true,
   `actuallyStart()` runs regardless of grant state (declined perms are
   allowed; the proxy still works, only foreground/survival degrade).
4. The header's network-indicator click opens the same dialog with
   `permsDialogStartsProxy = false` — granting the optional pre-33 phone
   perm without starting the proxy.

**`READ_BASIC_PHONE_STATE` is auto-granted on API 33+** (normal install-time
permission); declared in the manifest with no prompt path. The pre-33
fallback is `READ_PHONE_STATE` with `android:maxSdkVersion="32"` so modern
users never see it in the dialog. The runtime check
`needsPhonePermission()` already encodes this — returns `false` on API 33+.

**Don't reintroduce the auto-chain.** It was originally there to walk one
system prompt at a time, but the per-item button design supersedes it and
is what the user explicitly asked for. The Android-13+ "auto-deny after
two denies" ANR loop is a non-issue here because each launcher is fired
by a discrete user tap, not from inside an activity-result callback.

Decline is fine for any of them — the service still runs; only the
foreground notification visibility / background survival / header tech
label degrade.

## Fixed in v1.1.0 (don't undo)

- **Rapid stop → start no longer hangs the proxy.** Root cause was the
  `scope.launch { ... awaitAvailable ... }` in `startProxy` being
  fire-and-forget: `stopProxy` never cancelled it, so its private watcher
  `NetworkCallback` stayed registered, fired `onAvailable` on the next
  cellular event, and resumed into a *second* `Socks5Server` that fought
  the new one for port 1080. The loser's `onFatal` called `cellular.stop()`
  and killed the winner's network handle. Fix: track the launch as
  `startJob` and call `fullCleanup()` at the top of both `startProxy`
  and `stopProxy`. Verified at 50/50 over LAN with no failures.
- **LAN inbound now works.** v0.2.1 → v1.0.0's
  `cm.bindProcessToNetwork(cellular)` was tagging the listener socket
  with the cellular netId, so the kernel routed SYN-ACK out the cellular
  interface instead of `wlan0` and external clients timed out at
  `SYN_RECV`. Removing the call restored normal Wi-Fi inbound; the
  cellular pinning still works because every outbound socket is
  individually bound via `Network.bindSocket()`.

## Open issues

- **Firefox "Secure Connection Failed — authenticity of the received data
  could not be verified" is *not* a proxy bug.** The user confirmed this
  reproduces with the proxy turned off, so it's their network's TLS
  interception / DPI behaviour on certain hosts, not anything we can fix
  from the app side. See Mozilla bug 1690853 and the SOCKS5 `socks5h://`
  remote-DNS notes in the README.

## Things that broke during early development (don't re-introduce)

- `byteArrayOf(0x05, 0x00)` fails to compile — `0x05` is `Int`, `byteArrayOf`
  needs `Byte`. Every byte literal needs `.toByte()`.
- `var x by animateFloat(...)` needs `import androidx.compose.runtime
  .getValue` — `animateFloat` returns `State<Float>`, which has `getValue`
  only as an extension.
- `suspendCancellableCoroutine { cont -> ... cont.resume(null) }` infers
  `T = Network` and rejects `null`. Type the call explicitly:
  `suspendCancellableCoroutine<Network?> { ... }`.
- `material3.ripple` doesn't exist in Compose BOM 2024.11.00 — use the
  default ripple from `Modifier.clickable(onClick = ...)`.
- `LocalDPColors`/`Ink`-as-`@Composable get()` is the right pattern, but
  *don't* try to use those getters inside top-level `darkColorScheme(...)`
  initialisers — those run at class init, not in a Composable scope. Resolve
  the DPColors instance inside the `DataProxyTheme` Composable and pass its
  fields into `darkColorScheme()` / `lightColorScheme()` there.

## User context

- **User testing environment.** The user is in Iran on the **mtnirancell**
  cellular carrier. Their cellular network blocks `8.8.8.8`, `example.com`,
  and most generic global probes. Use **`curl time.ir`** for any
  connectivity sanity check, end-to-end SOCKS5 smoke test, or DNS lookup
  test — the user explicitly asked for this. Their cellular DNS lives at
  `10.255.255.254` / `10.10.10.10`.
- **The phone identity is `R5CY50CMNWM`** (Samsung Galaxy, OneUI). It also
  sometimes shows an `emulator-5554` offline device; pass `-s` to adb to
  disambiguate.
- **The phone is usually locked** when you're testing — you can install,
  launch, and query state via adb, but you can't tap the UI yourself. The
  user does the interactive testing.
- **SOCKS5 clients need remote DNS** (`--socks5-hostname` / `socks5h://`
  / Firefox's "Proxy DNS when using SOCKS v5") to get the proxy's cellular
  DNS. Without it the client resolves locally, sends the resolved IP to
  the proxy as ATYP=0x01, and that IP may be unreachable / geo-routed /
  filtered on cellular even though it worked on Wi-Fi. Plain `--socks5`
  failures look like "proxy is broken" but aren't.

## Repo

GitHub: <https://github.com/Sir-MmD/dataproxy> (default branch `main`,
public, MIT). Latest release lives at the `/releases/latest` URL.
