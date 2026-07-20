package org.asr.runtime.pipeline

/**
 * Immutable per-frame metadata carrier passed through the pipeline.
 */
data class FrameMetadata(
    val frameId: Long,
    val sensorTimestampNs: Long,
    val analyzerEnterNs: Long
)
