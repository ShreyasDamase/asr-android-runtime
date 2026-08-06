package org.asr.runtime.diagnostic

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import org.asr.runtime.camera.FrameMetadata
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Robotics-grade benchmark processor.
 *
 * Tracks FPS, latency, thermal status, memory, and error counts.
 * Called from CameraAnalyzer before the CameraPipeline for lightweight metric collection.
 */
class BenchmarkProcessor(private val context: Context) {

    companion object {
        private const val TAG = "PhoneCamera"
        private const val LOG_INTERVAL_MS = 1000L
        const val TELEMETRY_PROTOCOL = 1
    }

    @Volatile
    var snapshot = TelemetrySnapshot()
        private set

    private var startTime = 0L
    private var lastLogTime = 0L
    private var totalFrames = 0L
    private var averageFps = 0.0
    private var frameGapEvents = 0L
    private val frameWindowMs = ArrayDeque<Long>()

    fun reset(restartCount: Int = 0) {
        startTime = SystemClock.elapsedRealtime()
        lastLogTime = startTime
        totalFrames = 0L
        averageFps = 0.0
        frameGapEvents = 0L
        frameWindowMs.clear()
        snapshot = TelemetrySnapshot(restartCount = restartCount)
        Log.i(TAG, "Benchmark Reset")
    }

    fun process(image: ImageProxy, metadata: FrameMetadata) {
        val processStartNs = System.nanoTime()
        val now = SystemClock.elapsedRealtime()

        if (startTime == 0L) startTime = now
        totalFrames++

        frameWindowMs.addLast(now)
        while (frameWindowMs.isNotEmpty() && frameWindowMs.first() < now - 1000L) {
            frameWindowMs.removeFirst()
        }
        val instantFps = frameWindowMs.size.toDouble()

        val totalElapsed = now - startTime
        if (totalElapsed > 0) {
            averageFps = (totalFrames.toDouble() / totalElapsed.toDouble()) * 1000.0
        }

        val sensorTimeMs = TimeUnit.NANOSECONDS.toMillis(metadata.sensorTimestampNs)
        val bootTimeMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
        val latencyMs = (bootTimeMs - sensorTimeMs).toDouble()

        val analyzerMs = (System.nanoTime() - processStartNs) / 1_000_000.0
        val prev = snapshot

        snapshot = TelemetrySnapshot(
            frameId = metadata.frameId,
            instantFps = instantFps,
            averageFps = averageFps,
            latencyMs = latencyMs,
            analyzerMs = analyzerMs,
            totalFrames = totalFrames,
            frameGapEvents = frameGapEvents,
            cameraErrors = prev.cameraErrors,
            restartCount = prev.restartCount,
            thermalStatus = prev.thermalStatus,
            sensorTimestampNs = metadata.sensorTimestampNs,
            analyzerEnterNs = metadata.analyzerEnterNs,
            memoryMb = getMemoryUsageMb(),
            uptimeMs = now - startTime
        )

        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            logTelemetry(snapshot)
            lastLogTime = now
        }
    }

    fun onFrameGap() {
        frameGapEvents++
    }

    fun onCameraError() {
        snapshot = snapshot.copy(cameraErrors = snapshot.cameraErrors + 1)
    }

    fun updateThermalStatus(status: ThermalStatus) {
        snapshot = snapshot.copy(thermalStatus = status)
    }

    fun getRunningTimeMs(): Long {
        if (startTime == 0L) return 0L
        return SystemClock.elapsedRealtime() - startTime
    }

    fun getMemoryUsageMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
    }

    private fun logTelemetry(s: TelemetrySnapshot) {
        val uptimeSec = s.uptimeMs / 1000
        Log.d(
            TAG,
            "[TELEMETRY] protocol=$TELEMETRY_PROTOCOL" +
                    " frame_id=${s.frameId}" +
                    " fps=${String.format(Locale.US, "%.1f", s.instantFps)}" +
                    " avg_fps=${String.format(Locale.US, "%.1f", s.averageFps)}" +
                    " latency_ms=${String.format(Locale.US, "%.1f", s.latencyMs)}" +
                    " analyzer_ms=${String.format(Locale.US, "%.2f", s.analyzerMs)}" +
                    " frames=${s.totalFrames}" +
                    " gap_events=${s.frameGapEvents}" +
                    " errors=${s.cameraErrors}" +
                    " restarts=${s.restartCount}" +
                    " thermal=${s.thermalStatus.name}" +
                    " memory_mb=${s.memoryMb}" +
                    " uptime_s=$uptimeSec"
        )
    }
}
