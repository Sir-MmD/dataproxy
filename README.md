<div align="center">

<img src="docs/icon.png" alt="DataProxy logo" width="128" height="128" />

# DataProxy

**Turn your Android phone into a SOCKS5 proxy whose traffic always goes out over mobile data.**

[![Release](https://img.shields.io/github/v/release/Sir-MmD/dataproxy?style=flat-square&color=3DDC97&label=release)](https://github.com/Sir-MmD/dataproxy/releases/latest)
[![License](https://img.shields.io/badge/license-MIT-3DDC97?style=flat-square)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%208.0%2B-3DDC97?style=flat-square)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/kotlin-2.0-3DDC97?style=flat-square&logo=kotlin&logoColor=black)](https://kotlinlang.org)

</div>

## What it does

Your laptop (or any device on the same Wi-Fi) points its SOCKS5 settings at
`phone-ip:1080`. DataProxy listens there and pins every outbound socket to
the phone's **cellular** network — so the egress goes through mobile data
even when Wi-Fi is the default. Other apps on the phone are untouched; only
the sockets DataProxy creates are pinned.

```
laptop  ── SOCKS5 over Wi-Fi ──▶  📱 DataProxy  ── TCP/UDP over LTE ──▶  internet
```

## Screenshot

<div align="center">
  <img src="docs/screenshots/home.jpg" alt="DataProxy home screen" width="320" />
</div>

## Install

Download the signed APK from the
[releases page](https://github.com/Sir-MmD/dataproxy/releases/latest) and
open it on your phone — tap to install. You may need to allow "install from
unknown sources" the first time.

> The APK is signed with a self-signed certificate, so Android will show
> the usual side-load warning on first install.

## Quickstart

1. **Launch DataProxy** and tap the big power button.
2. **First-run prompts** ask for the notification + battery-optimisation
   exemptions DataProxy needs to keep running in the background. If mobile
   data is off, you'll get a shortcut to the right settings panel.
3. **Optional — enable auth.** Open the **Auth** tile on the home screen to
   require a username/password from clients.
4. **Wire up a client.** From your laptop on the same Wi-Fi:
   ```
   curl --socks5-hostname <phone-ip>:1080 https://your-target.example
   ```
   Use `--socks5-hostname` (not `--socks5`) so DNS also goes over cellular —
   see [DNS and client config](#dns-and-client-config) below.

## Features

| | |
|---|---|
| ⚡ **Cellular pinning** | Per-socket `Network.bindSocket()` + process binding to the cellular `Network`. No root, no VPN service. |
| 🔌 **SOCKS5 TCP + UDP** | RFC 1928 CONNECT and UDP ASSOCIATE; RFC 1929 username/password auth (optional). IPv4 / IPv6 / domain ATYP. |
| 🌐 **Listen anywhere** | Bind to `0.0.0.0` or a specific interface (Wi-Fi, USB tether, hotspot). |
| 📊 **Live metrics** | Speed bars, cumulative traffic up/down, per-device connection list. |
| 🔋 **Auto-pause on data loss** | If mobile data drops the listener stays alive and resumes automatically when data is back. |
| 🛡️ **Foreground service** | Persistent notification keeps the proxy running with the screen off. |
| 🎨 **Light / dark / system theme** | Toggle under the app icon. OLED-black dark mode for real AMOLED power savings. |

## DNS and client config

DataProxy resolves hostnames using the **cellular network's DNS** when the
client sends a hostname in the SOCKS5 CONNECT (`ATYP=domain`). Many clients
default to **local DNS** instead, which leaks through Wi-Fi DNS and can
connect to wrong, blocked, or geo-routed IPs. To get the full cellular path,
configure your client for remote DNS:

| Client | Remote-DNS setting |
|---|---|
| `curl` | `--socks5-hostname` (not `--socks5`) |
| Firefox | tick **Proxy DNS when using SOCKS v5** in proxy settings |
| Chromium | `--proxy-server="socks5://host:1080"` (remote DNS is the default for SOCKS5) |
| Python `requests` / `urllib3` | `socks5h://host:1080` — the `h` matters |
| `proxychains-ng` | `proxy_dns` on (default) |

## Build from source

Android Studio Ladybug+ (or Gradle ≥ 8.10 with the Android SDK) and JDK 17+.

```
git clone https://github.com/Sir-MmD/dataproxy.git
cd dataproxy
./gradlew :app:assembleRelease
```

The release APK lands at `app/build/outputs/apk/release/app-release.apk`. If
no keystore is present at `app/keystore/dataproxy-release.jks` the build
produces an **unsigned** APK — sign it yourself with `apksigner`, or
generate a keystore:

```
keytool -genkeypair -v \
  -keystore app/keystore/dataproxy-release.jks \
  -alias dataproxy -keyalg RSA -keysize 4096 -validity 36500 \
  -storepass dataproxy -keypass dataproxy \
  -dname "CN=DataProxy, O=DataProxy, C=US"
```

## Caveats

- **Mobile data must be on.** Wi-Fi can be on too — that's the whole point —
  but the cellular network needs to be up.
- **Local-DNS SOCKS5 clients leak.** See
  [DNS and client config](#dns-and-client-config).
- **Carrier tethering policies** may detect and throttle the traffic
  pattern. DataProxy doesn't try to disguise itself.

## License

MIT — see [LICENSE](LICENSE).
