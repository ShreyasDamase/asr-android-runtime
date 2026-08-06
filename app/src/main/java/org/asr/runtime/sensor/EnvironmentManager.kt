package org.asr.runtime.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import org.asr.runtime.protocol.AsrPacket
import org.asr.runtime.protocol.AsrVersion
import org.asr.runtime.protocol.TopicId
import org.asr.runtime.transport.TransportManager
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Environment sensor driver.
 *
 * Publishes an ENVIRONMENT packet on every sensor event. Each environment
 * sensor is published individually because they arrive at very different rates
 * (pressure: ~50Hz, light: ~15Hz, humidity: <1Hz).
 *
 * Environment payload — payload_version=0x01:
 *   [0]     payload_version  UInt8
 *   [1]     sensor_type      UInt8  (Android Sensor.TYPE_*)
 *   [2-5]   value            Float32 LE (primary value for this sensor)
 *   [6-9]   reserved         Float32 (0.0 — available for future extension)
 */
class EnvironmentManager(
    context: Context,
    private val transportManager: TransportManager,
    private val deviceId: Byte
) : BaseSensorManager(context, "environment", listOf(
    Sensor.TYPE_LIGHT,
    Sensor.TYPE_PRESSURE,
    Sensor.TYPE_AMBIENT_TEMPERATURE,
    Sensor.TYPE_RELATIVE_HUMIDITY
)) {
    private val seq = AtomicInteger(0)

    override fun onSensorChanged(event: SensorEvent) {
        totalEvents.incrementAndGet()
        val payload = buildPayload(event.sensor.type, event.values[0])
        val packet = AsrPacket.encode(
            topicId  = TopicId.ENVIRONMENT.value,
            seq      = seq.getAndIncrement(),
            payload  = payload,
            deviceId = deviceId
        )
        transportManager.send(packet)
    }

    private fun buildPayload(sensorType: Int, value: Float): ByteArray =
        ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN).apply {
            put(AsrVersion.PAYLOAD_VERSION)
            put(sensorType.toByte())
            putFloat(value)
            putFloat(0f)    // reserved
        }.array()

    override fun capabilities() =
        listOf("light", "pressure", "ambient_temperature", "relative_humidity")
}
