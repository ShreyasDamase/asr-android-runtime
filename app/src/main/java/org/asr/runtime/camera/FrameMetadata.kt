package org.asr.runtime.camera

/**
 * Immutable per-frame metadata carrier passed through the camera pipeline.
 *
 * @param frameId           Monotonically increasing frame counter from CameraAnalyzer.
 * @param sensorTimestampNs Hardware sensor timestamp in nanoseconds (from ImageProxy.imageInfo).
 * @param analyzerEnterNs   System.nanoTime() when the frame entered CameraAnalyzer.
 *
 * Latency = analyzerEnterNs - sensorTimestampNs (pipeline latency from sensor to analyzer).
 */
data class FrameMetadata(
    val frameId: Long,
    val sensorTimestampNs: Long,
    val analyzerEnterNs: Long
)
