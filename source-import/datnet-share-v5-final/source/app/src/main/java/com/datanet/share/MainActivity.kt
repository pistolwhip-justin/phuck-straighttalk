package com.datanet.share

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.method.ScrollingMovementMethod
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.datanet.share.common.Constants
import com.datanet.share.common.SpeedTestClient
import com.datanet.share.common.UdpTest
import com.datanet.share.receiver.ReceiverService
import com.datanet.share.receiver.ReceiverVpnService
import com.datanet.share.sender.SenderService

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var senderBtn: Button
    private lateinit var localOnlyBtn: Button
    private lateinit var receiverBtn: Button
    private lateinit var forceStopBtn: Button
    private lateinit var udpTestBtn: Button
    private lateinit var speedTestBtn: Button
    private lateinit var refreshInterfacesBtn: Button
    private lateinit var credsCard: View
    private lateinit var ssidText: TextView
    private lateinit var passText: TextView
    private lateinit var interfacesText: TextView

    private val pendingLogs = StringBuilder()
    private val logLock = Any()
    private val logHandler = Handler(Looper.getMainLooper())
    private val logFlushRunnable = Runnable { flushLogs() }

    private var mode: Mode = Mode.NONE
    private enum class Mode { NONE, SENDER, RECEIVER }

    private val vpnPermissionLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) startReceiverService() else appendLog("VPN permission denied")
    }

    private val permsLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            val status = intent?.getStringExtra(Constants.EXTRA_STATUS) ?: return
            val details = intent.getStringExtra(Constants.EXTRA_DETAILS) ?: ""
            appendLog("[${now()}] ${status.uppercase()}  $details")
            when (status) {
                "active" -> {
                    val ip = intent.getStringExtra(Constants.EXTRA_PROXY_IP)
                    val port = intent.getIntExtra(Constants.EXTRA_PROXY_PORT, 0)
                    val ssid = intent.getStringExtra(Constants.EXTRA_SSID)
                    val pass = intent.getStringExtra(Constants.EXTRA_PASSPHRASE)
                    val interfaces = intent.getStringExtra(Constants.EXTRA_INTERFACES)
                    statusText.text = if (ip != null && port != 0) "Sharing on $ip:$port" else "Active"
                    if (!ssid.isNullOrBlank()) { credsCard.visibility = View.VISIBLE; ssidText.text = ssid; passText.text = if (pass.isNullOrBlank()) "(no password)" else pass }
                    else if (interfaces != null) { credsCard.visibility = View.VISIBLE; ssidText.text = "(local mode)"; passText.text = "-" }
                    if (!interfaces.isNullOrBlank()) { interfacesText.text = interfaces; interfacesText.visibility = View.VISIBLE } else interfacesText.visibility = View.GONE
                    setButtonsBusy(false)
                }
                "failed" -> { statusText.text = "Failed"; credsCard.visibility = View.GONE; setButtonsBusy(false) }
                "starting" -> { statusText.text = "Starting..."; setButtonsBusy(true) }
                "stopped" -> { statusText.text = "Stopped"; credsCard.visibility = View.GONE; setButtonsBusy(false) }
                "needs_permission" -> requestVpnPermission()
                "stats" -> {}
            }
        }
    }

    private fun setButtonsBusy(busy: Boolean) { runOnUiThread { senderBtn.isEnabled = !busy || mode != Mode.SENDER; localOnlyBtn.isEnabled = !busy || mode != Mode.SENDER; receiverBtn.isEnabled = !busy || mode != Mode.RECEIVER } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        bindViews()
        requestPermissionsIfNeeded()
    }

    private fun bindViews() {
        statusText = findViewById(R.id.statusText)
        logText = findViewById<TextView?>(R.id.logText).also { it?.movementMethod = ScrollingMovementMethod() }!!
        senderBtn = findViewById(R.id.btnSender)
        localOnlyBtn = findViewById(R.id.btnLocalOnly)
        receiverBtn = findViewById(R.id.btnReceiver)
        forceStopBtn = findViewById(R.id.btnForceStop)
        udpTestBtn = findViewById(R.id.btnUdpTest)
        speedTestBtn = findViewById(R.id.btnSpeedTest)
        refreshInterfacesBtn = findViewById(R.id.btnRefreshInterfaces)
        credsCard = findViewById(R.id.credsCard)
        ssidText = findViewById(R.id.ssidText)
        passText = findViewById(R.id.passText)
        interfacesText = findViewById(R.id.interfacesText)
        senderBtn.setOnClickListener { startSender() }
        localOnlyBtn.setOnClickListener { startLocalOnly() }
        receiverBtn.setOnClickListener { startReceiver() }
        forceStopBtn.setOnClickListener { forceStopAll() }
        udpTestBtn.setOnClickListener { runUdpTest() }
        speedTestBtn.setOnClickListener { runSpeedTest() }
        refreshInterfacesBtn.setOnClickListener { refreshInterfaces() }
    }

    private fun startLocalOnly() {
        mode = Mode.SENDER
        statusText.text = "Starting local-only..."
        appendLog("[${now()}] LOCAL-ONLY mode starting (no WiFi Direct)")
        val intent = Intent(this, SenderService::class.java).apply { action = SenderService.ACTION_START_LOCAL }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun refreshInterfaces() {
        val interfaces = com.datanet.share.common.InterfaceEnumerator.getSummary()
        interfacesText.text = interfaces
        appendLog("[${now()}] Interfaces refreshed:\n$interfaces")
    }

    private fun runUdpTest() {
        appendLog("[${now()}] === UDP TEST STARTING ===")
        UdpTest.runTest({ msg -> appendLog("[UDP TEST] $msg") }, { msg -> appendLog("[UDP TEST] $msg") })
    }

    private fun runSpeedTest() {
        appendLog("[${now()}] === LINK SPEED TEST STARTING ===")
        SpeedTestClient.runTest(
            host = "192.168.49.1",
            port = com.datanet.share.sender.SpeedTestServer.DEFAULT_PORT,
            onProgress = { msg -> appendLog("[SPEED TEST] $msg") },
            onComplete = { msg -> appendLog("[SPEED TEST] $msg") }
        )
    }

    private fun forceStopAll() {
        appendLog("[${now()}] FORCE STOP")
        // Use ContextCompat.startForegroundService for STOP actions to avoid
        // BackgroundServiceStartNotAllowedException on Android 12+ when app is backgrounded.
        try { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, SenderService::class.java).apply { action = SenderService.ACTION_STOP }) } catch (_: Exception) {}
        try { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, ReceiverService::class.java).apply { action = ReceiverService.ACTION_STOP }) } catch (_: Exception) {}
        try { androidx.core.content.ContextCompat.startForegroundService(this, Intent(this, ReceiverVpnService::class.java).apply { action = ReceiverVpnService.ACTION_STOP }) } catch (_: Exception) {}
        // Give the STOP intents a moment to be processed, then reset UI.
        // No killProcess — let Android handle process lifecycle normally so
        // onDestroy runs and resources are released cleanly.
        Handler(Looper.getMainLooper()).postDelayed({
            statusText.text = "Stopped"; credsCard.visibility = View.GONE; setButtonsBusy(false); mode = Mode.NONE
        }, 300)
    }

    private fun startSender() {
        mode = Mode.SENDER
        statusText.text = "Starting sender..."
        appendLog("[${now()}] SENDER mode starting")
        val intent = Intent(this, SenderService::class.java).apply { action = SenderService.ACTION_START }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun startReceiver() {
        mode = Mode.RECEIVER
        statusText.text = "Starting receiver..."
        appendLog("[${now()}] RECEIVER mode starting")
        val prepare = VpnService.prepare(this)
        if (prepare != null) vpnPermissionLauncher.launch(prepare) else startReceiverService()
    }

    private fun startReceiverService() {
        val intent = Intent(this, ReceiverService::class.java).apply { action = ReceiverService.ACTION_START; putExtra(ReceiverService.EXTRA_SOCKS_HOST, "192.168.49.1"); putExtra(ReceiverService.EXTRA_SOCKS_PORT, Constants.SOCKS5_PORT) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent) else startService(intent)
    }

    private fun requestVpnPermission() { val p = VpnService.prepare(this); if (p != null) vpnPermissionLauncher.launch(p) else startReceiverService() }

    private fun requestPermissionsIfNeeded() {
        val needed = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.NEARBY_WIFI_DEVICES
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.POST_NOTIFICATIONS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) needed += Manifest.permission.ACCESS_FINE_LOCATION
        }
        if (needed.isNotEmpty()) permsLauncher.launch(needed.toTypedArray())
    }

    override fun onResume() { super.onResume(); val filter = IntentFilter().apply { addAction(Constants.ACTION_SENDER_STATE); addAction(Constants.ACTION_RECEIVER_STATE) }; LocalBroadcastManager.getInstance(this).registerReceiver(stateReceiver, filter) }
    override fun onPause() { super.onPause(); try { LocalBroadcastManager.getInstance(this).unregisterReceiver(stateReceiver) } catch (_: Exception) {} }
    override fun onDestroy() { super.onDestroy(); logHandler.removeCallbacks(logFlushRunnable) }

    private fun appendLog(line: String) { synchronized(logLock) { pendingLogs.append(line).append('\n') }; logHandler.removeCallbacks(logFlushRunnable); logHandler.postDelayed(logFlushRunnable, 200) }

    private fun flushLogs() {
        val toWrite: String
        synchronized(logLock) { if (pendingLogs.isEmpty()) return; toWrite = pendingLogs.toString(); pendingLogs.setLength(0) }
        runOnUiThread {
            logText.append(toWrite)
            val fullText = logText.text
            if (fullText.length > 8000) logText.text = fullText.substring(fullText.length - 6000)
            val scroll = logText.layout
            if (scroll != null) { val y = scroll.getLineTop(scroll.lineCount) - logText.height; if (y > 0) logText.scrollTo(0, y + 20) }
        }
    }

    private fun now(): String = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
}
