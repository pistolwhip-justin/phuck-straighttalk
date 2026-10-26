# DataNet Share

Share your Android phone's mobile data with other devices — without using the carrier hotspot feature.

Bypasses carrier tethering detection by using WiFi Direct (a separate radio path from the carrier hotspot) and binding upstream sockets directly to the cellular network via `Network.getSocketFactory()`. No root required.

## The Problem

Carriers charge extra for tethering/hotspot, or cap it at low speeds, even when you have unlimited phone data. This app lets you share your phone's mobile data with another Android phone, a PC or Mac, or any device that supports SOCKS5 proxy — without triggering the carrier's hotspot detection.

## Features

- **No root required** — uses Android's VpnService and WiFi Direct APIs
- **Three sharing modes:** WiFi Direct (phone-to-phone), Local-only (USB/WiFi to PC/Mac), Receiver
- **Full TCP support** — userspace TCP stack with window scaling (RFC 7323)
- **Full UDP support** — SOCKS5 UDP ASSOCIATE with NAT table (Discord voice, WebRTC, gaming)
- **Native C library** — checksums and TUN I/O in C for maximum throughput (10x faster than Kotlin)
- **325-400 Mbps real-world throughput** (faster than native speed test)
- **Cellular-bound sockets** — upstream traffic forced via mobile data, not WiFi
- **Survives screen-off** — partial wake lock keeps the CPU running
- **Built-in UDP test** — STUN-based connectivity test
- **PC/Mac support** — any device with SOCKS5 proxy support can connect
- **Release-signed APK** — 100-year keystore validity

## Speeds (real-world, on a Pixel 10 Pro XL)

| Method | Download | Upload |
|--------|----------|--------|
| WiFi Direct (phone-to-phone) | **400 Mbps** | ~80 Mbps |
| USB tethering (phone-to-Mac) | **325 Mbps** | ~80 Mbps |
| WiFi local (phone-to-PC) | ~100 Mbps | ~50 Mbps |

These speeds actually exceed what the phone gets natively on a speed test — likely because the carrier applies QoS/throttling to speed test traffic, but traffic through the SOCKS5 proxy looks like generic app traffic.

## How to install

1. Download `DataNetShare-v4.0-release.apk` from the releases page
2. Copy it to your Android phone (both phones if sharing phone-to-phone)
3. Open the APK and accept the "install unknown app" prompt
4. Open the app and grant all requested permissions

The APK is signed with a release certificate. Uninstall any old version first.

## How to use

### Phone-to-phone (WiFi Direct mode)

**On the sender phone:**
1. Make sure mobile data is ON and WiFi is ON
2. Open the app → tap **START SHARING (WiFi Direct)**
3. Note the SSID and password shown in the blue card

**On the receiver phone:**
1. Android Settings → WiFi → WiFi Direct
2. Tap the sender's SSID → enter the password
3. Open the app → tap **START RECEIVING**
4. Grant VPN permission when prompted

### Phone-to-PC/Mac (USB tethering — fastest)

**On the sender phone:**
1. Open the app → tap **START LOCAL ONLY (USB/WiFi)**
2. Settings → Hotspot & tethering → enable **USB tethering**
3. Tap **REFRESH INTERFACES** → find the USB interface (often `ncm0` or `rndis0`) and note its IP

**On your PC/Mac:**
1. Set SOCKS5 proxy to the phone's USB IP, port `8282`
   - **Mac:** System Settings → Network → USB → Details → Proxies → SOCKS Proxy
   - **Firefox:** Settings → Network Settings → Manual proxy → SOCKS Host: the IP, Port: 8282, select SOCKS v5, check "Proxy DNS when using SOCKS v5"

### Phone-to-PC/Mac (WiFi — no cable)

1. Both devices on same WiFi
2. Sender: tap **START LOCAL ONLY** → **REFRESH INTERFACES** → note WiFi IP
3. PC/Mac: set SOCKS5 proxy to that IP, port `8282`

## How it works

```
SENDER PHONE                           RECEIVER DEVICE
├── WifiDirectHost.kt                  ├── Tun2Socks.kt          (userspace IPv4/TCP/UDP stack)
│   └── Creates WiFi Direct group      │   ├── TcpFlow.kt        (TCP state + window scaling)
│       (becomes 192.168.49.1)         │   └── SharedUdpFlow.kt  (single UDP associate)
├── Socks5Server.kt                    │
│   ├── TCP CONNECT handler            ├── ReceiverVpnService.kt (VpnService, TUN fd)
│   └── UDP ASSOCIATE handler          │
│       (two-socket: relay + upstream) ├── NativeHelper.kt       (JNI bridge to C library)
├── SenderService.kt                   │
│   └── Foreground service + wake lock └── MainActivity.kt       (UI + log + tests)
│
├── CellularNetworkTracker.kt
│   └── NetworkCallback for cellular
│       (socketFactory.createSocket for TCP)
│
└── Native C library (libdatanet_native.so)
    ├── Native checksum computation (10x faster than Kotlin)
    ├── Native TUN I/O (direct read/write syscalls)
    └── Compiled for arm64-v8a, armeabi-v7a, x86, x86_64
```

### The key tricks

1. **WiFi Direct, not carrier hotspot** — separate radio path, no carrier detection
2. **`Network.socketFactory.createSocket()`** — TCP sockets bound to cellular at kernel level
3. **Split `/1` routes** — `0.0.0.0/1` + `128.0.0.0/1` beats kernel default route on Android 11+
4. **Native C checksums + TUN I/O** — 10x faster than Kotlin
5. **TCP window scaling (RFC 7323)** — 8MB window, single connections fill 400 Mbps pipe
6. **4MB socket buffers** — matches bandwidth-delay product for high-speed cellular
7. **Two-socket UDP relay** — WiFi Direct socket + cellular socket, with NAT table
8. **`VpnService.protect()` on all bypass sockets** — prevents VPN loops

## Building from source

### Requirements

- JDK 17
- Android SDK: platform-tools, platforms;android-34, build-tools;34.0.0, cmake;3.22.1, ndk;26.1.10909125
- Gradle 8.5+ (wrapper included)

### Build

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk

# Create local.properties
echo "sdk.dir=$ANDROID_HOME" > local.properties

# Generate keystore (one-time)
keytool -genkeypair -v -keystore keystore.jks \
  -keyalg RSA -keysize 2048 -validity 36500 -alias datanet \
  -storepass YOURPASS -keypass YOURPASS \
  -dname "CN=DataNet Share, OU=App, O=DataNet"

# Edit app/build.gradle.kts signingConfigs.release to point to your keystore

# Build release APK
./gradlew assembleRelease
```

Output: `app/build/outputs/apk/release/app-release.apk`

## Troubleshooting

**"createGroup failed: BUSY"** — Quick Share / Nearby Share is holding WiFi Direct. Disable both, toggle WiFi, retry.

**MacBook shows "no internet" on USB** — normal. macOS checks the phone's default route, not our proxy. Set the SOCKS5 proxy and it works.

**Nothing loads even with proxy set** — macOS SOCKS proxy doesn't proxy DNS. Use Firefox with "Proxy DNS when using SOCKS v5" checked.

**App crashes** — use the STOP button (force-stops and kills process), then restart.

## Privacy

- No telemetry, no analytics, no trackers
- Traffic contents are NOT logged (only byte counts)
- All receiver traffic goes through the sender's cellular connection

## License

MIT — do whatever you want with it.

## Why this exists

Because carriers charging extra for tethering — a feature the phone already has — is bullshit. The hardware can share data. The software can share data. The carrier just flips a flag to disable it unless you pay. This app goes around the flag.
