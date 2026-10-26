package com.datanet.share.receiver

import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.datanet.share.common.Constants

class ReceiverVpnService : VpnService() {
    companion object {
        private const val TAG = "ReceiverVpnService"
        const val ACTION_START = "com.datanet.share.receiver.VPN_START"
        const val ACTION_STOP = "com.datanet.share.receiver.VPN_STOP"
        const val ACTION_QUERY_STATE = "com.datanet.share.receiver.QUERY_STATE"
        const val EXTRA_SOCKS_HOST = "socks_host"
        const val EXTRA_SOCKS_PORT = "socks_port"
    }

    private var pfd: ParcelFileDescriptor? = null
    private var tun2socks: Tun2Socks? = null
    @Volatile private var running = false
    @Volatile private var foregroundStarted = false
    private var wakeLock: PowerManager.WakeLock? = null

    // Cached state for re-broadcasting when the activity reconnects (QUERY_STATE).
    @Volatile private var lastStatus: String = "stopped"
    @Volatile private var lastDetails: String = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        try {
            tryStartForeground()
            when (action) {
                ACTION_START -> { val host = intent.getStringExtra(EXTRA_SOCKS_HOST) ?: "192.168.49.1"; val port = intent.getIntExtra(EXTRA_SOCKS_PORT, Constants.SOCKS5_PORT); startTunnel(host, port) }
                ACTION_STOP -> { stopTunnel(); stopSelf(); return START_NOT_STICKY }
                ACTION_QUERY_STATE -> { rebroadcastState() }
            }
        } catch (t: Throwable) {
            broadcast("failed", "ReceiverVpnService crash: ${t.javaClass.simpleName}: ${t.message}")
            try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
            stopSelf(); return START_NOT_STICKY
        }
        // START_NOT_STICKY: if the OS kills the service under memory pressure,
        // do not resurrect it with a null intent. A null-intent resurrection would
        // leave the VPN up but pointing at no SOCKS server, breaking all connectivity.
        return START_NOT_STICKY
    }

    private fun tryStartForeground() {
        if (foregroundStarted) return
        try { ServiceCompat.startForeground(this, Constants.RECEIVER_NOTIFICATION_ID, buildNotification("DataNet Share Receiver", "VPN tunnel active"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE); foregroundStarted = true } catch (e: Throwable) {
            try { startForeground(Constants.RECEIVER_NOTIFICATION_ID, buildNotification("DataNet Share Receiver", "VPN tunnel active")); foregroundStarted = true } catch (_: Throwable) {}
        }
    }

    private fun startTunnel(socksHost: String, socksPort: Int) {
        if (running) return
        acquireWakeLock()
        val builder = Builder().setSession("DataNet Share Receiver").addAddress(Constants.VPN_ADDRESS, 24).addDnsServer(Constants.TUNNELED_DNS).setMtu(1500)
        builder.addRoute("0.0.0.0", 1); builder.addRoute("128.0.0.0", 1)
        try { pfd = builder.establish() } catch (e: Throwable) { broadcast("failed", "VPN establish failed: ${e.javaClass.simpleName}: ${e.message}"); stopSelf(); return }
        if (pfd == null) { broadcast("failed", "VPN permission denied"); stopSelf(); return }
        running = true
        try {
            tun2socks = Tun2Socks(pfd!!.fileDescriptor, socksHost, socksPort, { s -> protect(s) }, { d -> protect(d) }) { active, rx, tx -> broadcast("stats", "active=$active rx=${rx/1024}KB tx=${tx/1024}KB") }
            tun2socks?.start()
            broadcast("active", "Tunneling via $socksHost:$socksPort")
        } catch (t: Throwable) {
            broadcast("failed", "Tun2Socks start: ${t.javaClass.simpleName}: ${t.message}")
            stopTunnel(); stopSelf()
        }
    }

    private fun stopTunnel() {
        running = false
        try { tun2socks?.stop() } catch (_: Throwable) {}
        tun2socks = null
        try { pfd?.close() } catch (_: Throwable) {}
        pfd = null
        releaseWakeLock()
        try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {}
        try { (getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager).cancel(Constants.RECEIVER_NOTIFICATION_ID) } catch (_: Throwable) {}
        broadcast("stopped", "Tunnel stopped")
    }

    // No wake lock timeout — relies on stopTunnel/stopSelf to release.
    // Previous 10-hour timeout caused silent session death after 10h.
    private fun acquireWakeLock() { if (wakeLock == null) { try { val pm = getSystemService(Context.POWER_SERVICE) as PowerManager; wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DataNetShare::Receiver").apply { acquire() } } catch (_: Throwable) {} } }
    private fun releaseWakeLock() { try { wakeLock?.release() } catch (_: Throwable) {}; wakeLock = null }

    private fun rebroadcastState() {
        try { LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(Constants.ACTION_RECEIVER_STATE).apply { putExtra(Constants.EXTRA_STATUS, lastStatus); putExtra(Constants.EXTRA_DETAILS, lastDetails) }) } catch (_: Throwable) {}
    }

    private fun broadcast(status: String, details: String) {
        lastStatus = status; lastDetails = details
        try { LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(Constants.ACTION_RECEIVER_STATE).apply { putExtra(Constants.EXTRA_STATUS, status); putExtra(Constants.EXTRA_DETAILS, details) }) } catch (_: Throwable) {}
    }

    private fun buildNotification(title: String, text: String): android.app.Notification {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) { val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager; nm.createNotificationChannel(android.app.NotificationChannel(Constants.CHANNEL_ID, "DataNet Share", android.app.NotificationManager.IMPORTANCE_LOW)) }
        return NotificationCompat.Builder(this, Constants.CHANNEL_ID).setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
    }

    override fun onDestroy() { super.onDestroy(); stopTunnel(); try { stopForeground(STOP_FOREGROUND_REMOVE) } catch (_: Throwable) {} }
    override fun onRevoke() { stopTunnel(); stopSelf() }
}
