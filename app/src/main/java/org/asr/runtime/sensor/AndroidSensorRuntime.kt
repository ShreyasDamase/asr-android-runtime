package org.asr.runtime.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import org.asr.runtime.common.SensorModule
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong

class AndroidSensorRuntime(context: Context) : SensorEventListener, SensorModule {

    override val id: String = "sensors"

    companion object {
        private const val TAG = "AsrSensors"
        private const val DEFAULT_SAMPLING_US = 20_000
        private const val LOG_INTERVAL_EVENTS = 250L
    }

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val availableSensors = sensorManager.getSensorList(Sensor.TYPE_ALL)
    private val totalEvents = AtomicLong(0L)
    private val failedSensorNames = mutableListOf<String>()

    @Volatile
    var snapshot = AndroidSensorSnapshot(availableSensors = availableSensors.size)
        private set

    override fun start() {
        startAll()
    }

    fun startAll(samplingPeriodUs: Int = DEFAULT_SAMPLING_US) {
        failedSensorNames.clear()
        var activeCount = 0

        availableSensors.forEach { sensor ->
            try {
                val registered = sensorManager.registerListener(this, sensor, samplingPeriodUs)
                if (registered) {
                    activeCount++
                } else {
                    failedSensorNames.add(sensor.name)
                }
            } catch (e: SecurityException) {
                failedSensorNames.add("${sensor.name} permission=${e.javaClass.simpleName}")
            } catch (e: RuntimeException) {
                failedSensorNames.add("${sensor.name} error=${e.javaClass.simpleName}")
            }
        }

        snapshot = snapshot.copy(
            availableSensors = availableSensors.size,
            activeSensors = activeCount,
            failedSensors = failedSensorNames.toList()
        )

        Log.i(
            TAG,
            "Android sensors started active=$activeCount available=${availableSensors.size} failed=${failedSensorNames.size}"
        )
        logSensorCatalog()
    }

    override fun stop() {
        sensorManager.unregisterListener(this)
        snapshot = snapshot.copy(activeSensors = 0)
        Log.i(TAG, "Android sensors stopped")
    }

    override fun release() {
        stop()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val count = totalEvents.incrementAndGet()
        val values = event.values.take(6)
        snapshot = snapshot.copy(
            totalEvents = count,
            lastSensorName = event.sensor.name,
            lastSensorType = event.sensor.stringType,
            lastValues = values,
            lastTimestampNs = event.timestamp
        )

        if (count % LOG_INTERVAL_EVENTS == 0L) {
            Log.d(
                TAG,
                "[SENSOR] events=$count sensor=\"${event.sensor.name}\" type=${event.sensor.stringType} values=${
                    values.joinToString(prefix = "[", postfix = "]") {
                        String.format(Locale.US, "%.3f", it)
                    }
                }"
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        sensor ?: return
        Log.d(TAG, "[SENSOR_ACCURACY] sensor=\"${sensor.name}\" type=${sensor.stringType} accuracy=$accuracy")
    }

    private fun logSensorCatalog() {
        availableSensors.forEachIndexed { index, sensor ->
            Log.i(
                TAG,
                "[SENSOR_INFO] index=$index name=\"${sensor.name}\" type=${sensor.stringType}" +
                        " vendor=\"${sensor.vendor}\" wakeup=${sensor.isWakeUpSensor}" +
                        " min_delay_us=${sensor.minDelay} max_delay_us=${sensor.maxDelay}"
            )
        }
    }
}
