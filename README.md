<div align="center">

<img src="docs/icon.png" alt="DataProxy logo" width="128" height="128" />

# DataProxy

**A native Android SOCKS5 proxy that forces every outbound socket onto the cellular network — even when Wi‑Fi is the system default.**

[![Release](https://img.shields.io/github/v/release/Sir-MmD/dataproxy?style=flat-square&color=3DDC97&label=release)](https://github.com/Sir-MmD/dataproxy/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-3DDC97?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/Android-8.0%2B%20(API%2026%E2%80%9336)-3DDC97?style=flat-square&logo=android&logoColor=black)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-3DDC97?style=flat-square&logo=kotlin&logoColor=black)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Jetpack%20Compose-Material%203-3DDC97?style=flat-square)](https://developer.android.com/jetpack/compose)

</div>

---

## Overview

DataProxy runs entirely on the phone as a SOCKS5 server. Any client on the
same network — a laptop, a TV box, another phone — points its SOCKS5
settings at `phone-ip:1080`, and every TCP/UDP packet that leaves the proxy
is bound to the cellular `Network` handle. The listener itself stays on the
Wi‑Fi interface, so inbound clients can reach it without any VPN setup, root,
or tethering tricks.

```
  laptop / TV / phone
        │
        │  SOCKS5 over Wi‑Fi
        ▼
   📱 DataProxy ─── TCP/UDP pinned to LTE/5G ──▶  internet
        ▲
        │  DNS resolved via cellular's resolver
```

No VPN service is registered, no `iptables` rules are touched, and no other
app on the phone is affected — only the sockets DataProxy explicitly opens
are network‑bound to cellular.

## Screenshot

<div align="center">
  <img src="docs/screenshots/home.jpg" alt="DataProxy home screen on Android" width="320" />
</div>

## Features

- **Cellular pinning per socket.** Outbound TCP and UDP are bound to the
  cellular `Network` via `Network.bindSocket()` / `bindDatagram()` — not a
  process‑wide hijack.
- **Full SOCKS5 surface.** RFC 1928 `CONNECT` and `UDP ASSOCIATE`; RFC 1929
  username/password auth (optional, live‑toggleable). IPv4 / IPv6 / domain
  address types.
- **Cellular DNS.** Hostnames sent as `ATYP=domain` are resolved through
  `Network.getAllByName()` on the cellular handle, so DNS cannot leak to
  Wi‑Fi or local LAN resolvers.
- **Live network indicator.** Header shows the active operator name and the
  current radio tech (`2G` / `3G` / `4G` / `5G`) read straight from
  `TelephonyManager`; updates as the device hands over between cells.
- **Auto‑pause on data loss.** If cellular drops the listener stays bound
  and the service enters `Paused`; when data is back the watcher coroutine
  flips it to `Running` automatically.
- **Live metrics.** Per‑second up/down speed, cumulative traffic counters,
  and a per‑client device list (deduplicated by IP, ordered by activity).
- **Foreground service.** Keeps running with the screen off, with a
  persistent low‑importance status notification.
- **Anti‑Kill survival hub.** A dedicated screen that surfaces the OEM
  settings — battery optimisation, auto‑launch, background activity, and the
  Recents lock — that stop aggressive Android skins from killing the proxy,
  with live status and per‑vendor deep links. The same prompts are folded
  into the start‑up permissions dialog.
- **Auto‑start on boot.** Optional: the proxy relaunches itself after the
  phone reboots.
- **Auth subscreen.** RFC 1929 username/password, toggle without
  restarting the proxy.
- **Light / dark / system theming.** Per‑theme palette in a Compose
  composition local; persisted across launches.
- **Material 3 single‑activity UI.** One screen, manual tab nav, no scroll
  on standard phone displays.

## Architecture

```
MainActivity ────────────────▶ MainViewModel ──────▶ ProxyService (foreground)
   │                              │                        │
   │ Jetpack Compose UI           │  ServiceConnection     │
   │ — Home / Listen / Devices /  │  + StateFlow mirror    │
   │   Auth / Anti‑Kill screens   │                        │
   │                              │                        ▼
   │                              │             Socks5Server (listener)
   │                              │                    │
   │                              │           ┌────────┴────────┐
   │                              │           ▼                 ▼
   │                              │      Socks5Connection   Socks5UdpRelay
   │                              │       (TCP CONNECT)     (UDP ASSOC.)
   │                              │           │                 │
   │                              │           └─ cellular.bindSocket() ─┐
   │                              │                                     │
   │                              ▼                                     ▼
   │                     CellularTechMonitor                CellularNetworkProvider
   │                     (operator + 2G/3G/4G/5G,           (Network handle, DNS,
   │                      from TelephonyManager)             auto‑reconnect on drop)
```

| Component | Responsibility |
|---|---|
| `MainActivity` | Compose host, permission launchers, dialogs (perms, mobile‑data‑off), power‑button toggle |
| `MainViewModel` | Mirrors `ProxyService` state via `ServiceConnection`; owns `CellularTechMonitor` and shared‑prefs state |
| `ProxyService` | Foreground service, lifecycle of the listener and cellular handle, single `fullCleanup()` invoked on every start *and* stop |
| `CellularNetworkProvider` | Lazy singleton holding the cellular `Network`; per‑socket `bindSocket()` for egress; `awaitAvailable()` with a 15 s timeout |
| `CellularTechMonitor` | Polls the *data* subscription's `TelephonyManager` every 2 s for `dataNetworkType` + SIM operator name (dual‑SIM safe, stable carrier label); surfaces `DataOff` / `Tech("4G")` / etc. |
| `BootReceiver` | Relaunches `ProxyService` after `BOOT_COMPLETED` when auto‑start is enabled; reads the saved listen address / port |
| `Socks5Server` | `ServerSocket` accept loop, dispatches each client to a `Socks5Connection` on its own coroutine |
| `Socks5Connection` | RFC 1928 negotiation, RFC 1929 auth, `CONNECT` tunnel with bidirectional copy; `SO_LINGER=0` RST on failed handshakes so carrier NAT entries don't linger |
| `Socks5UdpRelay` | Dual‑socket UDP relay: client side bound to the same listen address, remote side bound to cellular; rejects `FRAG != 0` |
| `ConnectionRegistry` + `SpeedSampler` | Live counters: cumulative bytes, active TCP sessions, per‑device aggregates, 1 Hz rate sampling |

## Tech stack

| Layer | Choice | Why |
|---|---|---|
| Language | **Kotlin 2.x** | Coroutines + StateFlow first‑class |
| UI | **Jetpack Compose** + **Material 3** | Single‑activity, declarative, no XML layouts |
| Async | **kotlinx.coroutines** + **Flow / StateFlow** | Cooperative cancellation throughout the proxy + UI |
| Networking | `java.net.ServerSocket` + Android `Network.bindSocket()` | No third‑party netty/okhttp on the data path — just JDK sockets pinned to the cellular `Network` |
| Cellular state | `ConnectivityManager.requestNetwork` + `TelephonyManager` | Two independent paths: provider gets a `Network` handle for binding; monitor reads `dataNetworkType` / `networkOperatorName` for the header |
| Background | **Foreground `Service`** with `specialUse` foreground type | Survives the activity, holds a partial wake lock while running; `specialUse` (not `dataSync`) so it can also be relaunched from a `BOOT_COMPLETED` receiver on Android 15+ |
| Persistence | `SharedPreferences` (`dataproxy_prefs`) | Bind address, port, auth toggle/creds, theme mode |
| IPC | `ServiceConnection` + `LocalBinder` | Activity binds the service to read its `StateFlow`s directly; no AIDL |
| Build | **Gradle 8.10** + **Android Gradle Plugin** | Kotlin DSL, R8 minify, signing config conditional on keystore presence |
| Min SDK | **26** (Android 8.0 Oreo) | `TelephonyManager.isDataEnabled()` + foreground service requirements |
| Target / Compile SDK | **36** (Android 16) | Latest stable API surface; uses `READ_BASIC_PHONE_STATE` (normal perm, API 33+) |
| Signing | Self‑signed RSA 4096, 100‑year validity | Stable upgrade install across releases |
| Tooling | JBR 21 (bundled with Android Studio) | Set via `org.gradle.java.home` in `gradle.properties` |

External runtime dependencies are kept deliberately minimal: AndroidX core,
Compose UI / Material 3, Lifecycle, Activity Compose. No Retrofit, no
OkHttp, no Hilt — every networking call is hand‑written JDK code on a
cellular‑bound `Network`.

## Permissions

| Permission | Type | Used for |
|---|---|---|
| `INTERNET` | normal | Outbound sockets |
| `ACCESS_NETWORK_STATE` | normal | `ConnectivityManager` + `isDataEnabled` |
| `ACCESS_WIFI_STATE` | normal | Enumerating local bind addresses on the Wi‑Fi interface |
| `CHANGE_NETWORK_STATE` | normal | `requestNetwork` callback registration |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | normal | Keep proxy alive in background; `specialUse` also allows relaunch on boot |
| `WAKE_LOCK` | normal | Partial wake lock while listener is up |
| `RECEIVE_BOOT_COMPLETED` | normal | Relaunch the proxy after a reboot (optional, off by default) |
| `POST_NOTIFICATIONS` | **runtime, Android 13+** | Foreground service status notification — asked when you tap Start |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | runtime intent | Prevents Doze from suspending the listener — asked when you tap Start |
| `READ_BASIC_PHONE_STATE` | normal (API 33+) | `getDataNetworkType()` for the header tech label — auto‑granted, no prompt |
| `READ_PHONE_STATE` | runtime, **API ≤ 32 only** (`maxSdkVersion="32"`) | Pre‑Tiramisu fallback for the same tech read |

The permission dialog is **non‑blocking** — every entry has a per‑item
*Allow* button, granted items show a tick, and you can leave the dialog at
any time. The proxy works without `READ_PHONE_STATE`; the header just falls
back to the operator name on its own.

## Install

Download the signed APK from the
[releases page](https://github.com/Sir-MmD/dataproxy/releases/latest) and
open it on your phone. You may need to allow *install from unknown sources*
the first time.

> The APK is signed with a self‑signed certificate, so Android shows the
> usual side‑load warning on first install. Subsequent updates upgrade in
> place (same signing key across releases).

## Quickstart

1. **Launch DataProxy** and tap the big power button.
2. **First‑run prompts.** A dialog lists the permissions the proxy needs.
   Tap *Allow* next to each item; granted ones show a tick. If mobile data
   is off, a dialog with a shortcut to the right settings panel appears
   instead.
3. **Optional — keep it alive.** Tap the *Anti‑Kill* chip in the header to
   walk the per‑vendor battery / auto‑launch settings and to enable
   auto‑start on boot, so the proxy survives Doze, swipe‑away, and reboots.
4. **Optional — enable auth.** Open the *Auth* tile on the home screen to
   require a username/password from clients (RFC 1929).
5. **Connect a client.** From any device on the same Wi‑Fi:
   ```
   curl --socks5-hostname <phone-ip>:1080 https://your-target.example
   ```
   Use `--socks5-hostname` (not `--socks5`) so DNS also goes over cellular
   — see [DNS and client config](#dns-and-client-config) below.

## DNS and client config

DataProxy resolves hostnames using the **cellular network's DNS** when the
client sends `ATYP=domain` in the SOCKS5 CONNECT. Many clients default to
**local DNS** instead, which leaks through the Wi‑Fi resolver and can
connect to blocked or geo‑routed IPs even though the SOCKS path is fine.
Configure your client for remote DNS:

| Client | Remote‑DNS setting |
|---|---|
| `curl` | `--socks5-hostname` (not `--socks5`) |
| Firefox | tick **Proxy DNS when using SOCKS v5** in proxy settings |
| Chromium | `--proxy-server="socks5://host:1080"` — remote DNS is the SOCKS5 default |
| Python `requests` / `urllib3` | `socks5h://host:1080` — the `h` matters |
| `proxychains-ng` | `proxy_dns` on (default) |

## Build from source

Android Studio Ladybug+ or Gradle ≥ 8.10 with the Android SDK and JDK 17+:

```
git clone https://github.com/Sir-MmD/dataproxy.git
cd dataproxy
./gradlew :app:assembleRelease
```

The release APK lands at `app/build/outputs/apk/release/app-release.apk`.
If no keystore is present at `app/keystore/dataproxy-release.jks` the build
still succeeds and produces an **unsigned** APK — sign it yourself with
`apksigner`, or generate a keystore:

```
keytool -genkeypair -v \
  -keystore app/keystore/dataproxy-release.jks \
  -alias dataproxy -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass dataproxy -keypass dataproxy \
  -dname "CN=DataProxy, O=DataProxy, C=US"
```

## Limitations

- **Mobile data must be on.** Wi‑Fi can stay on too — that's the entire
  point — but the cellular network needs to be up. The proxy detects this
  before starting and prompts you to enable mobile data.
- **`SOCKS5 BIND` is not implemented.** The server replies
  `REP_COMMAND_NOT_SUPPORTED` (0x07) for that command — almost no client
  uses it, and supporting it would mean accepting inbound on cellular which
  carriers typically NAT.
- **UDP fragmentation is rejected.** `FRAG != 0` returns nothing; matches
  what every common UDP client sends.
- **Local‑DNS SOCKS5 clients leak.** See
  [DNS and client config](#dns-and-client-config).
- **Carrier tethering policies** may flag the traffic pattern. DataProxy
  doesn't try to disguise itself.
- **TLS errors from clients are usually a *network* problem**, not a proxy
  bug — e.g. carrier DPI / TLS interception on certain hosts. The same
  request fails the same way with the proxy off.

## License

MIT — see [LICENSE](LICENSE).
