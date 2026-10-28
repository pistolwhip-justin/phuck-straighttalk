# Phuck StraightTalk DiagnosticWTF

## Purpose

DiagnosticWTF is a separate diagnostic-only build based on the known-good Phuck StraightTalk v5.0.3 source. Its purpose is to observe current app behavior before any performance or operational changes are considered.

**Non-goal:** this branch must not implement wake-lock changes, background reporting changes, socket-buffer tuning, forwarding refactors, or other changes intended to alter the app's networking behavior.

## Separation

- Branch: `diagnostic/1.0.0`
- Independent version sequence: starts at `1.0.0`
- Application ID: `pro.pistolwhip.phuckstraighttalk.diagnostic`
- Release tag: `v1.0.0`
- APK asset: `PhuckStraightTalk-DiagnosticWTF-v1.0.0-debug.apk`
- Production branch/release baseline remains `main` / `v5.0.3`.
- Diagnostic changes must not be merged into production. If diagnostic findings justify a production change, implement that change separately without carrying over the diagnostic logger.

## Instrumentation in v1.0.0

- App foreground/background and activity lifecycle.
- Sender and receiver service commands and lifecycle.
- Sender and receiver partial wake-lock acquisition/release events.
- Wi-Fi Direct state transitions (without SSID/password details).
- Sender's existing traffic-stat callback values.
- Receiver TUN loop's existing once-per-second statistics cadence, active TCP flow count, packet count, TCP SYN count, and byte counters.
- Timestamp, elapsed realtime, process CPU time, battery percentage when Android exposes it, and event fields.
- Bounded private log storage with one previous log file.
- In-app export to a user-selected text file.

The instrumentation observes events at existing code paths; it does not change wake-lock policy, packet forwarding, retry policy, socket buffers, or statistics timing. Logging itself adds some overhead, so this build is for measurement and must not be treated as a production candidate.

## How to collect logs

1. Install DiagnosticWTF on the sender or receiver phone. Its distinct application ID allows it to coexist with the production app.
2. Reproduce the test condition and let it run for the intended duration.
3. Open DiagnosticWTF and tap **EXPORT DIAGNOSTIC LOGS**.
4. Save the text file and review it before sharing. The app avoids logging Wi-Fi Direct credentials, but logs may still contain operational metadata.

Logs remain in the app's private storage until exported. Uninstalling the diagnostic app removes its private logs unless you exported them first.

## Build and release

The dedicated workflow `.github/workflows/release-1.0.0.yml` builds from committed source on branch `diagnostic/1.0.0`, runs available debug unit tests, assembles a debug APK, and publishes it as an asset on the versioned GitHub Release. Existing releases are never overwritten.

## Validation status

The APK build, automated tests, release publication, installation, and physical-device behavior are separate checks. A successful workflow does not establish that the diagnostic app is operationally identical to production or that its logging overhead is negligible. Compare its network behavior to v5.0.3 on-device before using the logs as a baseline.
