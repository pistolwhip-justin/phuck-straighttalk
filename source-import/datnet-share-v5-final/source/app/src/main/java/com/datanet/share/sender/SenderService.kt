package com.datanet.share.sender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.datanet.share.common.CellularNetworkTracker
import com.datanet.share.common.Constants
import com.datanet.share.common.FormatBytes
import com.datanet.share.common.InterfaceEnumerator

class SenderService : Service() {
    companion object {
        private const val TAG = "SenderService"
        const val ACTION_START = "com.datanet.share.sender.START"
        const val ACTION_START_LOCAL = "com.datanet.share.sender.START_LOCAL"
        const val ACTION_STOP = "com.datanet.share.sender.STOP"
        const val ACTION_QUERY_STATE = "com.datanet.share.sender.QUERY_STATE"
    }

    private var wifiHost: WifiDirectHost? = null
    private var socks: Socks5Server? = null
    private var speedTest: SpeedTestServer? = null
    private var stateObserver: androidx.lifecycle.Observer<WifiDirectHost.State>? = null
    private var wakeLock: PowerManager.WakeLock? = null

    // Cached state for re-broadcasting when the activity reconnects (QUERY_STATE).
    @Volatile private var lastStatus: String = "stopped"
    @Volatile private var lastDetails: String = ""
    @Volatile private var lastProxyIp: String? = null
    @Volatile private var lastProxyPort: Int? = null
    @Volatile private var lastSsid: String? = null
    @Volatile private var lastPassphrase: String? = null
    @Volatile private var lastInterfaces: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_START -> startSharing(useWifiDirect = true)
                ACTION_START_LOCAL -> startSharing(useWifiDirect = false)
                ACTION_STOP -> { stopSharing(); stopSelf(); return START_NOT_STICKY }
                ACTION_QUERY_STATE -> { rebroadcastState() }
            }
        } catch (t: Throwable) {
            broadcast("failed", "SenderService crash: ${t.javaClass.simpleName}: ${t.message}")
            stopSharing(); stopSelf(); return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun rebroadcastState() {
        try {
            val intent = Intent(Constants.ACTION_SENDER_STATE).apply {
                putExtra(Constants.EXTRA_STATUS, lastStatus)
                putExtra(Constants.EXTRA_DETAILS, lastDetails)
                lastProxyIp?.let { putExtra(Constants.EXTRA_PROXY_IP, it) }
                lastProxyPort?.let { putExtra(Constants.EXTRA_PROXY_PORT, it) }
                lastSsid?.let { putExtra(Constants.EXTRA_SSID, it) }
                lastPassphrase?.let { putExtra(Constants.EXTRA_PASSPHRASE, it) }
                lastInterfaces?.let { putExtra(Constants.EXTRA_INTERFACES, it) }
            }
            LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
        } catch (_: Throwable) {}
    }

    private fun acquireLocks() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            // No timeout — relies on stopForeground/stopSelf to release the lock.
            // The previous 10-hour timeout caused silent session death after 10h.
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DataNetShare::Sender").apply { acquire() }
        }
    }

    private fun releaseLocks() { try { wakeLock?.release() } catch (_: Exception) {}; wakeLock = null }

    private fun startSharing(useWifiDirect: Boolean) {
        try { ServiceCompat.startForeground(this, Constants.SENDER_NOTIFICATION_ID, buildNotification("Starting sharing...", if (useWifiDirect) "Negotiating WiFi Direct group" else "Local-only mode"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE) } catch (_: Throwable) { try { startForeground(Constants.SENDER_NOTIFICATION_ID, buildNotification("Starting...", "")) } catch (_: Throwable) {} }
        acquireLocks()
        broadcast("starting", if (useWifiDirect) "Acquiring WiFi Direct group..." else "Starting local-only SOCKS5 proxy...")
        if (useWifiDirect) {
            if (wifiHost == null) wifiHost = WifiDirectHost(this)
            stateObserver?.let { wifiHost?.state?.removeObserver(it) }
            val observer = androidx.lifecycle.Observer<WifiDirectHost.State> { state ->
                when (state) {
                    is WifiDirectHost.State.Idle -> {}
                    is WifiDirectHost.State.Initializing -> broadcast("starting", "Initializing WiFi Direct (attempt ${state.attempt}/${state.maxAttempts})...")
                    is WifiDirectHost.State.CreatingGroup -> broadcast("starting", "Creating WiFi Direct group (attempt ${state.attempt}/${state.maxAttempts})...")
                    is WifiDirectHost.State.Retrying -> broadcast("starting", "Attempt ${state.attempt}/${state.maxAttempts} failed (${state.reason}); retrying in ${state.nextDelayMs/1000}s...")
                    is WifiDirectHost.State.GroupReady -> onGroupReady(state.groupOwnerIp, state.ssid, state.passphrase)
                    is WifiDirectHost.State.Failed -> { val detail = if (state.hint.isNotBlank()) "${state.reason}\n\n${state.hint}" else state.reason; broadcast("failed", detail) }
                }
            }
            stateObserver = observer
            wifiHost?.state?.observeForever(observer)
            wifiHost?.start()
        } else { onLocalReady() }
    }

    private fun onLocalReady() {
        if (socks != null) return
        socks = Socks5Server(this, "0.0.0.0", Constants.SOCKS5_PORT) { clients, rx, tx ->
            val cellular = if (CellularNetworkTracker.cellularAvailable()) "mobile data" else "no cellular!"
            updateNotification("Sharing mobile data (local)", "$clients client(s) - ${FormatBytes.formatRxTx(rx, tx)} - $cellular")
        }
        speedTest = SpeedTestServer("0.0.0.0").also { it.start() }
        try {
            socks?.start(); Thread.sleep(500)
            val cellular = if (CellularNetworkTracker.cellularAvailable()) "mobile data (${CellularNetworkTracker.describeNetwork()})" else "NO CELLULAR"
            val interfaces = InterfaceEnumerator.getSummary()
            broadcast("active", "LOCAL-ONLY MODE (no WiFi Direct)\n\nSet SOCKS5 proxy to one of the IPs below, port ${Constants.SOCKS5_PORT}.\n\nUpstream: $cellular", proxyIp = "0.0.0.0", proxyPort = Constants.SOCKS5_PORT, interfaces = interfaces)
            val notif = NotificationCompat.Builder(this, Constants.CHANNEL_ID).setContentTitle("Sharing mobile data (local)").setContentText("SOCKS5 on 0.0.0.0:${Constants.SOCKS5_PORT}").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setStyle(NotificationCompat.BigTextStyle().bigText("Local-only mode (no WiFi Direct).\nSOCKS5: 0.0.0.0:${Constants.SOCKS5_PORT}\nUpstream: $cellular")).build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(Constants.SENDER_NOTIFICATION_ID, notif)
        } catch (e: Exception) { broadcast("failed", "SOCKS5 start: ${e.message}") }
    }

    private fun onGroupReady(groupOwnerIp: String, ssid: String, passphrase: String) {
        if (socks != null) return
        socks = Socks5Server(this, groupOwnerIp, Constants.SOCKS5_PORT) { clients, rx, tx ->
            val cellular = if (CellularNetworkTracker.cellularAvailable()) "mobile data" else "no cellular!"
            updateNotification("Sharing mobile data via WiFi Direct", "$clients client(s) - ${FormatBytes.formatRxTx(rx, tx)} - $cellular")
        }
        speedTest = SpeedTestServer(groupOwnerIp).also { it.start() }
        try {
            socks?.start(); Thread.sleep(500)
            val cellular = if (CellularNetworkTracker.cellularAvailable()) "mobile data (${CellularNetworkTracker.describeNetwork()})" else "NO CELLULAR"
            val interfaces = InterfaceEnumerator.getSummary()
            broadcast("active", "SSID: $ssid\nPassword: $passphrase\n\nFor phone receiver: install app and tap START RECEIVING\n\nFor PC/Mac: SOCKS5 to $groupOwnerIp:${Constants.SOCKS5_PORT}\n\nUpstream: $cellular", proxyIp = groupOwnerIp, proxyPort = Constants.SOCKS5_PORT, ssid = ssid, passphrase = passphrase, interfaces = interfaces)
            // VISIBILITY_PRIVATE: hides the WiFi Direct password from the lock screen.
            // User must unlock the phone to see the SSID/passphrase.
            val notif = NotificationCompat.Builder(this, Constants.CHANNEL_ID).setContentTitle("Sharing mobile data via WiFi Direct").setContentText("SSID: $ssid - tap to view password").setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setStyle(NotificationCompat.BigTextStyle().bigText("WiFi Direct: join \"$ssid\" with password $passphrase\nSOCKS5: $groupOwnerIp:${Constants.SOCKS5_PORT}\nUpstream: $cellular")).build()
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(Constants.SENDER_NOTIFICATION_ID, notif)
        } catch (e: Exception) { broadcast("failed", "SOCKS5 start: ${e.message}") }
    }

    private fun stopSharing() {
        stateObserver?.let { wifiHost?.state?.removeObserver(it) }; stateObserver = null
        try { socks?.stop() } catch (_: Exception) {}; socks = null
        try { speedTest?.stop() } catch (_: Exception) {}; speedTest = null
        try { wifiHost?.stop() } catch (_: Exception) {}; wifiHost = null
        releaseLocks()
        try { CellularNetworkTracker.stop() } catch (_: Exception) {}
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).cancel(Constants.SENDER_NOTIFICATION_ID) } catch (_: Exception) {}
        broadcast("stopped", "Sharing stopped")
    }

    private fun buildNotification(title: String, text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.createNotificationChannel(NotificationChannel(Constants.CHANNEL_ID, "DataNet Share", NotificationManager.IMPORTANCE_LOW)) }
        return NotificationCompat.Builder(this, Constants.CHANNEL_ID).setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.stat_sys_data_bluetooth).setOngoing(true).build()
    }

    private fun updateNotification(title: String, text: String) { try { (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(Constants.SENDER_NOTIFICATION_ID, buildNotification(title, text)) } catch (_: Exception) {} }

    private fun broadcast(status: String, details: String, proxyIp: String? = null, proxyPort: Int? = null, ssid: String? = null, passphrase: String? = null, interfaces: String? = null) {
        // Cache state so QUERY_STATE can re-broadcast it.
        lastStatus = status; lastDetails = details
        lastProxyIp = proxyIp; lastProxyPort = proxyPort
        lastSsid = ssid; lastPassphrase = passphrase
        lastInterfaces = interfaces
        val intent = Intent(Constants.ACTION_SENDER_STATE).apply { putExtra(Constants.EXTRA_STATUS, status); putExtra(Constants.EXTRA_DETAILS, details); proxyIp?.let { putExtra(Constants.EXTRA_PROXY_IP, it) }; proxyPort?.let { putExtra(Constants.EXTRA_PROXY_PORT, it) }; ssid?.let { putExtra(Constants.EXTRA_SSID, it) }; passphrase?.let { putExtra(Constants.EXTRA_PASSPHRASE, it) }; interfaces?.let { putExtra(Constants.EXTRA_INTERFACES, it) } }
        LocalBroadcastManager.getInstance(this).sendBroadcast(intent)
    }

    override fun onDestroy() { super.onDestroy(); stopSharing() }
}
