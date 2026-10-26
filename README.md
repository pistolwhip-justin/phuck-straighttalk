# Phuck StraightTalk

**An independently rebranded build of DataNet Share 5.0.0.**

Share an Android phone's mobile data with other devices using the original project's SOCKS5 proxy and Wi-Fi Direct functionality. This repository preserves the imported upstream implementation; the current changes are limited to branding, application identity, and test-build/release setup.

## Test APK

The first rebranded test build is published as the **v5.0.0** GitHub Release asset when the versioned Actions workflow succeeds.

- [Releases and APK downloads](https://github.com/pistolwhip-justin/phuck-straighttalk/releases)
- [Build workflow status](https://github.com/pistolwhip-justin/phuck-straighttalk/actions/workflows/release-5.0.0.yml)

This is a **debug-signed sideload/testing APK**, not a Play Store or production-signed release. Because it uses a new application ID, it installs separately from the original DataNet Share app. Android may require permission to install apps from the browser or file manager.

## Original project and attribution

This project is based on [DataNet Share by Keggin-AI](https://github.com/Keggin-AI/DataNetShare). The original technical implementation and documentation are retained as much as practical during this branding pass. The upstream project is licensed under the MIT License; its original license/copyright notices must remain with the copied material. Branding and repository changes are by Justin S. / Pistolwhip Productions LLC.

- Website: https://pistolwhip.pro
- Contact: justin@pistolwhip.pro
- Support: [open an issue in this repository](https://github.com/pistolwhip-justin/phuck-straighttalk/issues)

## Build

The source project is in [source-import/datnet-share-v5-final/source](source-import/datnet-share-v5-final/source). The versioned GitHub Actions workflow builds the debug APK from committed source using JDK 17, Gradle 8.5, and Android SDK 34, runs available debug unit tests, and attaches the APK to the matching GitHub Release.

## Scope of this copy

This pass is a rebrand only. Existing project files, generated build-cache material, original APK, and security audit are being retained for the owner's test and review. Cleanup or technical changes beyond those necessary to build the rebranded APK are deferred until after testing.
