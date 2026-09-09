package org.asr.runtime.battery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import org.asr.runtime.protocol.AsrPacket
import org.asr.runtime.protocol.PacketSerializer
import org.asr.runtime.protocol.TopicId
import org.asr.runtime.transport.TransportManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages battery telemetry for ASR.
 */
class BatteryManager(
    private val context: Context,
    private val transportManager: TransportManager,
    private val deviceId: Byte,
    private val onRosBattery: ((Float, Float, Float, Boolean) -> Unit)? = null
) : SensorModule {

    override val id: String = "battery"
    override var state: ModuleState = ModuleState.STOPPED

    private var _level: Float = -1f
    private var _isCharging: Boolean = false
    private var _temperature: Float = -1f
    private var _voltage: Int = -1
    private val seq = AtomicInteger(0)

    val level: Float get() = _level
    val isCharging: Boolean get() = _isCharging
    val temperature: Float get() = _temperature
    val voltage: Int get() = _voltage

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            // Guard against a missing/zero scale extra, which would otherwise
            // produce NaN, +Inf, or a negative percentage.
            _level = if (level >= 0 && scale > 0) level * 100f / scale else -1f

            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            _isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                          status == BatteryManager.BATTERY_STATUS_FULL

            _temperature = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f
            _voltage = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1)

            Log.d("AsrBattery", "[BATTERY] level=$_level% charging=$_isCharging temp=$_temperature°C voltage=$_voltage mV")

            // ── Send Battery packet ──
            val payload = PacketSerializer.battery(_level, _temperature, _voltage.toFloat(), _isCharging)
            val packet = AsrPacket.encode(
                topicId = TopicId.BATTERY.value,
                seq = seq.getAndIncrement(),
                payload = payload,
                deviceId = deviceId
            )
            transportManager.send(packet)
            onRosBattery?.invoke(_level, _temperature, _voltage.toFloat(), _isCharging)
        }
    }

    override fun start() {
        state = ModuleState.STARTING
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(batteryReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(batteryReceiver, filter)
        }
        Log.i("AsrBattery", "Battery monitor started")
        state = ModuleState.RUNNING
    }

    override fun stop() {
        state = ModuleState.STOPPING
        try {
            context.unregisterReceiver(batteryReceiver)
        } catch (e: Exception) {
            // ignore
        }
        Log.i("AsrBattery", "Battery monitor stopped")
        state = ModuleState.STOPPED
    }

    override fun release() {
        stop()
    }
}
