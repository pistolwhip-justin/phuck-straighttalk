# Phuck StraightTalk

Share your Android phone's mobile data with other devices using the original DataNet Share implementation — without using the carrier hotspot feature.

This repository is an independently rebranded copy of [DataNet Share](https://github.com/Keggin-AI/DataNetShare), originally published by Keggin-AI. This branding pass is intended to preserve the original app's technical behavior and documentation; it does not independently validate upstream performance claims or carrier compatibility.

## Features retained from the upstream project

- No root required — uses Android's VpnService and Wi-Fi Direct APIs
- Wi-Fi Direct phone-to-phone, local-only USB/Wi-Fi to PC/Mac, and receiver modes
- SOCKS5 TCP and UDP support
- Cellular-bound upstream sockets
- Screen-off operation using a partial wake lock
- Built-in UDP connectivity test
- No telemetry, analytics, or trackers according to the upstream documentation

Refer to the original documentation below for the existing setup instructions, architecture notes, troubleshooting, and implementation details. Those instructions are retained as upstream technical material and may include original project names or version-specific details.

## Install the test APK

Download the APK from the [Phuck StraightTalk GitHub Releases](https://github.com/pistolwhip-justin/phuck-straighttalk/releases). The initial rebranded build is debug-signed for sideload testing and is not a production/Play Store release. The new application ID allows it to install alongside the original app.

## Original upstream documentation

The technical documentation and original project material remain in this repository. The upstream project is [DataNet Share by Keggin-AI](https://github.com/Keggin-AI/DataNetShare). Original license and copyright notices are retained with the imported material.

## Project contact

- **Developer:** Justin S.
- **Company:** Pistolwhip Productions LLC
- **Website:** https://pistolwhip.pro
- **Email:** justin@pistolwhip.pro
- **Support:** [GitHub Issues](https://github.com/pistolwhip-justin/phuck-straighttalk/issues)

## License and attribution

The original DataNet Share project is MIT-licensed. See the original repository and any license/copyright notices included with the imported source. Rebranding and repository-specific additions are by Justin S. / Pistolwhip Productions LLC; this does not remove or replace upstream attribution.
