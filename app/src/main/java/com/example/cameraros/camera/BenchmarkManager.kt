package com.example.cameraros.camera

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max
import kotlin.math.min

/**
 * Manages robotics-grade camera benchmarks and health heartbeat.
 */
class BenchmarkManager(private val context: Context) : FrameProcessor {

    companion object {
        private const val TAG = "PhoneCamera"
        private const val LOG_INTERVAL_MS = 1000L
    }

    private val frameCount = AtomicLong(0)
    private var startTime = 0L
    private var lastFrameTime = 0L
    private var lastLogTime = 0L

    // Metrics
    var currentFps = 0.0
    var averageFps = 0.0
    var frameTimeMs = 0.0
    var analyzerTimeMs = 0.0
    var latencyMs = 0.0
    var totalFrames = 0L

    private var totalAnalyzerTime = 0L

    fun reset() {
        frameCount.set(0)
        startTime = SystemClock.elapsedRealtime()
        lastFrameTime = 0L
        lastLogTime = startTime
        currentFps = 0.0
        averageFps = 0.0
        frameTimeMs = 0.0
        analyzerTimeMs = 0.0
        latencyMs = 0.0
        totalAnalyzerTime = 0L
        Log.i(TAG, "Benchmark Reset")
    }

    override fun process(image: ImageProxy, timestampNs: Long) {
        // Since FramePipeline calls this, we measure the "processing" duration here
        val startProcessTime = System.nanoTime()
        
        updateMetrics(timestampNs)
        
        val durationNs = System.nanoTime() - startProcessTime
        analyzerTimeMs = durationNs / 1_000_000.0
        totalAnalyzerTime += (durationNs / 1_000_000)
    }

    private fun updateMetrics(cameraTimestampNs: Long) {
        val now = SystemClock.elapsedRealtime()
        if (startTime == 0L) startTime = now
        
        val count = frameCount.incrementAndGet()
        totalFrames = count
        
        // Calculate intervals
        if (lastFrameTime != 0L) {
            val interval = now - lastFrameTime
            frameTimeMs = interval.toDouble()
        }
        lastFrameTime = now

        // FPS Calculations
        val totalElapsed = now - startTime
        if (totalElapsed > 0) {
            averageFps = (count.toDouble() / totalElapsed.toDouble()) * 1000.0
            currentFps = averageFps // Using avg as current for stability in simple benchmark
        }

        // Latency: Diff between camera capture and current arrival
        val cameraTimeMs = TimeUnit.NANOSECONDS.toMillis(cameraTimestampNs)
        val bootTimeMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
        latencyMs = (bootTimeMs - cameraTimeMs).toDouble()

        // Periodic Heartbeat Logging
        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            logHeartbeat()
            lastLogTime = now
        }
    }

    fun getRunningTimeMs(): Long {
        if (startTime == 0L) return 0L
        return SystemClock.elapsedRealtime() - startTime
    }

    private fun logHeartbeat() {
        val uptimeMs = getRunningTimeMs()
        val uptimeStr = formatDuration(uptimeMs)
        val memInfo = getMemoryUsage()

        val heartbeat = """
            --------------------------------------------------
            Status: RUNNING
            FPS: ${String.format(Locale.US, "%.1f", currentFps)}
            Average FPS: ${String.format(Locale.US, "%.1f", averageFps)}
            Latency: ${String.format(Locale.US, "%.1f", latencyMs)} ms
            Analyzer Time: ${String.format(Locale.US, "%.2f", analyzerTimeMs)} ms
            Frames: $totalFrames
            Uptime: $uptimeStr
            Memory: $memInfo MB
            --------------------------------------------------
        """.trimIndent()
        Log.d(TAG, heartbeat)
    }

    private fun formatDuration(ms: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(ms)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(ms) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(ms) % 60
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    fun getMemoryUsage(): Long {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memoryInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memoryInfo)
        
        val runtime = Runtime.getRuntime()
        val usedMem = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        return usedMem
    }
}
