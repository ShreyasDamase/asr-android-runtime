package org.asr.runtime.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import org.asr.runtime.common.SensorModule

/**
 * Manages battery telemetry for ASR.
 */
class BatteryManager(private val context: Context) : SensorModule {

    override val id: String = "battery"

    private var _level: Float = -1f
    private var _isCharging: Boolean = false
    private var _temperature: Float = -1f
    private var _voltage: Int = -1

    val level: Float get() = _level
    val isCharging: Boolean get() = _isCharging
    val temperature: Float get() = _temperature
    val voltage: Int get() = _voltage

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            _level = level * 100 / scale.toFloat()

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            _isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                          status == BatteryManager.BATTERY_STATUS_FULL

            _temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f
            _voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)

            Log.d("AsrBattery", "[BATTERY] level=$_level% charging=$_isCharging temp=$_temperature°C voltage=$_voltage mV")
        }
    }

    override fun start() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        context.registerReceiver(batteryReceiver, filter)
        Log.i("AsrBattery", "Battery monitor started")
    }

    override fun stop() {
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // ignore
        }
        Log.i("AsrBattery", "Battery monitor stopped")
    }

    override fun release() {
        stop()
    }
}
