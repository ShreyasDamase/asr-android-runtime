package org.asr.runtime.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent

/**
 * Manages Inertial Measurement Unit (IMU) sensors.
 */
class ImuManager(context: Context) : BaseSensorManager(context, "imu", listOf(
    Sensor.TYPE_ACCELEROMETER,
    Sensor.TYPE_GYROSCOPE,
    Sensor.TYPE_GRAVITY,
    Sensor.TYPE_ROTATION_VECTOR,
    Sensor.TYPE_LINEAR_ACCELERATION
)) {

    override fun onSensorChanged(event: SensorEvent) {
        totalEvents.incrementAndGet()
        // Telemetry updates will go here in v0.2
    }
}
