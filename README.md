# DataProxy

A black-themed Android SOCKS5 proxy server that accepts inbound connections on
your Wi-Fi / LAN and forces every outbound connection through the device's
**cellular network only**.

Think of it as turning your phone into a tiny gateway: laptops or other phones
on the same Wi-Fi point their SOCKS5 settings at `phone-ip:1080`, and their
traffic egresses via your SIM's mobile data — bypassing the local Wi-Fi
entirely.

> **Latest release:** [v0.1 APK](../../releases/latest) (signed, ready to side-load)

## Features

- **Per-socket cellular binding** via `ConnectivityManager.requestNetwork(TRANSPORT_CELLULAR)` + `Network.bindSocket()` — the official Android API for pinning egress to a specific transport.
- **SOCKS5 (RFC 1928)** server, no-auth, supports IPv4 / IPv6 / domain ATYP and the CONNECT command. DNS for hostname targets is resolved on the cellular network so you don't leak via Wi-Fi DNS.
- **Listen-address picker** — `0.0.0.0` (any interface) plus every detected non-loopback IPv4 (Wi-Fi, USB tether, ethernet).
- **Foreground service** with a persistent ongoing notification so Android won't kill the listener when the UI is backgrounded.
- **Live stats** — real-time upload/download speed (log-scaled bars), cumulative bytes per direction, per-client (IP) device list.
- **Battery-optimization prompt** — first-run banner that fires the system
  `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` dialog (or falls back to
  Settings on stripped OEM ROMs).
- **True OLED-black theme** (`#000000`), mint-green accent, monospaced numerics.

## Project layout

```
app/src/main/
├── AndroidManifest.xml
├── java/com/dataproxy/
│   ├── MainActivity.kt              # entrypoint, battery-opt prompt
│   ├── DataProxyApplication.kt
│   ├── network/
│   │   ├── CellularNetworkProvider.kt   # ConnectivityManager wrapper
│   │   └── NetworkInterfaceLister.kt    # local IP enumeration
│   ├── proxy/
│   │   ├── Socks5Server.kt              # accept loop
│   │   ├── Socks5Connection.kt          # RFC 1928 handshake + relay
│   │   └── ConnectionRegistry.kt        # device + traffic accounting
│   ├── service/
│   │   └── ProxyService.kt              # foreground host, notification
│   ├── ui/                              # Compose + Material3 + black theme
│   └── util/
└── res/
```

## Install

The fastest path is the **GitHub release**:

1. Download `app-release.apk` from the [latest release](../../releases/latest).
2. Transfer to your phone (or `adb install app-release.apk` from your computer).
3. Allow "install from unknown sources" if prompted.
4. Launch DataProxy.

> The release APK is signed with a self-signed certificate — Android may show
> a warning the first time you install it. That's expected for side-loaded
> apps.

## Build from source

Requirements:
- Android Studio Ladybug+ (or Gradle ≥ 8.10 with Android SDK installed)
- JDK 17 or newer (Android Studio bundles JDK 21 — that's what was used to build the v0.1 release)
- Android SDK platform `android-36`

```bash
git clone https://github.com/Sir-MmD/dataproxy.git
cd dataproxy

# Debug build (signed with the auto-generated debug key — fine for testing)
./gradlew :app:installDebug
```

To produce a signed **release** APK, generate your own keystore first:

```bash
mkdir -p app/keystore
keytool -genkeypair -v \
  -keystore app/keystore/dataproxy-release.jks \
  -alias dataproxy -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass dataproxy -keypass dataproxy \
  -dname "CN=DataProxy, O=DataProxy, C=US"

./gradlew :app:assembleRelease
# → app/build/outputs/apk/release/app-release.apk
```

Override the passwords via env vars if you don't want them in `build.gradle.kts`:
`DATAPROXY_KEYSTORE_PASSWORD`, `DATAPROXY_KEY_ALIAS`, `DATAPROXY_KEY_PASSWORD`.

If the keystore is missing, the release APK is built **unsigned**; sign it
yourself with `apksigner` afterwards.

## Using the app

1. **Launch DataProxy.** If the battery-optimization banner appears, tap **Allow**
   and confirm the system dialog. Without this, Android Doze can suspend the
   proxy after a few minutes of screen-off.
2. **Pick a listen address.** `0.0.0.0` lets clients on any of your phone's
   interfaces connect; choose a specific Wi-Fi IP if you only want LAN
   reachability.
3. **Hit the power button.** The service will request a cellular network
   (mobile data must be enabled — you can have Wi-Fi on simultaneously, that's
   the whole point), bind the listener, and turn the ring mint-green.
4. **Point clients at it.** On a laptop:
   - macOS / Linux: `curl --socks5-hostname phone-ip:1080 https://example.com`
   - Browser: configure SOCKS5 in network settings, `phone-ip` port `1080`.
   - SSH: `ssh -o "ProxyCommand=nc -X 5 -x phone-ip:1080 %h %p" user@host`
5. As clients connect they appear in **Connected devices** (grouped by source
   IP); the speedometer and totals update every second.

## How the cellular pinning works

```
LAN client  --(SOCKS5 over Wi-Fi)-->  DataProxy (phone)  --(TCP over LTE)-->  Internet
                                          │
                                          └── Network.bindSocket() forces
                                              every outbound socket onto
                                              the cellular Network object,
                                              regardless of system default.
```

The phone's own routing table is untouched — only the sockets DataProxy
creates are pinned. Other apps on the phone continue using whichever network
Android picks for them.

## Permissions

- `INTERNET`, `ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE` — to request and
  bind cellular networks.
- `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_DATA_SYNC` — long-lived service.
- `POST_NOTIFICATIONS` (API 33+) — for the ongoing status notification.
- `WAKE_LOCK` — keep the CPU running while connections are active.
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` — to surface the system whitelist
  dialog; the user still grants it explicitly.

## Caveats

- **Mobile data must be enabled** — even if Wi-Fi is providing your default
  internet, DataProxy needs the cellular network to be up so it can route
  through it. If cellular is missing, the connect call replies with
  `Network unreachable (0x03)` per RFC 1928.
- **Carrier policies** can block tethering-style traffic; some carriers detect
  and throttle. DataProxy does not work around this.
- **CONNECT only** — UDP-ASSOCIATE and BIND commands aren't implemented. That
  covers ~all real-world SOCKS5 traffic (HTTP, HTTPS, SSH) but rules out
  some QUIC / DNS-over-UDP setups.
- **No authentication** — anyone on the listen interface can use the proxy.
  Pin the listen address to a LAN-only IP, or run behind a Wi-Fi network you
  trust.
