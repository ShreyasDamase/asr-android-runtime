package com.example.cameraros.camera

import android.util.Log
import androidx.camera.core.ImageProxy
import java.util.concurrent.atomic.AtomicLong

/**
 * Orchestrates a sequence of [FrameProcessor]s.
 *
 * Responsibilities:
 * - Assigns a monotonic [frameId] to every frame (single source of truth).
 * - Detects frame gaps by comparing consecutive sensor timestamps.
 *   Note: this is a heuristic based on timestamp deltas, not an exact dropped-frame count.
 *   Reported as [frameGapEvents] — do not treat as a precise drop counter.
 * - Constructs a [FrameMetadata] object for each frame and passes it to all processors.
 * - Prevents one failing processor from killing the whole pipeline.
 *
 * @param processors      Ordered list of processors to call for each frame.
 * @param onFrameGap      Optional callback invoked when a gap is detected.
 *                        Parameters: (frameId, gapMs). Called on the analyzer thread.
 */
class FramePipeline(
    private val processors: List<FrameProcessor>,
    private val onFrameGap: (frameId: Long, gapMs: Long) -> Unit = { _, _ -> }
) {

    companion object {
        private const val TAG = "PhoneCamera"

        // A gap larger than this threshold likely means at least one frame was dropped.
        // 50ms = 1.5× the expected 33ms interval at 30 FPS.
        // Recalibrate if targetFps is changed via CameraConfig.
        private const val GAP_THRESHOLD_NS = 50_000_000L
    }

    private val frameCounter = AtomicLong(0)

    /** Running count of detected timestamp gaps. Heuristic — see class doc. */
    @Volatile
    var frameGapEvents = 0L
        private set

    private var lastSensorTimestampNs = 0L

    /**
     * Dispatches a frame to all registered processors.
     *
     * @param image             The ImageProxy. Must be closed by the caller after this returns.
     * @param sensorTimestampNs Hardware sensor timestamp (ns, boot-relative).
     * @param analyzerEnterNs   System.nanoTime() captured when [CameraAnalyzer.analyze] was entered.
     * @return The frame ID assigned to this frame.
     */
    fun dispatch(image: ImageProxy, sensorTimestampNs: Long, analyzerEnterNs: Long): Long {
        val frameId = frameCounter.incrementAndGet()

        // Heuristic gap detection
        if (lastSensorTimestampNs != 0L) {
            val gapNs = sensorTimestampNs - lastSensorTimestampNs
            if (gapNs > GAP_THRESHOLD_NS) {
                frameGapEvents++
                val gapMs = gapNs / 1_000_000
                Log.w(TAG, "[GAP] frame_id=$frameId gap_ms=$gapMs (heuristic)")
                onFrameGap(frameId, gapMs)
            }
        }
        lastSensorTimestampNs = sensorTimestampNs

        val metadata = FrameMetadata(
            frameId = frameId,
            sensorTimestampNs = sensorTimestampNs,
            analyzerEnterNs = analyzerEnterNs
        )

        processors.forEach { processor ->
            try {
                processor.process(image, metadata)
            } catch (e: Exception) {
                Log.e(TAG, "Processor error in ${processor.javaClass.simpleName}", e)
            }
        }

        return frameId
    }
}
