package org.asr.runtime.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import java.util.concurrent.atomic.AtomicLong

/**
 * Base class for specific Android sensor groupings.
 */
abstract class BaseSensorManager(
    context: Context,
    override val id: String,
    private val sensorTypes: List<Int>
) : SensorEventListener, SensorModule {

    override var state: ModuleState = ModuleState.STOPPED

    protected val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    protected val totalEvents = AtomicLong(0L)
    
    private val TAG = "Asr${id.replaceFirstChar { it.uppercase() }}"

    override fun start() {
        state = ModuleState.STARTING
        sensorTypes.forEach { type ->
            val sensor = sensorManager.getDefaultSensor(type)
            if (sensor != null) {
                sensorManager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_FASTEST)
                Log.i(TAG, "Started sensor: ${sensor.name}")
            }
        }
        state = ModuleState.RUNNING
    }

    override fun stop() {
        state = ModuleState.STOPPING
        sensorManager.unregisterListener(this)
        Log.i(TAG, "Stopped all sensors")
        state = ModuleState.STOPPED
    }

    override fun release() {
        stop()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    abstract override fun onSensorChanged(event: SensorEvent)
}
