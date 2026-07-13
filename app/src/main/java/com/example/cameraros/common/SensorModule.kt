package com.example.cameraros.common

/**
 * Common interface for all sensor modules in the Android Robotics Sensor Platform.
 *
 * Every module (Camera, IMU, GPS, Battery) exposes the same four lifecycle operations
 * so the command router can treat them generically:
 *
 *   camera start  →  CameraModule.start()
 *   imu start     →  ImuModule.start()
 *   gps status    →  GpsModule.status()
 */
interface SensorModule {

    /** Human-readable name used in logs and telemetry (e.g. "Camera", "IMU", "GPS"). */
    val moduleName: String

    /** Start the sensor and begin publishing data. */
    fun start()

    /** Stop the sensor cleanly, releasing all resources. */
    fun stop()

    /** Log the current status of this module to Logcat. */
    fun status()

    /**
     * Apply runtime configuration parameters.
     * Keys and values are module-specific (e.g. "fps" → "30", "resolution" → "640x480").
     */
    fun configure(params: Map<String, String>)
}
