# Working in this repo

A SOCKS5 proxy for Android that pins outbound traffic to the cellular network.
Project directory is still `xlink/` (kept for git history); the Gradle project,
Android package, and brand are all `DataProxy` / `com.dataproxy`.

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
adb install -r /tmp/DataProxy-vX.Y.Z.apk

# Crash check
adb shell am force-stop com.dataproxy && adb logcat -c
adb shell monkey -p com.dataproxy -c android.intent.category.LAUNCHER 1
adb logcat -d AndroidRuntime:E "*:S" | tail -20
```

`org.gradle.java.home=/opt/android-studio/jbr` is set in `gradle.properties`
so command-line builds pick up the JBR automatically (system Java is 26,
which Gradle 8.10 doesn't officially support — it works but warns).

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

The signing keystore is `app/keystore/dataproxy-release.jks` (gitignored,
password `dataproxy`). Same key has been used for every release — installs
upgrade in place. If the keystore file is missing, the build still succeeds
with an unsigned APK (signing config is conditional in `build.gradle.kts`).

## Architecture invariants

These are load-bearing — break them and the app stops working.

- **Process is pinned to cellular.** `CellularNetworkProvider.onAvailable`
  calls `cm.bindProcessToNetwork(network)` so any JVM-default DNS also goes
  over LTE — this is the DNS-leak fix. Don't remove it, and remember to
  null it out on `onLost` / `onUnavailable` / `stop()`.
- **Domain DNS goes through `Network.getAllByName`.** In `Socks5Connection
  .openRemote`, hostnames are resolved via `cellular.resolveHost(host)` —
  not Java's default resolver. Process binding is belt-and-suspenders on top.
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
- **No `cm.allNetworks` pre-check for mobile data.** Samsung's OneUI hides
  the cellular network from `allNetworks` when Wi-Fi is the active default,
  which made the v0.2 pre-check return false positives. The dialog is driven
  by an actual `requestNetwork` attempt inside the service with a 6 s
  timeout (`ProxyService.startProxy`).

## UI conventions

- **Home fits one screen, no scrolling.** Power button is 156 dp, paddings
  are tight, the battery-opt prompt is a one-time dialog (not a banner).
  Verified by screenshot on a 1080×2340 device. Adding more cards to Home
  will break the layout — put new things on a subscreen and add a nav tile.
- **Listen and Devices are separate screens** reached via nav tiles on Home,
  not embedded sections. `ListenAddressScreen` and `DevicesScreen` both call
  `BackHandler(onBack = onBack)` at the top so hardware back returns to Home
  instead of finishing the activity.
- **Tab navigation is manual** (`var tab by rememberSaveable { ... Tab.Home
  }` in `MainActivity` + `when (tab)` in `AppNav`). We deliberately do not
  pull in `androidx.navigation:navigation-compose` for three screens.
- **OLED-black palette is in `ui/theme/Color.kt`** — keep `Ink = #000000`
  for real AMOLED power savings. Mint accent `#3DDC97`, warning amber for
  Paused / battery dialog, danger red for Error.

## Permissions flow

Triggered only when the user taps the power button — never at launch.

1. `MainActivity.onPowerToggle` checks `needsNotifPermission()` and
   `BatteryOptimizationHelper.isIgnoring()`.
2. If either is missing, shows `PermissionsDialog` (in `AppNav.kt`) with
   per-permission reasons.
3. On user confirm, `startPermsChain()` requests them sequentially via the
   `notifPermission` / `batteryOptResult` activity-result launchers. Each
   callback re-enters `continuePermsChain()` until both are handled, then
   calls `actuallyStart()`.

Decline is fine for either — the service still runs; only the foreground
notification visibility / background-survival changes.

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

## Repo

GitHub: <https://github.com/Sir-MmD/dataproxy> (default branch `main`,
public, MIT). Latest release lives at the `/releases/latest` URL.
