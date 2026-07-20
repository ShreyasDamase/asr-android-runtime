package org.asr.runtime.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent

/**
 * Manages environmental sensors.
 */
class EnvironmentManager(context: Context) : BaseSensorManager(context, "environment", listOf(
    Sensor.TYPE_LIGHT,
    Sensor.TYPE_PROXIMITY,
    Sensor.TYPE_PRESSURE,
    Sensor.TYPE_AMBIENT_TEMPERATURE,
    Sensor.TYPE_RELATIVE_HUMIDITY
)) {

    override fun onSensorChanged(event: SensorEvent) {
        totalEvents.incrementAndGet()
    }
}
