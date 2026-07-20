package org.asr.runtime.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.util.Log

/**
 * Handles rich capability discovery for ASR.
 */
class CapabilityManager(private val context: Context) {

    companion object {
        private const val TAG = "AsrCapabilities"
    }

    fun discover(): Map<String, Any> {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        val cameraInfo = mapOf(
            "available" to true,
            "resolution" to "640x480", // Default; will be dynamic in v0.2
            "format" to "YUV_420_888"
        )

        val imuInfo = mapOf(
            "available" to (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null),
            "accelerometer" to (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null),
            "gyroscope" to (sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null),
            "rotation_vector" to (sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null)
        )

        val environmentalInfo = mapOf(
            "light" to (sensorManager.getDefaultSensor(Sensor.TYPE_LIGHT) != null),
            "proximity" to (sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY) != null),
            "pressure" to (sensorManager.getDefaultSensor(Sensor.TYPE_PRESSURE) != null)
        )

        val capabilities = mapOf(
            "camera" to cameraInfo,
            "imu" to imuInfo,
            "environmental" to environmentalInfo,
            "gps" to context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LOCATION_GPS),
            "microphone" to context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE),
            "thermal" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
            "battery" to true
        )

        Log.i(TAG, "[CAPABILITIES] $capabilities")
        return capabilities
    }
}
