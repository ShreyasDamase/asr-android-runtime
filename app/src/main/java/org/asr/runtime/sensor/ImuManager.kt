package org.asr.runtime.sensor

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.SensorEvent
import org.asr.runtime.protocol.AsrPacket
import org.asr.runtime.protocol.PacketSerializer
import org.asr.runtime.protocol.TopicId
import org.asr.runtime.transport.TransportManager
import java.util.concurrent.atomic.AtomicInteger

/**
 * IMU hardware driver.
 *
 * Subscribes to all inertial sensors and publishes a single IMU packet
 * containing fused accel, gyro, gravity, and rotation data.
 *
 * Sensor fusion approach: each sensor event is stored in the latest-value
 * buffer. A packet is assembled and published on every ACCELEROMETER event,
 * which arrives most frequently, ensuring packets reflect the latest state
 * of all sensors at time of publish.
 *
 * Full quaternion (w, x, y, z) is always published — see PacketSerializer.imu().
 */
class ImuManager(
    context: Context,
    private val transportManager: TransportManager,
    private val deviceId: Byte,
    private val onRosSample: ((FloatArray, FloatArray, FloatArray, Long) -> Unit)? = null
) : BaseSensorManager(context, "imu", listOf(
    Sensor.TYPE_ACCELEROMETER,
    Sensor.TYPE_GYROSCOPE,
    Sensor.TYPE_GRAVITY,
    Sensor.TYPE_ROTATION_VECTOR,
    Sensor.TYPE_GAME_ROTATION_VECTOR,
    Sensor.TYPE_LINEAR_ACCELERATION
)) {
    private val seq = AtomicInteger(0)

    // Some phones have no Rotation Vector sensor, only Game Rotation Vector (not referenced to north).
    private val hasRotationVector =
        (context.getSystemService(Context.SENSOR_SERVICE) as SensorManager)
            .getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null

    // Latest values — updated on each sensor event, published on ACCELEROMETER
    // Sensor events all arrive on one thread and consumers read synchronously, so these
    // arrays are reused to avoid allocating at the IMU rate.
    private val accel    = FloatArray(3)
    private val gyro     = FloatArray(3)
    private val gravity  = FloatArray(3)
    private val rotation = FloatArray(4)  // [x, y, z, w] Android convention
    @Volatile private var lastTs   = 0L

    override fun onSensorChanged(event: SensorEvent) {
        totalEvents.incrementAndGet()
        when (event.sensor.type) {
            Sensor.TYPE_ACCELEROMETER      -> {
                event.values.copyInto(accel, 0, 0, 3)
                lastTs = event.timestamp // Use hardware sensor timestamp
                publishPacket()
                onRosSample?.invoke(accel, gyro, rotation, lastTs)
            }
            Sensor.TYPE_GYROSCOPE          -> event.values.copyInto(gyro, 0, 0, 3)
            Sensor.TYPE_GRAVITY            -> event.values.copyInto(gravity, 0, 0, 3)
            Sensor.TYPE_ROTATION_VECTOR    -> updateRotation(event.values)
            Sensor.TYPE_GAME_ROTATION_VECTOR -> if (!hasRotationVector) updateRotation(event.values)
            // LINEAR_ACCELERATION not in packet v1 payload — reserved for payload v2
        }
    }

    /** The scalar component is optional in rotation sensor events; derive it when it is missing. */
    private fun updateRotation(values: FloatArray) {
        val x = values[0]
        val y = values[1]
        val z = values[2]
        rotation[0] = x
        rotation[1] = y
        rotation[2] = z
        rotation[3] = if (values.size > 3) values[3]
        else kotlin.math.sqrt(maxOf(0f, 1f - x * x - y * y - z * z))
    }

    private fun publishPacket() {
        val payload = PacketSerializer.imu(
            accel    = accel,
            gyro     = gyro,
            gravity  = gravity,
            rotation = rotation   // PacketSerializer handles w,x,y,z reordering
        )
        val packet = AsrPacket.encode(
            topicId     = TopicId.IMU.value,
            seq         = seq.getAndIncrement(),
            payload     = payload,
            deviceId    = deviceId,
            timestampNs = lastTs
        )
        transportManager.send(packet)
    }

    override fun capabilities() = listOf(
        "accelerometer", "gyroscope", "rotation_vector", "gravity", "linear_acceleration"
    )

    override fun status(): Map<String, Any> = mapOf(
        "id"     to id,
        "state"  to state.name,
        "events" to totalEvents.get(),
        "seq"    to seq.get()
    )
}
