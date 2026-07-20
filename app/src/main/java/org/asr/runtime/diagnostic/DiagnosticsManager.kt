package org.asr.runtime.diagnostic

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Orchestrates system health monitoring and heartbeat.
 */
class DiagnosticsManager(private val context: Context) : SensorModule {

    override val id: String = "diagnostics"
    override var state: ModuleState = ModuleState.STOPPED

    private var startTime = 0L
    private var lastLogTime = 0L
    private val LOG_INTERVAL_MS = 1000L

    override fun start() {
        state = ModuleState.STARTING
        startTime = SystemClock.elapsedRealtime()
        lastLogTime = startTime
        Log.i("AsrDiagnostics", "Diagnostics manager started")
        state = ModuleState.RUNNING
    }

    override fun stop() {
        state = ModuleState.STOPPING
        Log.i("AsrDiagnostics", "Diagnostics manager stopped")
        state = ModuleState.STOPPED
    }

    override fun release() {
        stop()
    }

    fun logHeartbeat(
        fps: Double,
        latency: Double,
        activeModules: List<String>,
        transportConnected: Boolean
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            val memInfo = getMemoryUsage()
            val uptime = formatDuration(now - startTime)
            
            val heartbeat = """
                [HEARTBEAT]
                Status: RUNNING
                FPS: ${String.format(Locale.US, "%.1f", fps)}
                Latency: ${String.format(Locale.US, "%.1f", latency)} ms
                Uptime: $uptime
                Memory: $memInfo MB
                Transport: ${if (transportConnected) "CONNECTED" else "DISCONNECTED"}
                Modules: ${activeModules.joinToString()}
                ==================================================
            """.trimIndent()
            
            Log.d("PhoneCamera", heartbeat)
            lastLogTime = now
        }
    }

    private fun getMemoryUsage(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
    }

    private fun formatDuration(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }
}
