package com.datanet.share.sender

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import com.datanet.share.common.Constants

class WifiDirectHost(private val context: Context) {
    companion object {
        private const val TAG = "WifiDirectHost"
        private const val MAX_ATTEMPTS = 4
        private val BACKOFF_MS = longArrayOf(1000, 2000, 4000, 8000)
    }

    private val manager: WifiP2pManager? = context.getSystemService(Context.WIFI_P2P_SERVICE) as? WifiP2pManager
    private val wifiManager: WifiManager? = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var channel: WifiP2pManager.Channel? = null
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var attempt = 0

    private val _state = MutableLiveData<State>(State.Idle)
    val state: LiveData<State> = _state

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION -> {
                    val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(WifiP2pManager.EXTRA_WIFI_P2P_INFO, WifiP2pInfo::class.java)
                    } else { @Suppress("DEPRECATION") intent.getParcelableExtra<WifiP2pInfo>(WifiP2pManager.EXTRA_WIFI_P2P_INFO) }
                    onConnectionInfo(info)
                }
                WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION -> {
                    val state = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE, WifiP2pManager.WIFI_P2P_STATE_DISABLED)
                    if (state != WifiP2pManager.WIFI_P2P_STATE_ENABLED && running) {
                        _state.postValue(State.Failed("WiFi Direct is disabled. Enable it in Android Settings."))
                    }
                }
            }
        }
    }

    sealed class State {
        object Idle : State()
        data class Initializing(val attempt: Int, val maxAttempts: Int) : State()
        data class CreatingGroup(val attempt: Int, val maxAttempts: Int) : State()
        data class Retrying(val attempt: Int, val maxAttempts: Int, val nextDelayMs: Long, val reason: String) : State()
        data class GroupReady(val groupOwnerIp: String, val ssid: String, val passphrase: String) : State()
        data class Failed(val reason: String, val hint: String = "") : State()
    }

    fun start() {
        if (running) return
        running = true; attempt = 0
        val mgr = manager ?: run { _state.postValue(State.Failed("WiFi P2P not available")); running = false; return }
        if (wifiManager?.isWifiEnabled != true) {
            _state.postValue(State.Failed("WiFi is turned off", "Turn WiFi ON in Android Settings, then tap Start again."))
            running = false; return
        }
        _state.postValue(State.Initializing(1, MAX_ATTEMPTS))
        channel = mgr.initialize(context, context.mainLooper, null)
        val filter = IntentFilter().apply { addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION); addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else { @Suppress("DEPRECATION") context.registerReceiver(receiver, filter) }
        _state.postValue(State.CreatingGroup(1, MAX_ATTEMPTS))
        cleanAndCreate()
    }

    private fun cleanAndCreate() {
        val mgr = manager ?: return
        attempt++
        _state.postValue(State.CreatingGroup(attempt, MAX_ATTEMPTS))
        val cleanupsDone = CountDownLatch(3)
        val next = Runnable { if (cleanupsDone.countDownAndCheckAllDone()) createGroup() }
        try { mgr.clearLocalServices(channel, object : WifiP2pManager.ActionListener { override fun onSuccess() { next.run() }; override fun onFailure(r: Int) { next.run() } }) } catch (_: SecurityException) { next.run() }
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) { mgr.cancelConnect(channel, object : WifiP2pManager.ActionListener { override fun onSuccess() { next.run() }; override fun onFailure(r: Int) { next.run() } }) } else { @Suppress("DEPRECATION") mgr.cancelConnect(channel, null); next.run() } } catch (_: SecurityException) { next.run() }
        try { mgr.removeGroup(channel, object : WifiP2pManager.ActionListener { override fun onSuccess() { next.run() }; override fun onFailure(r: Int) { next.run() } }) } catch (_: SecurityException) { next.run() }
    }

    private fun createGroup() {
        val mgr = manager ?: return
        try { mgr.createGroup(channel, object : WifiP2pManager.ActionListener {
            override fun onSuccess() { publishService(); mgr.requestConnectionInfo(channel) { info -> onConnectionInfo(info) } }
            override fun onFailure(reason: Int) { handleCreateFailure(reason) }
        }) } catch (e: SecurityException) { _state.postValue(State.Failed("No permission: ${e.message}")); running = false }
    }

    private fun handleCreateFailure(reason: Int) {
        val reasonStr = reasonText(reason)
        if (attempt >= MAX_ATTEMPTS) {
            val hint = when (reason) {
                WifiP2pManager.BUSY -> "Quick Share / Nearby Share may be holding WiFi Direct. Disable it, toggle WiFi, retry."
                WifiP2pManager.P2P_UNSUPPORTED -> "This device does not support WiFi Direct."
                else -> "Try toggling WiFi off/on and restarting the app."
            }
            _state.postValue(State.Failed("createGroup failed after $MAX_ATTEMPTS attempts: $reasonStr", hint))
            running = false; return
        }
        val delay = BACKOFF_MS[minOf(attempt, BACKOFF_MS.size - 1)]
        _state.postValue(State.Retrying(attempt, MAX_ATTEMPTS, delay, reasonStr))
        handler.postDelayed({ cleanAndCreate() }, delay)
    }

    private fun publishService() {
        val mgr = manager ?: return
        try { val info = WifiP2pDnsSdServiceInfo.newInstance("_datanetshare", "_tcp", mapOf("port" to Constants.SOCKS5_PORT.toString())); mgr.addLocalService(channel, info, null) } catch (_: Exception) {}
    }

    private fun onConnectionInfo(info: WifiP2pInfo?) {
        if (info == null) return
        if (info.groupFormed && info.isGroupOwner) fetchGroupInfo("192.168.49.1")
    }

    private fun fetchGroupInfo(groupOwnerIp: String) {
        val mgr = manager ?: run { _state.postValue(State.GroupReady(groupOwnerIp, "DIRECT-xx-unknown", "")); return }
        try { mgr.requestGroupInfo(channel) { group -> if (group == null) handler.postDelayed({ mgr.requestGroupInfo(channel) { g2 -> emitGroup(groupOwnerIp, g2) } }, 500) else emitGroup(groupOwnerIp, group) } } catch (_: SecurityException) { _state.postValue(State.GroupReady(groupOwnerIp, "DIRECT-xx-unknown", "")) }
    }

    private fun emitGroup(groupOwnerIp: String, group: WifiP2pGroup?) {
        val ssid = group?.networkName ?: "DIRECT-xx-unknown"
        val pass = group?.passphrase ?: ""
        _state.postValue(State.GroupReady(groupOwnerIp, ssid, pass))
    }

    fun stop() {
        running = false; handler.removeCallbacksAndMessages(null)
        try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        try { manager?.removeGroup(channel, null) } catch (_: SecurityException) {}
        _state.postValue(State.Idle)
    }

    private fun reasonText(reason: Int): String = when (reason) {
        WifiP2pManager.P2P_UNSUPPORTED -> "P2P_UNSUPPORTED"
        WifiP2pManager.BUSY -> "BUSY"
        WifiP2pManager.ERROR -> "ERROR"
        else -> "UNKNOWN($reason)"
    }

    private class CountDownLatch(private val count: Int) {
        private val done = java.util.concurrent.atomic.AtomicInteger(0)
        fun countDownAndCheckAllDone(): Boolean = done.incrementAndGet() == count
    }
}
