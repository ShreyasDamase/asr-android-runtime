package org.asr.runtime.core

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.util.Log

/**
 * Handles capability discovery for ASR.
 */
class CapabilityManager(private val context: Context) {

    companion object {
        private const val TAG = "AsrCapabilities"
    }

    fun discover(): Map<String, Boolean> {
        val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        
        val capabilities = mapOf(
            "camera" to true, // Always true if ASR is running
            "imu" to (sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) != null && 
                     sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null),
            "gps" to context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LOCATION_GPS),
            "microphone" to context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE),
            "thermal" to (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q),
            "battery" to true // All Android devices have battery system
        )

        Log.i(TAG, "[CAPABILITIES] $capabilities")
        return capabilities
    }
}
