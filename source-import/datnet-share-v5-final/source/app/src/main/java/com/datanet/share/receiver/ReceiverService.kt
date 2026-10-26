package com.datanet.share.receiver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.datanet.share.common.Constants

class ReceiverService : Service() {
    companion object {
        private const val TAG = "ReceiverService"
        const val ACTION_START = "com.datanet.share.receiver.START"
        const val ACTION_STOP = "com.datanet.share.receiver.STOP"
        const val EXTRA_SOCKS_HOST = "socks_host"
        const val EXTRA_SOCKS_PORT = "socks_port"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                startForeground(Constants.RECEIVER_NOTIFICATION_ID, buildNotification("Connecting to sender...", ""))
                val host = intent.getStringExtra(EXTRA_SOCKS_HOST) ?: "192.168.49.1"
                val port = intent.getIntExtra(EXTRA_SOCKS_PORT, Constants.SOCKS5_PORT)
                startVpn(host, port)
            }
            ACTION_STOP -> { stopVpn(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        }
        return START_STICKY
    }

    private fun startVpn(host: String, port: Int) {
        try {
            if (VpnService.prepare(this) != null) {
                LocalBroadcastManager.getInstance(this).sendBroadcast(Intent(Constants.ACTION_RECEIVER_STATE).apply { putExtra(Constants.EXTRA_STATUS, "needs_permission") })
                stopSelf(); return
            }
            val intent = Intent(this, ReceiverVpnService::class.java).apply { action = ReceiverVpnService.ACTION_START; putExtra(ReceiverVpnService.EXTRA_SOCKS_HOST, host); putExtra(ReceiverVpnService.EXTRA_SOCKS_PORT, port) }
            try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent) } catch (e: Throwable) { try { startService(intent) } catch (_: Throwable) {} }
        } catch (t: Throwable) { stopSelf() }
    }

    private fun stopVpn() { startService(Intent(this, ReceiverVpnService::class.java).apply { action = ReceiverVpnService.ACTION_STOP }); stopForeground(STOP_FOREGROUND_REMOVE) }

    private fun buildNotification(title: String, text: String): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) { val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager; nm.createNotificationChannel(NotificationChannel(Constants.CHANNEL_ID, "DataNet Share", NotificationManager.IMPORTANCE_LOW)) }
        return NotificationCompat.Builder(this, Constants.CHANNEL_ID).setContentTitle(title).setContentText(text).setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true).build()
    }
}
