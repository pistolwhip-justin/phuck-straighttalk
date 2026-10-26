# DataNet Share v5.0 (Unified)

## Contents
- `DataNetShare-v5.0-unified.apk` — Signed APK (install on both phones)
- `source/` — Full source code (Gradle project, in its own subfolder)
- `DataNetShare-Security-Audit.md` — Original security audit report

## Installation
```bash
adb uninstall com.datanet.share
adb install DataNetShare-v5.0-unified.apk
```
Same APK on both sender and receiver phones.

## Features
- **Unified codebase**: v4.0 sender + v3.5 receiver, no native code
- **VPN-aware sockets**: Carrier sees only your VPN endpoint (e.g., Google VPN)
- **Link speed test**: Tap "LINK SPEED TEST" on the receiver to measure WiFi Direct link speed (10MB payload, no internet needed)
- **MB display**: Notification shows data transfer in MB (not KB) when >= 1MB
- **All security patches**: See DataNetShare-Security-Audit.md for full details

## Usage

### Sender phone:
1. Turn ON your VPN (e.g., Google VPN) if you want carrier privacy
2. Open app → tap START SHARING (WiFi Direct)
3. Note the SSID and password shown in the blue card

### Receiver phone:
1. Connect to the sender's WiFi Direct network (Android Settings → WiFi → WiFi Direct)
2. Open app → tap START RECEIVING
3. Grant VPN permission when prompted

### Link Speed Test:
- On the receiver, tap "LINK SPEED TEST" — downloads 10MB from sender through SOCKS5
- Reports Mbps in the log
- Tests the WiFi Direct link speed, NOT internet speed

## Building from source
```bash
cd source
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```
Requires: JDK 17, Android SDK 34, Gradle 8.5+

## Security
All audit findings patched. See DataNetShare-Security-Audit.md for the full report.

Signed with a freshly-generated keystore. If you have the original signing key,
re-sign with `apksigner` to preserve update compatibility.
