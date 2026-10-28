package com.datanet.share.common

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Diagnostic-only, asynchronous event logger.
 * This class is added only to the DiagnosticWTF branch/build.
 * It observes state; it must never control application behavior.
 */
object DiagnosticLogger {
    private const val TAG = "DiagnosticWTF"
    private const val MAX_LOG_BYTES = 8L * 1024L * 1024L
    private val writer = Executors.newSingleThreadExecutor { r ->
        Thread(r, "diagnostic-log-writer").apply { isDaemon = true }
    }
    @Volatile private var appContext: Context? = null
    @Volatile private var logFile: File? = null

    fun initialize(context: Context) {
        if (appContext != null) return
        synchronized(this) {
            if (appContext != null) return
            appContext = context.applicationContext
            val dir = File(context.applicationContext.filesDir, "diagnostic-logs")
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "Unable to create diagnostic log directory")
                return
            }
            logFile = File(dir, "diagnostic.log")
            record("logger_initialized", mapOf(
                "app_version" to "1.0.0",
                "android_sdk" to android.os.Build.VERSION.SDK_INT.toString(),
                "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}"
            ))
        }
    }

    fun record(event: String, fields: Map<String, String> = emptyMap()) {
        val context = appContext ?: return
        val file = logFile ?: return
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ", Locale.US).format(Date())
        val elapsedMs = SystemClock.elapsedRealtime()
        val cpuMs = Process.getElapsedCpuTime()
        val battery = try {
            val status = context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = status?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = status?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            if (level >= 0 && scale > 0) "${(level * 100) / scale}%" else "unknown"
        } catch (_: Exception) { "unavailable" }
        val safeFields = fields.entries.joinToString(" ") { (key, value) ->
            "${safe(key)}=${safe(value)}"
        }
        val line = "${timestamp} elapsed_ms=${elapsedMs} process_cpu_ms=${cpuMs} battery=${battery} event=${safe(event)} ${safeFields}\n"
        Log.i(TAG, line.trim())
        try {
            writer.execute {
                try {
                    if (file.exists() && file.length() >= MAX_LOG_BYTES) {
                        val previous = File(file.parentFile, "diagnostic-previous.log")
                        if (previous.exists()) previous.delete()
                        file.renameTo(previous)
                    }
                    file.appendText(line, Charsets.UTF_8)
                } catch (e: Exception) {
                    Log.w(TAG, "Unable to persist diagnostic event", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Diagnostic logger unavailable", e)
        }
    }

    fun readLogs(): String {
        val file = logFile ?: return "Diagnostic logger has not initialized."
        val previous = File(file.parentFile, "diagnostic-previous.log")
        return buildString {
            if (previous.exists()) append(previous.readText(Charsets.UTF_8)).append('\n')
            if (file.exists()) append(file.readText(Charsets.UTF_8))
        }.ifBlank { "No diagnostic events recorded yet." }
    }

    private fun safe(value: String): String = value
        .replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace(" ", "_")

    private fun safe(value: Any?): String = safe(value?.toString() ?: "null")
}
