package com.example.cameraros.camera

/**
 * Immutable per-frame metadata carrier passed through the pipeline.
 *
 * All timing information for a frame lives here. Adding new fields
 * (e.g. exposure time, ISO, camera ID) extends this struct without
 * changing any processor signatures.
 *
 * @param frameId      Monotonic counter from [FramePipeline]. Unique per session.
 * @param sensorTimestampNs  Hardware capture timestamp from the camera sensor (ns, boot time).
 * @param analyzerEnterNs    System.nanoTime() when [CameraAnalyzer.analyze] was entered.
 *
 * Future fields to add when the data is available:
 *   val exposureTimeNs: Long    — from SENSOR_EXPOSURE_TIME capture result
 *   val iso: Int                — from SENSOR_SENSITIVITY capture result
 *   val cameraId: String        — for multi-camera systems
 */
data class FrameMetadata(
    val frameId: Long,
    val sensorTimestampNs: Long,
    val analyzerEnterNs: Long
)
