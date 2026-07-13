package com.example.cameraros.camera

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Robotics-grade benchmark processor.
 *
 * Implements [FrameProcessor] — plugged into [FramePipeline] as the first processor.
 *
 * Thread safety:
 * - [process] is always called from the single camera analyzer thread.
 * - [snapshot] is a [@Volatile] reference to an immutable [TelemetrySnapshot].
 *   Writing it replaces the reference atomically; any thread reading [snapshot]
 *   gets a consistent, non-torn point-in-time view.
 * - [updateThermalStatus] and [onCameraError] may be called from any thread
 *   (thermal listener / Camera2 callbacks) and are safe because they use
 *   copy() to produce a new snapshot atomically via the volatile write.
 */
class BenchmarkManager(private val context: Context) : FrameProcessor {

    companion object {
        private const val TAG = "PhoneCamera"
        private const val LOG_INTERVAL_MS = 1000L

        /**
         * Telemetry protocol version.
         * Increment this when fields are added or removed so that Ubuntu-side
         * parsers can detect the format and adapt.
         */
        const val TELEMETRY_PROTOCOL = 1
    }

    // -------------------------------------------------------------------------
    // Telemetry snapshot — read from any thread, written on analyzer thread
    // -------------------------------------------------------------------------

    @Volatile
    var snapshot = TelemetrySnapshot()
        private set

    // -------------------------------------------------------------------------
    // Internal state — only accessed on the analyzer thread
    // -------------------------------------------------------------------------

    private var startTime = 0L
    private var lastLogTime = 0L
    private var totalFrames = 0L
    private var averageFps = 0.0
    private var frameGapEvents = 0L

    /** Sliding-window FPS: stores arrival timestamps for the last 1 second of frames. */
    private val frameWindowMs = ArrayDeque<Long>()

    // -------------------------------------------------------------------------
    // Lifecycle
    // -------------------------------------------------------------------------

    /**
     * Reset all metrics. Call this before (re)starting the camera pipeline.
     * @param restartCount The number of restarts so far, preserved across resets.
     */
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

    // -------------------------------------------------------------------------
    // FrameProcessor implementation
    // -------------------------------------------------------------------------

    override fun process(image: ImageProxy, metadata: FrameMetadata) {
        val processStartNs = System.nanoTime()
        val now = SystemClock.elapsedRealtime()

        if (startTime == 0L) startTime = now

        totalFrames++

        // Sliding-window instant FPS: count frames that arrived within the last 1 second
        frameWindowMs.addLast(now)
        while (frameWindowMs.isNotEmpty() && frameWindowMs.first() < now - 1000L) {
            frameWindowMs.removeFirst()
        }
        val instantFps = frameWindowMs.size.toDouble()

        // Lifetime average FPS
        val totalElapsed = now - startTime
        if (totalElapsed > 0) {
            averageFps = (totalFrames.toDouble() / totalElapsed.toDouble()) * 1000.0
        }

        // End-to-end latency: sensor hardware capture → analyzer entry
        // Both timestamps are boot-relative nanoseconds.
        val sensorTimeMs = TimeUnit.NANOSECONDS.toMillis(metadata.sensorTimestampNs)
        val bootTimeMs = SystemClock.elapsedRealtimeNanos() / 1_000_000
        val latencyMs = (bootTimeMs - sensorTimeMs).toDouble()

        // Time spent inside this processor
        val analyzerMs = (System.nanoTime() - processStartNs) / 1_000_000.0

        // Preserve cross-thread fields from the previous snapshot
        val prev = snapshot

        // Publish new snapshot — atomic volatile write
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

        // Periodic structured telemetry heartbeat
        if (now - lastLogTime >= LOG_INTERVAL_MS) {
            logTelemetry(snapshot)
            lastLogTime = now
        }
    }

    // -------------------------------------------------------------------------
    // Cross-thread update methods (safe — produce new snapshots via copy())
    // -------------------------------------------------------------------------

    /**
     * Called when a frame gap is detected by [FramePipeline].
     * Safe to call from the analyzer thread.
     */
    fun onFrameGap() {
        frameGapEvents++  // analyzer thread only — no sync needed
    }

    /**
     * Called when a Camera2 device error occurs.
     * May be called from the camera callback thread.
     */
    fun onCameraError() {
        snapshot = snapshot.copy(cameraErrors = snapshot.cameraErrors + 1)
    }

    /**
     * Update the thermal status in the snapshot.
     * Called from the thermal listener (main thread).
     */
    fun updateThermalStatus(status: ThermalStatus) {
        snapshot = snapshot.copy(thermalStatus = status)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Returns total running time in ms since last [reset]. */
    fun getRunningTimeMs(): Long {
        if (startTime == 0L) return 0L
        return SystemClock.elapsedRealtime() - startTime
    }

    /** Current app heap usage in MB. */
    fun getMemoryUsageMb(): Long {
        val runtime = Runtime.getRuntime()
        return (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)
    }

    /**
     * Emits one structured telemetry line per second.
     *
     * Format: [TELEMETRY] protocol=1 frame_id=N fps=N.N avg_fps=N.N ...
     *
     * Parse on Ubuntu:
     *   adb logcat -s PhoneCamera:D | grep TELEMETRY \
     *     | awk '{for(i=1;i<=NF;i++) if($i~/fps=/) print $i}'
     */
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
