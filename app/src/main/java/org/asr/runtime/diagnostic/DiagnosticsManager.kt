package org.asr.runtime.diagnostic

import android.content.Context
import android.os.SystemClock
import android.util.Log
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import org.asr.runtime.transport.TransportManager
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Orchestrates system health monitoring and structured heartbeat logging.
 *
 * Heartbeat fires at 1Hz and includes:
 * - Transport connectivity and packet statistics
 * - Packet drops, CRC failures, queue depth, reconnect count
 * - FPS, latency, memory usage
 * - All active module IDs
 *
 * The detailed transport metrics make communication problems immediately
 * visible in Logcat without needing a network monitor.
 */
class DiagnosticsManager(
    private val context: Context,
    private val transportManager: TransportManager? = null
) : SensorModule {

    override val id: String = "diagnostics"
    override var state: ModuleState = ModuleState.STOPPED

    private var startTime = 0L
    private var lastLogTime = 0L
    private val LOG_INTERVAL_MS = 1000L

    override fun start() {
        state = ModuleState.STARTING
        startTime = SystemClock.elapsedRealtime()
        lastLogTime = startTime
        Log.i(TAG, "Diagnostics manager started")
        state = ModuleState.RUNNING
    }

    override fun stop() {
        state = ModuleState.STOPPING
        Log.i(TAG, "Diagnostics manager stopped")
        state = ModuleState.STOPPED
    }

    override fun release() = stop()

    /**
     * Emits a structured 1Hz heartbeat to Logcat.
     *
     * @param fps               Current instantaneous FPS from BenchmarkProcessor.
     * @param latency           Frame-to-analyzer latency in milliseconds.
     * @param activeModules     List of module IDs currently in RUNNING state.
     * @param tcpTransport      Optional TcpTransport for queue depth stats.
     */
    fun logHeartbeat(
        fps: Double,
        latency: Double,
        activeModules: List<String>,
        tcpTransport: org.asr.runtime.transport.TcpTransport? = null
    ) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastLogTime < LOG_INTERVAL_MS) return

        val memMb  = (Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }) / (1024 * 1024)
        val uptime = formatDuration(now - startTime)

        val transportStatus = if (transportManager != null) {
            val connected  = transportManager.isAnyConnected()
            val pkts       = transportManager.totalPacketsSent
            val drops      = transportManager.totalPacketDrops
            val crcs       = transportManager.totalCrcFailures
            val reconnects = transportManager.reconnectCount
            val qDepth     = tcpTransport?.queueDepth ?: 0
            val qDrops     = tcpTransport?.queueDrops ?: 0
            if (connected)
                "CONNECTED | pkts=$pkts drops=$drops crc_fail=$crcs reconnects=$reconnects q=$qDepth q_drops=$qDrops"
            else
                "DISCONNECTED | drops=$drops crc_fail=$crcs reconnects=$reconnects"
        } else {
            "N/A"
        }

        val heartbeat = buildString {
            appendLine("[HEARTBEAT] ================================================")
            appendLine("  Status:    RUNNING     Uptime:  $uptime")
            appendLine("  FPS:       ${String.format(Locale.US, "%.1f", fps)}")
            appendLine("  Latency:   ${String.format(Locale.US, "%.1f", latency)} ms")
            appendLine("  Memory:    $memMb MB")
            appendLine("  Transport: $transportStatus")
            appendLine("  Modules:   ${activeModules.joinToString(", ")}")
            append("====================================================")
        }

        Log.d(TAG, heartbeat)
        lastLogTime = now
    }

    private fun formatDuration(ms: Long): String {
        val h = TimeUnit.MILLISECONDS.toHours(ms)
        val m = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val s = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
    }

    companion object {
        private const val TAG = "PhoneCamera"
    }
}
