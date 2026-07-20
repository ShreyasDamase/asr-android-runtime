package org.asr.runtime.diagnostic

/**
 * Immutable snapshot of all service telemetry.
 */
data class TelemetrySnapshot(
    val frameId: Long = 0,
    val instantFps: Double = 0.0,
    val averageFps: Double = 0.0,
    val latencyMs: Double = 0.0,
    val analyzerMs: Double = 0.0,
    val totalFrames: Long = 0,
    val frameGapEvents: Long = 0,
    val cameraErrors: Int = 0,
    val restartCount: Int = 0,
    val thermalStatus: ThermalStatus = ThermalStatus.UNKNOWN,
    val sensorTimestampNs: Long = 0,
    val analyzerEnterNs: Long = 0,
    val memoryMb: Long = 0,
    val uptimeMs: Long = 0
)
