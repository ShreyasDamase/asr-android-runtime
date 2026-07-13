package com.example.cameraros.camera

/**
 * Immutable snapshot of all service telemetry.
 *
 * Written atomically by the analyzer thread as a new object.
 * The [@Volatile] reference in [BenchmarkManager] guarantees any other thread
 * reading [BenchmarkManager.snapshot] sees a consistent, non-torn view.
 *
 * Intentionally named "Telemetry" rather than "Frame" because it aggregates
 * fields beyond frame data: memory, uptime, thermal state, restart count, etc.
 *
 * Protocol version 1 field set. When fields are added or removed, increment
 * [TELEMETRY_PROTOCOL] in [BenchmarkManager] so Ubuntu-side parsers can adapt.
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
