# DataNet Share — Full Security Audit Report

**Audited artifacts**
- `datanet-share-v3.5-source.zip` (versionCode 22, versionName "3.5") — deployed on the **receiver** phone
- `datanet-share-v4.zip` (versionCode 23, versionName "4.0") — deployed on the **sender** phone (adds native C library, larger buffers, TCP window scaling)

**Audit type:** Static source-code review (Kotlin, Java, C++ JNI, Gradle, Android manifest).
**Auditor model:** Honest-but-curious carrier; compromised peer on the local/WiFi Direct network; physical access to either device (lock screen unlocked).
**Report date:** 2026-08-16.

---

## 1. Executive Summary

DataNet Share is a self-contained SOCKS5-over-WiFi-Direct tethering app that bypasses carrier hotspot detection. The sender phone (v4.0) listens on a SOCKS5 proxy and routes upstream traffic out of the cellular modem via `Network.socketFactory.createSocket()` / `Network.bindSocket()`. The receiver phone (v3.5) brings up an Android `VpnService`, parses IP/TCP/UDP in userspace, and forwards every byte to the sender's SOCKS5 proxy over the WiFi Direct link.

**Overall risk:** **HIGH.** The app does what it claims cleanly, and the native C code is benign. However, the SOCKS5 server has **no authentication** and binds to **`0.0.0.0`** in local-only mode (PC/Mac sharing), which makes it an **open proxy** reachable by anyone on the same WiFi. There is no transport encryption between receiver and sender (the WiFi Direct WPA2 password is the only gate), the production signing keystore password is hardcoded in the repo, `usesCleartextTraffic="true"` disables a key Android protection, and DNS is hard-pinned to `8.8.8.8` over the carrier's unencrypted network.

**Top three things to fix before relying on this in any non-trusted environment:**

1. Add SOCKS5 username/password authentication (RFC 1929) to `Socks5Server.kt` and bind the local-only listener to a single interface (e.g. the USB tethering interface), not `0.0.0.0`.
2. Remove `android:usesCleartextTraffic="true"` and `android:allowBackup="true"` from the manifest.
3. Move the keystore password out of `build.gradle.kts` and into `local.properties` / environment variables; rotate the keystore password immediately because the current one is in the source archive you distributed.

No remote-code-execution vulnerabilities were found. The native C library has one defensive-coding gap that is not currently exploitable from outside the app sandbox (see §4.4).

---

## 2. Scope and Methodology

### 2.1 In scope

- All Kotlin sources under `app/src/main/java/com/datanet/share/` (15 files per version).
- The C++ JNI library `app/src/main/cpp/native.cpp` and `CMakeLists.txt` (v4.0 only).
- `AndroidManifest.xml`, `build.gradle.kts`, `gradle.properties`, `settings.gradle.kts`, `proguard-rules.pro`.
- The packaged APKs in `releases/*.apk` were verified to exist but not byte-for-byte reverse-engineered; the audit is source-based.

### 2.2 Out of scope

- Dynamic analysis (running the app in a sandbox, fuzzing the TUN parser, MITM-ing the WiFi Direct link).
- Reverse engineering the bundled APKs to verify they match the source.
- Third-party dependencies (AndroidX, Kotlin coroutines) — assumed trusted, but `androidx.localbroadcastmanager` is officially deprecated and could be replaced with `LiveData`/`Flow`.
- Supply-chain attacks on the Gradle repositories (`google()`, `mavenCentral()`).

### 2.3 Methodology

Each source file was reviewed line-by-line against the following checklists:

- OWASP Mobile Top 10 (2024): M1 Improper Credential Usage, M2 Insecure Communication, M3 Insecure Authentication, M4 Insufficient Cryptography, M5 Insecure Authorization, M6 Insecure Configuration, M7 Susceptible Code Quality, M8 Security Misconfiguration, M9 Reverse Engineering, M10 Extraneous Functionality.
- CWE categories relevant to a SOCKS5/TUN proxy: CWE-22 (path traversal — N/A), CWE-78 (OS command injection — N/A), CWE-119 (buffer overflow in JNI), CWE-125 (OOB read), CWE-287 (improper authentication), CWE-306 (missing authentication for critical function), CWE-319 (cleartext transmission), CWE-326 (weak crypto), CWE-532 (logfile leakage), CWE-732 (overly permissive permission), CWE-749 (exposed dangerous method), CWE-770 (resource exhaustion), CWE-918 (SSRF — relevant because the SOCKS5 server is an SSRF primitive if unauthenticated).

---

## 3. Architecture Overview

```
SENDER PHONE (v4.0)                              RECEIVER PHONE (v3.5) / PC / MAC
─────────────────────                            ─────────────────────────────
┌─────────────────────────┐                      ┌──────────────────────────┐
│ MainActivity.kt         │                      │ MainActivity.kt          │
│  - Start/Stop/Log UI    │                      │  - Start/Stop/Log UI     │
└───────────┬─────────────┘                      └───────────┬──────────────┘
            │ Intent.ACTION_START                             │ Intent.ACTION_START
            ▼                                                 ▼
┌─────────────────────────┐                      ┌──────────────────────────┐
│ SenderService (FG svc)  │                      │ ReceiverService         │
│  - WifiDirectHost       │   WiFi Direct group   │  → ReceiverVpnService  │
│    192.168.49.1:8282    │ ┌──────────────────► │  - VpnService.Builder   │
│  - Socks5Server         │ │  SOCKS5 + UDP ASSOC │  - TUN fd 10.10.10.1/24 │
│    bind 0.0.0.0:8282 OR │ │  WPA2 PSK only      │  - routes 0.0.0.0/1 +   │
│    bind 192.168.49.1    │ │                     │           128.0.0.0/1    │
│  - CellularNetworkTracker│ │                     │ - Tun2Socks (userspace) │
│    Network.getSocketFactory │                     │   - TcpFlow             │
│    Network.bindSocket  │ │                     │   - SharedUdpFlow        │
└─────────────────────────┘ │                     └──────────────────────────┘
            │                 │                                  │
            ▼                 │                                  ▼
   cellular modem ◄───────────┴─────────── SOCKS5 over WiFi Direct ◄───────
```

### 3.1 Trust boundaries

1. **Cellular carrier ↔ sender phone.** Untrusted. The carrier can see, throttle, and modify all upstream TCP/UDP/DNS traffic. They cannot see TLS contents but can see SNI, IPs, packet sizes, timing. They can fingerprint the proxied traffic and may detect tethering through DPI or traffic-shaping heuristics.
2. **Sender phone ↔ receiver phone (over WiFi Direct).** Protected only by the WiFi Direct group's WPA2-PSK passphrase. The SOCKS5 layer on top has no authentication, so once a device is on the WiFi Direct group it can use the proxy freely. **No encryption or authentication is applied at the SOCKS5 layer.**
3. **Sender phone ↔ local network (local-only mode).** The SOCKS5 server binds to `0.0.0.0:8282`. Anyone on the same WiFi (and on the USB-tethered interface, if enabled) can reach it. **There is no authentication and no interface restriction.**
4. **App sandbox ↔ native library.** The native library runs in the app's UID. Any memory-corruption bug in `native.cpp` would compromise the entire app sandbox (which already has VPN + WiFi Direct + foreground-service permissions).

---

## 4. Findings

Findings are grouped by severity, using a CVSS-3.1-style mental model. Vector strings are approximate — the attacker position (same-WiFi vs. WiFi Direct member vs. carrier) varies per finding and is called out in each entry.

### 4.1 CRITICAL

#### C1 — SOCKS5 server has no authentication (open proxy)
- **Files:** `Socks5Server.kt` (both versions), method `handleClient`.
- **CWE-306 / CWE-287.**
- **Code:**
  ```kotlin
  val methods = ByteArray(nMethods); dis.readFully(methods)
  if (nMethods == 0 || !methods.contains(0)) {           // ← accepts ONLY method 0x00 (NO_AUTH)
      output.write(byteArrayOf(0x05, 0xFF.toByte())); output.flush()
      throw IOException("Client did not offer NO_AUTH")
  }
  output.write(byteArrayOf(0x05, 0x00)); output.flush()    // ← picks NO_AUTH
  ```
- **Impact:** Anyone who can reach port 8282 of the sender phone can route arbitrary TCP/UDP through the sender's cellular connection. Combined with C2 (`0.0.0.0` bind in local-only mode), this means a stranger on the same hotel/café/airport WiFi can use your phone as an open proxy to do illegal things through your mobile IP, burn your data cap, or impersonate you to services that whitelist by IP.
- **Exploit:** From a laptop on the same WiFi: `curl --socks5 <phone-ip>:8282 https://example.com`. No credentials required.
- **CVSS (rough):** 9.1 (AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:L) in local-only mode on shared WiFi.

#### C2 — Local-only SOCKS5 binds to `0.0.0.0` instead of a specific interface
- **Files:** `SenderService.kt`, function `onLocalReady()`:
  ```kotlin
  socks = Socks5Server(this, "0.0.0.0", Constants.SOCKS5_PORT) { ... }
  ```
  and `Socks5Server.start()`:
  ```kotlin
  serverSocket = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress("0.0.0.0", port)) }
  ```
- **Impact:** The SOCKS5 listener accepts connections on **every** interface of the sender phone, including:
  - the WiFi interface (when on a shared WiFi — your PC can reach it, but so can everyone else on the SSID);
  - the WiFi Direct interface (if WiFi Direct happens to be up — wide-open to the WiFi Direct group);
  - the USB tethering interface (RNDIS/NCM) — usually safe, but if the host PC is bridged or compromised, you've given it open SOCKS5;
  - and, depending on the kernel's binding semantics, **the cellular interface itself** — meaning a public IP (if your carrier assigns one) could expose the SOCKS5 to the entire Internet.
- **CVSS (rough):** 8.6 (AV:N/AC:L/PR:N/UI:N/S:C/C:H/I:H/A:L) on shared WiFi.

#### C3 — No transport encryption between receiver and sender (cleartext SOCKS5 + cleartext DNS)
- **Files:** `TcpFlow.kt`, `SharedUdpFlow.kt`, `Socks5Server.kt`.
- **Impact:** Every TCP and UDP payload between the receiver phone and the sender phone traverses the WiFi Direct link in plaintext. The only protection is the WiFi Direct group's WPA2-PSK passphrase, which:
  - is broadcast in cleartext in the foreground notification (`SenderService.kt` line 109 in v4.0: `"WiFi Direct: join \"$ssid\" with password $passphrase\n…"`);
  - is exposed to notification-listener services on the sender phone (any app granted `BIND_NOTIFICATION_LISTENER_SERVICE`);
  - is a single PSK shared between all group members, so a compromised peer can sniff every other peer's traffic.
- **Practical impact:** If your sender phone is also running untrusted apps with notification access, those apps learn the WiFi Direct password and can join the group, sniff your receiver's traffic, and passively observe DNS queries, HTTP URLs, app metadata.
- **Mitigation:** WPA2 on WiFi Direct is generally fine for personal use. If you can't trust peers on the sender phone, run HTTPS through the proxy at all times (which is the default for modern apps).

### 4.2 HIGH

#### H1 — Production keystore password hardcoded in `build.gradle.kts`
- **Files:** `app/build.gradle.kts` (both versions):
  ```kotlin
  signingConfigs {
      create("release") {
          storeFile = file("/home/z/my-project/keystore/datanet-release.jks")
          storePassword = "datanetshare2024"
          keyAlias = "datanet"
          keyPassword = "datanetshare2024"
      }
  }
  ```
- **Impact:** Anyone with the source archive (and the keystore file, if you ever share it) can sign an APK that Android will treat as an update for your installed app. A malicious update would inherit the same UID and every permission this app has — `INTERNET`, `ACCESS_FINE_LOCATION`, `BIND_VPN_SERVICE`, foreground-service types, `WAKE_LOCK`, etc. Combined with `allowBackup="true"` (H4), the keystore password being known makes it much easier for an attacker who already controls the device to push a backdoored version that preserves the user's data.
- **Fix:** Move the password to `~/.gradle/gradle.properties` or an environment variable, then `storePassword = System.getenv("DATANET_STORE_PASSWORD")`. **Rotate the keystore immediately** because the current password is in the source archive you distributed with this audit. The keystore itself is not in the zip (only the path is referenced), so the password alone is not enough to forge an update — but if you ever publish the keystore file, the app is fully compromised.
- **CVSS (rough):** 7.5 (AV:L/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:H) for someone with both the keystore and the password.

#### H2 — `android:usesCleartextTraffic="true"` in manifest
- **Files:** `AndroidManifest.xml` (both versions).
- **Impact:** Disables Android 9+'s default refusal to send cleartext HTTP. The app itself doesn't make HTTP requests (it only operates as a SOCKS5/TUN proxy), so this flag is harmless for the app's own traffic. **However**, the flag is **inherited by every proxied app's view of the network** in the sense that it weakens the user's mental model: when this flag is `true`, the user might assume "the app uses HTTPS for everything" — but they should not. The receiver phone's apps go through the sender's cellular unencryptedly at the IP layer; whether their app-layer traffic is HTTPS depends on the app, not on this flag.
- **Real risk:** Negligible direct impact, but it disables an Android safety net for free. Set to `false` (the default) or, better, ship a `network_security_config.xml` that pins your own backend (you have no backend, so just set `false`).

#### H3 — WiFi Direct SSID and passphrase exposed via foreground notification (BigTextStyle)
- **Files:** `SenderService.kt`, lines 109 and 109-equivalent in v3.5.
- **Code (v4.0):**
  ```kotlin
  val notif = NotificationCompat.Builder(this, Constants.CHANNEL_ID)
      .setContentTitle("Sharing mobile data via WiFi Direct")
      .setContentText("SSID: $ssid - PWD: $passphrase")
      .setStyle(NotificationCompat.BigTextStyle().bigText(
          "WiFi Direct: join \"$ssid\" with password $passphrase\nSOCKS5: …"))
      .setOngoing(true).build()
  ```
- **Impact:** Anyone with physical access to the unlocked sender phone, or any app the user has granted notification-listener permission to, sees the WiFi Direct password in cleartext. This is partly unavoidable (the user must read the password to share it), but the BigTextStyle makes it trivially shoulder-surfable, and the `setOngoing(true)` means the password stays in the notification shade for hours.
- **Fix:** Use `setVisibility(NotificationCompat.VISIBILITY_SECRET)` and `setContentText("Tap to view credentials")` with a content intent that opens an in-app dialog showing the password only when the user is authenticated (or just when the user taps). At minimum, set `VISIBILITY_PRIVATE` so the password is masked on the lock screen.

#### H4 — `android:allowBackup="true"`
- **Files:** `AndroidManifest.xml` (both versions).
- **Impact:** Allows `adb backup` to extract the app's `/data/data/com.datanet.share` directory on a device with USB debugging enabled. The app doesn't store credentials persistently (no SharedPreferences, no SQLite), so the immediate payload is low. However:
  - On rooted devices, the app's process memory at backup time could contain the active WiFi Direct SSID/passphrase, the SOCKS5 NAT tables, and a list of destinations currently being proxied.
  - On Android <12, `adb backup` works without authentication; on Android 12+ it requires the user to confirm a key.
- **Fix:** Set `android:allowBackup="false"`, or set `android:dataExtractionRules="@xml/data_extraction_rules"` and explicitly exclude nothing.

#### H5 — Hardcoded DNS server `8.8.8.8` (Google) with no fallback
- **Files:** `Constants.kt`:
  ```kotlin
  const val TUNNELED_DNS = "8.8.8.8"
  ```
  Consumed by `ReceiverVpnService.kt`:
  ```kotlin
  val builder = Builder().setSession("DataNet Share Receiver")
      .addAddress(Constants.VPN_ADDRESS, 24)
      .addDnsServer(Constants.TUNNELED_DNS)
      .setMtu(1500)
  ```
- **Impact:** Every DNS query from the receiver phone is sent to `8.8.8.8` over the sender's cellular network, unencrypted. The carrier can passively observe every hostname the receiver phone resolves (which leaks a lot of behavioral metadata: which apps are running, which websites are being visited, which push-notification servers are being polled). Google also sees the queries; this is documented Google behavior for `8.8.8.8`. There is no fallback if `8.8.8.8` is unreachable (e.g., on a carrier that blocks it).
- **Fix:** Use DNS-over-TLS (Android 9+ supports `addDnsServer` with a TLS endpoint) or at least `8.8.8.8` and `1.1.1.1` as primary/secondary. Better: let the user pick a DNS server in the UI.

#### H6 — VPN routes `0.0.0.0/1` + `128.0.0.0/1` (full-tunnel hijack)
- **Files:** `ReceiverVpnService.kt`, function `startTunnel()`:
  ```kotlin
  builder.addRoute("0.0.0.0", 1); builder.addRoute("128.0.0.0", 1)
  ```
- **Impact:** These two `/1` routes beat the kernel's default route on Android 11+, so the VPN captures **all** IPv4 traffic from the receiver phone — including its own cellular, its own WiFi (when WiFi Direct is also up), and any local network on the receiver side. This is by design (the receiver has no upstream of its own while the VPN is up), but:
  - When the sender phone dies, WiFi Direct drops, or the cellular uplink fails, the receiver phone has **no connectivity at all** until the user manually stops the VPN. There is no automatic fail-over.
  - All receiver-phone apps now appear to come from the sender's cellular IP. Apps that authenticate by IP (some banking apps, some corporate VPNs) will reject the receiver.
  - Local LAN resources on the receiver phone (printers, AirPlay, Chromecast) become unreachable while the VPN is up.
- **Severity rationale:** This is "HIGH" rather than "MEDIUM" only because it silently degrades the receiver's network posture; if the user is unaware, they may attribute the failures to other apps.
- **Fix:** Consider adding `builder.addRoute("192.168.0.0", 16)` exclusions for local LANs, or use `builder.addRoute("0.0.0.0", 0)` and `addDisallowedApplication(getPackageName())` to exclude the sender's own traffic (though that's irrelevant on the receiver).

#### H7 — Partial wake lock held for 10 hours, regardless of usage
- **Files:** `SenderService.kt` and `ReceiverVpnService.kt`:
  ```kotlin
  wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DataNetShare::Sender").apply {
      setReferenceCounted(false); acquire(10 * 60 * 60 * 1000L)   // ← 10 hours
  }
  ```
- **Impact:** If the user starts sharing and forgets about it, the CPU stays awake for 10 hours, draining the battery. More subtly, the wake lock silently releases after 10 hours, so a long-running share session will mysteriously die overnight — the user may switch to an insecure alternative (e.g., enabling the carrier's hotspot) thinking the app is broken.
- **Fix:** Acquire without a timeout (or with a much longer one, e.g., 24 hours) and rely on `stopForeground`/`stopSelf` to release the lock. Or implement a periodic keep-alive check that re-acquires the lock if the share is still active.

### 4.3 MEDIUM

#### M1 — `START_STICKY` on services can silently resurrect the VPN
- **Files:** `SenderService.kt`, `ReceiverVpnService.kt` both `return START_STICKY`.
- **Impact:** If the OS kills the service due to memory pressure, it will be restarted with a null intent. Because `onStartCommand` does not handle `intent == null`, the `when (intent?.action)` falls through to the default `START_STICKY` return — the service stays alive but does nothing. For `ReceiverVpnService`, this means the VPN can come back without an active SOCKS host, which leads to a non-functional full-tunnel VPN on the receiver (every connection attempt fails).
- **Fix:** Return `START_NOT_STICKY` for the receiver, so the OS doesn't restart it. For the sender, `START_STICKY` is more defensible (you want sharing to survive a memory-pressure kill), but the restart path should re-acquire the WiFi Direct group explicitly.

#### M2 — `forceStopAll()` calls `android.os.Process.killProcess(myPid())` without releasing resources
- **Files:** `MainActivity.kt`, function `forceStopAll()`:
  ```kotlin
  Handler(Looper.getMainLooper()).postDelayed({
      android.os.Process.killProcess(android.os.Process.myPid())
  }, 500)
  ```
- **Impact:** `killProcess` SIGKILLs the process without running `onDestroy`/`stopSharing`/`stopTunnel`. The wake lock is auto-released by the kernel on process death (Android's `PowerManager` tracks PID), and the `ParcelFileDescriptor` to the TUN is auto-closed. However:
  - The WiFi Direct group is **not** removed, so it stays up until Android times it out (~5 minutes).
  - Any in-flight SOCKS5 connections are TCP-RST'd by the kernel, which is fine for the receiver (apps retry) but bad for any long-lived upload in progress.
  - The foreground notification is removed by the system on process death.
- **Fix:** Don't `killProcess` — call `stopSharing()` and `stopForeground()` properly, then `finishAffinity()`.

#### M3 — No rate limiting / per-IP throttling on the SOCKS5 server
- **Files:** `Socks5Server.kt`, function `acceptLoop()`:
  ```kotlin
  while (running) {
      val client = srv.accept()
      scope.launch { handleClient(client) }     // ← unbounded coroutine spawn
  }
  ```
- **Impact:** A single attacker who can reach the SOCKS5 port can open thousands of TCP connections, each spawning a coroutine that holds a thread from `Dispatchers.IO`. The `soTimeout = 60000` on the client socket mitigates slowloris (idle sockets time out after 60s), but the rate of new connections is unlimited. The phone can run out of file descriptors (typical per-app limit: 1024) and become unresponsive.
- **Fix:** Add a `Semaphore` of, say, 64 concurrent accepts, and reject further connections with a brief delay. Also consider `Connection: close` after N requests per connection (though SOCKS5 is connection-per-session so this is moot).

#### M4 — STUN test sends 12 bytes of `java.util.Random` (non-cryptographic) as transaction ID
- **Files:** `UdpTest.kt`:
  ```kotlin
  val txnId = ByteArray(12); java.util.Random().nextBytes(txnId)
  ```
- **Impact:** STUN transaction IDs don't need to be cryptographically random, but using `java.util.Random` means an observer who sees one transaction ID can predict the next one. This is mostly theoretical (the STUN test is a manual diagnostic), but if you ever extended this code path to use the transaction ID as a CSRF token or similar, you'd have a vulnerability. Use `java.security.SecureRandom` for any future use.

#### M5 — `RECEIVE_BOOT_COMPLETED` permission declared but never used
- **Files:** `AndroidManifest.xml` (both versions).
- **Impact:** The manifest declares `android.permission.RECEIVE_BOOT_COMPLETED` but no `<receiver android:name="…BootReceiver">` is registered to handle `BOOT_COMPLETED`. This is a phantom permission that widens the app's surface unnecessarily.
- **Fix:** Remove the permission, or implement the boot receiver if you want sharing to survive a reboot.

#### M6 — `LocalBroadcastManager` is deprecated and leaked across configuration changes
- **Files:** `MainActivity.kt`, `SenderService.kt`, `ReceiverService.kt`, `ReceiverVpnService.kt`.
- **Impact:** `androidx.localbroadcastmanager` is officially deprecated (Google recommends `LiveData` or `Flow`). It also has a known issue where `sendBroadcast` synchronously delivers to all registered receivers, which can cause re-entrancy if a receiver calls `sendBroadcast` itself. The current code doesn't do this, but it's a footgun.
- **Fix:** Replace with `MutableStateFlow<State>` exposed from the service and collected in the activity.

#### M7 — VPN `EXTRA_SOCKS_HOST` defaults to `192.168.49.1` and is silently overridden
- **Files:** `ReceiverService.kt`:
  ```kotlin
  val host = intent.getStringExtra(EXTRA_SOCKS_HOST) ?: "192.168.49.1"
  ```
  And `MainActivity.kt` hardcodes the same IP. There is no UI to override it.
- **Impact:** If the WiFi Direct group owner IP ever changes (some Android versions assign `192.168.49.2` or `192.168.4.1` depending on the carrier and DHCP behavior), the receiver will silently fail to connect and the user won't know why. Not a security issue per se, but a usability issue that can drive the user toward insecure alternatives.
- **Fix:** Add a text field in the UI for the SOCKS host; default to `192.168.49.1` but allow override.

#### M8 — Native C `tcp_checksum` / `udp_checksum` access `pkt[12..19]` without bounds check
- **Files:** `app/src/main/cpp/native.cpp`, lines 66–69 and 89–92.
- **Code:**
  ```cpp
  static uint16_t tcp_checksum(uint8_t* pkt, int ip_hdr_len, int tcp_len) {
      ...
      sum += ((uint16_t)pkt[12] << 8) | pkt[13];   // src IP byte 12
      sum += ((uint16_t)pkt[14] << 8) | pkt[15];   // src IP byte 14
      sum += ((uint16_t)pkt[16] << 8) | pkt[17];   // dst IP byte 16
      sum += ((uint16_t)pkt[18] << 8) | pkt[19];   // dst IP byte 18
      ...
  }
  ```
- **Impact:** The JNI caller (`Java_..._computeTcpChecksum`) only checks `ipHdrLen + tcpLen <= len`, which means if `len < 20`, this function reads out of bounds. In JNI, an OOB read on a `jbyte` array typically causes a SIGSEGV (because the array's backing memory is bounded by the JVM). The result is a native crash that brings down the entire app process, which would also take down the VPN — a denial of service that an attacker who can call `computeTcpChecksum` with a crafted packet could trigger.
- **Current exploitability:** **Zero from outside the app sandbox.** The only callers are `TcpFlow.computeAndSetChecksums` and `SharedUdpFlow.buildUdpPacket`, both of which pass arrays they construct themselves (always at least 20 bytes for the IP header). The `handlePacket` function in `Tun2Socks.kt` rejects packets shorter than 20 bytes before any flow is created. So an external attacker would first need to find a way to pass a malformed packet to the checksum function — which they can't, because the TUN's packet parser filters it.
- **Fix:** Add `if (len < 20) return 0;` at the top of `tcp_checksum` and `udp_checksum`, and document the precondition.

#### M9 — `UdpAssociate.clientHost` is captured but never enforced
- **Files:** `Socks5Server.kt`, inner class `UdpAssociate`:
  ```kotlin
  inner class UdpAssociate(private val bindHost: String, private val clientHost: String) {
      ...
  }
  ```
- **Impact:** The SOCKS5 spec says the UDP relay should only accept packets from the client that opened the association (the `clientHost` passed in). The current code does **not** check the source address of incoming UDP relay packets — it accepts from anyone who can reach the relay port. Combined with C1 (no SOCKS5 auth) and C2 (bind to `0.0.0.0`), this means an attacker on the same WiFi can send UDP packets to the relay port and have them forwarded out the cellular interface.
- **Fix:** In `UdpAssociate.relayLoop`, filter `from.address.hostAddress == clientHost` before forwarding.

### 4.4 LOW

#### L1 — Logging exposes internal state to logcat
- **Files:** Multiple — 49 `Log.*` calls across both versions.
- **Impact:** Other apps with `READ_LOGS` permission (granted via ADB on rooted devices, or via system-level access) can read:
  - `"Initial cellular network found: $network"` (network object hash — benign);
  - `"UDP relayed $cnt packets to $dstHost:$dstPort"` — this **leaks the destination IPs and ports of proxied traffic** to anyone reading logcat. This is a privacy regression. v4.0 removes this particular `Log.i` call, but the v3.5 receiver still logs it.
  - `"SOCKS5 listening on 0.0.0.0:$port (all interfaces)"` — confirms the open-proxy posture.
- **Fix:** Strip `Log.i` of destination addresses entirely, or gate behind a debug flag.

#### L2 — `allowBackup="true"` exposes the (minimal) app data
- Already covered as H4. Listed here for completeness because the practical payload is small.

#### L3 — `isMinifyEnabled = false` in release build
- **Files:** `app/build.gradle.kts`.
- **Impact:** Release APK is trivially reversible to nearly-original Kotlin. For this app (open source), that's not a security issue. For a malicious variant based on this codebase, it would be easy to analyze — so this is actually a net positive for the user. No fix needed; mention only for completeness.

#### L4 — `startService` used for `ACTION_STOP` on `ReceiverVpnService` (background-start restriction)
- **Files:** `MainActivity.kt`:
  ```kotlin
  try { startService(Intent(this, ReceiverVpnService::class.java).apply { action = ReceiverVpnService.ACTION_STOP }) } catch (_: Exception) {}
  ```
- **Impact:** On Android 12+, `startService` from the background throws `BackgroundServiceStartNotAllowedException`. The try/catch swallows the exception, so STOP may silently fail when the app is backgrounded. The user's tap on "STOP" looks like it worked (the button resets), but the VPN stays up.
- **Fix:** Use `startForegroundService` for STOP too, or use an `IntentService`/`JobIntentService` pattern. Easiest: `ContextCompat.startForegroundService(this, intent)`.

#### L5 — `setReferenceCounted(false)` on wake lock with `acquire(timeout)` is unusual
- **Files:** Both services.
- **Impact:** `setReferenceCounted(false)` makes `acquire`/`release` not stack — calling `acquire` twice then `release` once releases the lock entirely. The current code only calls `acquire` once per service lifetime, so this is benign. Mention only because it's a footgun if the code is later refactored.

#### L6 — `forceStopAll()` and `stopAll()` (v3.5 only) differ in behavior
- **Files:** `MainActivity.kt` v3.5 has both `stopAll()` (which dispatches STOP intents and resets UI but doesn't kill the process) and `forceStopAll()` (which dispatches STOP intents and then `killProcess`). v4.0 removed `stopAll()`.
- **Impact:** No security impact. v3.5's `stopAll()` was unused dead code anyway (no button wired to it). Mention for completeness.

#### L7 — `VpnService.prepare(this)` is called twice in v3.5
- **Files:** `MainActivity.kt` v3.5 line 153: `if (VpnService.prepare(this) != null) vpnPermissionLauncher.launch(VpnService.prepare(this)!!) else startReceiverService()`.
- **Impact:** Two `prepare()` calls per tap. The second one is the one that's actually used. v4.0 fixes this to a single call. No security impact, just a minor efficiency fix.

### 4.5 INFORMATIONAL

#### I1 — The app's stated purpose is to bypass carrier tethering detection
This is a legal/business risk, not a technical one. Carriers reserve the right to:
- Throttle the user's connection
- Charge tethering fees retroactively
- Terminate the user's account
- Send legal threats

Some carriers (notably T-Mobile US and several MVNOs) actively fingerprint tethered traffic by TTL, packet size distributions, and DPI signatures. The app does not normalize TTL (the TUN-to-SOCKS path leaves the kernel TTL default, which on Android is 64 — same as native, so this particular fingerprint is fine). But the carrier can still detect the traffic shape: a phone streaming YouTube looks different from a laptop streaming YouTube through a phone.

#### I2 — Receiver phone's apps all appear to come from the sender's cellular IP
Apps that authenticate by source IP (some banking apps, some corporate VPNs, some media apps with regional licensing) will treat the receiver phone as if it's on the sender's cellular network. This may trigger anti-fraud systems (the receiver was last seen on WiFi at home, suddenly appears on cellular from a different city). Be aware that some apps may freeze the account.

#### I3 — `WifiP2pDnsSdServiceInfo` publishes a `_datanetshare._tcp` Bonjour service
- **Files:** `WifiDirectHost.kt`:
  ```kotlin
  val info = WifiP2pDnsSdServiceInfo.newInstance("_datanetshare", "_tcp",
      mapOf("port" to Constants.SOCKS5_PORT.toString()))
  ```
- **Impact:** Other devices on the WiFi Direct group can discover this service via mDNS/Bonjour. This is the intended behavior (so that future versions could auto-discover the sender), but it does leak the existence of the app to anyone scanning the local network.

#### I4 — No certificate pinning or DNS-over-HTTPS for the proxied traffic
The app does not (and cannot) enforce HTTPS for the proxied apps — that's up to each app. The app also doesn't run a DNS-over-HTTPS resolver. If the user wants stronger privacy, they should configure the receiver phone's apps to use a DoH resolver (e.g., Cloudflare's 1.1.1.1 DoH app, or Intra).

#### I5 — Bundled APKs in the source repo
- **Files:** `releases/DataNetShare-v3.5-release.apk` and `releases/DataNetShare-v4.0-release.apk`.
- **Impact:** These APKs are signed with the production signing key (per `build.gradle.kts`). If the source archive is leaked, the APKs can be redistributed under the legitimate package name `com.datanet.share`. A user who installs a redistributed APK from an untrusted source would get the genuine, signed app — but they would also miss security updates and could be socially engineered into installing a backdoored fork later (which would have a different signature, but most users don't check signatures).
- **Fix:** Consider hosting APKs on a signed release channel (GitHub Releases, F-Droid) and removing them from the source repo.

---

## 5. Diff Analysis: v3.5 → v4.0

### 5.1 Security-relevant changes

| Aspect | v3.5 | v4.0 | Security impact |
|---|---|---|---|
| Native library | None | `libdatanet_native.so` (C++) for checksums + TUN I/O | Adds JNI attack surface. Code reviewed, no exploitable issues (see M8). |
| Socket buffer sizes | 256–512 KB | 4 MB | None. |
| TCP window scaling | None | Advertises 65535 << 7 = 8 MB (RFC 7323) | None. |
| `Log.i` in CellularNetworkTracker | 4 verbose calls | All stripped | Slight privacy improvement. |
| `Log.i` in SharedUdpFlow relay | `"UDP sent $cnt to relay (last: ${dstHost}:${dstPort})"` | Removed | Privacy improvement — destination addresses no longer logged. |
| `MainActivity.stopAll()` | Present (unused) | Removed | None. |
| `VpnService.prepare(this)` double call | Yes (line 153) | Fixed to single call | None, minor efficiency fix. |
| Manifest | Identical | Identical | None. |
| Permissions | Identical | Identical | None. |
| Build config | Same keystore path/password | Same keystore path/password | Both versions have H1. |
| `usesCleartextTraffic` | `true` | `true` | Both versions have H2. |
| `allowBackup` | `true` | `true` | Both versions have H4. |
| ProGuard | `isMinifyEnabled = false` | `isMinifyEnabled = false` | Both versions have L3. |

### 5.2 Why v4.0 on both ends is slower (user's note)

The user observed that running v4.0 on both ends slows speeds drastically. The likely cause is **not** the native code itself (which is faster), but the **larger socket buffers and TCP window scaling interacting badly with WiFi Direct's limited bandwidth on the receiver side**: when the receiver advertises an 8 MB TCP window and 4 MB socket buffers, the sender (v4.0) will eagerly fill that window. WiFi Direct's actual throughput (per the README, ~400 Mbps = ~50 MB/s) over a high-latency link means the BDP can be larger than the receiver's drain rate, causing bufferbloat and head-of-line blocking. v3.5 on the receiver, with smaller buffers (256–512 KB) and no window scaling, throttles itself to a slower steady-state that happens to match WiFi Direct's sweet spot.

This is a **performance** observation, not a security one — but it does mean the user is incentivized to keep running v3.5 on the receiver, which means the v3.5 privacy regressions (L1: logging destination IPs) persist on the receiver.

### 5.3 Recommended version-policy

- Continue running v3.5 on the receiver if speed is critical, but **patch Socks5Server.kt and Tun2Socks.kt on v3.5** to remove the `Log.i` calls that leak destination addresses. This is a 5-line change.
- Or, ship a v4.0.1 receiver build with smaller socket buffers (matching v3.5's 256 KB) and window scaling disabled. This restores v3.5's throttling behavior while keeping the v4.0 native code (which doesn't help much on the receiver anyway, since the receiver doesn't compute checksums at high rate).

---

## 6. Recommendations (prioritized)

### 6.1 Do this before next use

1. **Rotate the keystore password.** The current password (`datanetshare2024`) is in the source archive you distributed. Generate a new keystore and re-sign both APKs. (Closes H1.)
2. **In local-only mode, bind the SOCKS5 server to the specific USB/WiFi interface IP, not `0.0.0.0`.** Pick the IP from `InterfaceEnumerator.getAllInterfaces()` matching the user's selected interface. (Closes C2.)
3. **Add SOCKS5 username/password authentication (RFC 1929).** Generate a per-session random password when the sender starts, display it in the UI, and require the receiver to enter it. This is a 50-line change to `Socks5Server.handleClient`. (Closes C1.)
4. **Set `android:usesCleartextTraffic="false"` and `android:allowBackup="false"` in the manifest.** (Closes H2, H4.)

### 6.2 Do this in the next release

5. **Move the WiFi Direct password out of the foreground notification's BigTextStyle.** Use `VISIBILITY_SECRET` and a content intent that opens an in-app dialog. (Closes H3.)
6. **Set `android:permission="android.permission.BIND_VPN_SERVICE"` is already correct.** Also set `android:exported="false"` on all services (already correct). No change needed.
7. **Remove `RECEIVE_BOOT_COMPLETED` from the manifest** since no boot receiver is registered. (Closes M5.)
8. **Replace `LocalBroadcastManager` with `MutableStateFlow`.** (Closes M6, and reduces APK size.)
9. **In `Socks5Server.UdpAssociate.relayLoop`, filter UDP relay packets by `clientHost`.** (Closes M9.)
10. **In `native.cpp`, add `if (len < 20) return 0;` to `tcp_checksum` and `udp_checksum`.** (Closes M8.)
11. **Add a `Semaphore(64)` in `Socks5Server.acceptLoop` to limit concurrent connections.** (Closes M3.)
12. **Return `START_NOT_STICKY` from `ReceiverVpnService`** so the OS doesn't resurrect the VPN. (Closes M1.)

### 6.3 Nice to have

13. Let the user configure the DNS server (default `8.8.8.8`, but allow `1.1.1.1`, `9.9.9.9`, or a custom DoH endpoint). (Closes H5.)
14. Add an "auto-stop after N minutes of no traffic" option to the sender, so forgotten sessions don't burn battery. (Closes H7.)
15. Add LAN route exclusions to the VPN builder (`builder.addRoute("192.168.0.0", 16)` won't help because routes are additive — but `builder.addRoute("192.168.0.0", 24)` with a higher-priority `addRoute` to the VPN's own subnet could work). Or document the limitation in the UI. (Closes H6 partially.)
16. Strip `Log.i` of destination IPs entirely in v3.5's `SharedUdpFlow` (already done in v4.0). (Closes L1.)
17. Use `ContextCompat.startForegroundService` for STOP actions on `ReceiverVpnService`. (Closes L4.)
18. Remove the bundled APKs from the source repo; host them on GitHub Releases. (Closes I5.)

### 6.4 Operational recommendations (non-code)

19. **Don't use this on a shared WiFi (café, hotel, airport) in local-only mode.** The SOCKS5 server is reachable by everyone on the SSID. If you must, set up a firewall on the sender phone (e.g., via AFWall+) to drop inbound connections on port 8282 from non-trusted IPs.
20. **Trust the sender phone.** If the sender is compromised (malware, malicious app with notification access), the malware can see and modify all of the receiver's traffic. Don't run untrusted apps on the sender.
21. **Use HTTPS in proxied apps.** The WiFi Direct link is encrypted at WPA2, but the carrier can see SNI and packet metadata. HTTPS at the app layer is the only thing that protects you from carrier MITM.
22. **Be aware of the legal risk.** Bypassing tethering detection violates the terms of service of most carriers. If detected, the carrier may throttle, charge, or terminate your account.
23. **Periodically check for orphaned VPNs.** If the receiver phone ever feels "slow" or "weird," check Android Settings → Network → VPN. The DataNet Share VPN may be stuck on after the sender died.

---

## 7. Test Plan (for the user to verify fixes)

If you implement the recommendations above, verify with these tests:

1. **Open-proxy test (after fix #1–#3):** From a laptop on the same WiFi as the sender (in local-only mode), run `curl --socks5 <phone-ip>:8282 https://example.com`. It should fail with "authentication required." Then `curl --socks5-user wronguser:wrongpass --socks5 <phone-ip>:8282 https://example.com` should also fail. Then `curl --socks5-user <correct-user>:<correct-pass> --socks5 <phone-ip>:8282 https://example.com` should succeed.
2. **Interface-bind test (after fix #2):** Run `netstat -tlnp | grep 8282` (or `ss -tlnp`) on the sender phone (via `adb shell`). The listener should be on a single IP, not `0.0.0.0`.
3. **Notification-leak test (after fix #5):** With the sender running, lock the screen. The notification should not show the password. Tap the notification → app should open and prompt for biometric/unlock before showing the password.
4. **Backup-extraction test (after fix #4):** `adb backup -f datanet.ab com.datanet.share` should fail with "Permission denied / Backup not allowed."
5. **Native bounds test (after fix #10):** Hand-craft a 10-byte IP packet, pass it to `NativeHelper.computeTcpChecksum` via a test, and verify it returns 0 (not crash).
6. **STICKY test (after fix #12):** Force-kill the receiver service via `adb shell am force-stop com.datanet.share`. The VPN should not come back. (Before the fix, it would resurrect with a null intent.)

---

## 8. Conclusion

The codebase is well-organized and the v4.0 native C code is benign. The architecture is sound for the user's stated use case (sharing mobile data between two personal phones over WiFi Direct). However, the **local-only mode** (used for PC/Mac sharing) is dangerously exposed: anyone on the same WiFi can use your phone as an open proxy, with no authentication and no interface restriction. This is the single most important issue to fix.

The signing keystore password leak (H1) is the second most important issue — not because it's exploitable in isolation (the keystore file is not in the source), but because it normalizes bad practice and any future leak of the keystore file would immediately compromise all installed copies of the app.

The v3.5 vs v4.0 split is acceptable from a security standpoint: the only security-relevant difference is that v3.5 logs proxied destination IPs to logcat, while v4.0 doesn't. If you continue running v3.5 on the receiver, patch those `Log.i` lines (5 lines, see §5.2).

No remote-code-execution or privilege-escalation vulnerabilities were found. No malware behavior was found. The app does what it claims and nothing more. The carrier-bypass use case is, by design, a violation of most carriers' terms of service — but that is a legal/business risk for the user, not a technical defect of the app.

**Audit complete.**
