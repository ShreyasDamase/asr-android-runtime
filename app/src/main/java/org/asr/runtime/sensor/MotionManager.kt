package org.asr.runtime.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent

/**
 * Manages motion detection and step tracking.
 */
class MotionManager(
    context: Context,
    private val onRosSteps: ((Int) -> Unit)? = null
) : BaseSensorManager(context, "motion", listOf(
    Sensor.TYPE_STEP_COUNTER,
    Sensor.TYPE_STEP_DETECTOR,
    Sensor.TYPE_SIGNIFICANT_MOTION,
    Sensor.TYPE_STATIONARY_DETECT,
    Sensor.TYPE_MOTION_DETECT
)) {

    override fun onSensorChanged(event: SensorEvent) {
        totalEvents.incrementAndGet()
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) {
            val steps = event.values[0].toInt()
            onRosSteps?.invoke(steps)
        }
    }
}
