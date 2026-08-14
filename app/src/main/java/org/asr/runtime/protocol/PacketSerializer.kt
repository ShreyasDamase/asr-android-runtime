package org.asr.runtime.protocol

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Stateless serializer: converts module data into versioned payload ByteArrays.
 *
 * Protocol specification v1.0.0 rules:
 * - Payloads are TIGHTLY PACKED. No padding, no alignment.
 * - All multi-byte values are LITTLE ENDIAN.
 * - This object has NO state — no sequence numbers, no counters, no references.
 * - Every payload starts with [AsrVersion.PAYLOAD_VERSION] as byte [0].
 * - Each module owns its own sequence counter (AtomicInteger).
 *
 * Payload versions allow each payload type to evolve independently of the
 * packet header protocol version.
 */
object PacketSerializer {

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun buf(size: Int): ByteBuffer =
        ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    // ── IMU ─────────────────────────────────────────────────────────────────

    /**
     * IMU payload — payload_version=0x01, 53 bytes total.
     *
     * [0]     payload_version  UInt8
     * [1-12]  accel (x,y,z)    3×Float32 m/s²
     * [13-24] gyro  (x,y,z)    3×Float32 rad/s
     * [25-36] gravity (x,y,z)  3×Float32 m/s²
     * [37-52] rotation (w,x,y,z) 4×Float32 quaternion (FULL — all 4 components)
     *
     * @param accel   FloatArray[3]  — accelerometer values
     * @param gyro    FloatArray[3]  — gyroscope values
     * @param gravity FloatArray[3]  — gravity vector
     * @param rotation FloatArray[4] — rotation quaternion [w, x, y, z]
     */
    fun imu(
        accel: FloatArray,
        gyro: FloatArray,
        gravity: FloatArray,
        rotation: FloatArray
    ): ByteArray = buf(53).apply {
        put(AsrVersion.PAYLOAD_VERSION)
        accel.take(3).forEach   { putFloat(it) }
        gyro.take(3).forEach    { putFloat(it) }
        gravity.take(3).forEach { putFloat(it) }
        // Full quaternion: w, x, y, z
        putFloat(if (rotation.size > 3) rotation[3] else 1f)  // w (rotation[3] in Android API)
        putFloat(if (rotation.size > 0) rotation[0] else 0f)  // x
        putFloat(if (rotation.size > 1) rotation[1] else 0f)  // y
        putFloat(if (rotation.size > 2) rotation[2] else 0f)  // z
    }.array()

    // ── GPS ─────────────────────────────────────────────────────────────────

    /**
     * GPS payload — payload_version=0x01, 33 bytes total.
     *
     * [0]     payload_version  UInt8
     * [1-8]   latitude         Float64 degrees
     * [9-16]  longitude        Float64 degrees
     * [17-20] altitude         Float32 meters ASL
     * [21-24] accuracy         Float32 meters (1-sigma horizontal)
     * [25-28] bearing          Float32 degrees (0=North, clockwise)
     * [29-32] speed            Float32 m/s
     */
    fun gps(
        lat: Double,
        lon: Double,
        alt: Float,
        accuracy: Float,
        bearing: Float,
        speed: Float
    ): ByteArray = buf(33).apply {
        put(AsrVersion.PAYLOAD_VERSION)
        putDouble(lat)
        putDouble(lon)
        putFloat(alt)
        putFloat(accuracy)
        putFloat(bearing)
        putFloat(speed)
    }.array()

    // ── Battery ──────────────────────────────────────────────────────────────

    /**
     * Battery payload — payload_version=0x01, 17 bytes total.
     *
     * [0]     payload_version  UInt8
     * [1-4]   level            Float32 (0.0–100.0 %)
     * [5-8]   temperature      Float32 (°C - Celsius)
     * [9-12]  voltage          Float32 (mV - Millivolts)
     * [13-16] is_charging      UInt32 (0=No, 1=Yes)
     */
    fun battery(
        level: Float,
        temp: Float,
        voltage: Float,
        isCharging: Boolean
    ): ByteArray = buf(17).apply {
        put(AsrVersion.PAYLOAD_VERSION)
        putFloat(level)
        putFloat(temp)
        putFloat(voltage)
        putInt(if (isCharging) 1 else 0)
    }.array()

    // ── Audio ─────────────────────────────────────────────────────────────────

    /**
     * Audio Level / Diagnostics payload — payload_version=0x01, 18 bytes total.
     * [0]     payload_version  UInt8
     * [1-4]   rms_level        Float32 (0.0 .. 1.0)
     * [5-8]   peak_level       Float32 (0.0 .. 1.0)
     * [9-12]  noise_floor_dbfs Float32 (-120.0 .. 0.0)
     * [13]    voice_active     UInt8 (0 or 1)
     * [14-17] sample_rate_hz   Int32 (16000)
     */
    fun audioLevel(
        rmsLevel: Float,
        peakLevel: Float,
        noiseFloorDbfs: Float,
        voiceActive: Boolean,
        sampleRateHz: Int = 16000
    ): ByteArray = buf(18).apply {
        put(AsrVersion.PAYLOAD_VERSION)
        putFloat(rmsLevel)
        putFloat(peakLevel)
        putFloat(noiseFloorDbfs)
        put(if (voiceActive) 1.toByte() else 0.toByte())
        putInt(sampleRateHz)
    }.array()

    /**
     * Audio Raw PCM payload — payload_version=0x01, variable length.
     *
     * Data format: Signed PCM, 16-bit, Little Endian, Mono, 16 kHz.
     *
     * [0]     payload_version  UInt8
     * [1-2]   sample_count     UInt16 (number of Int16 samples)
     * [3..]   pcm_samples      sample_count × Int16 LE
     */
    fun audio(pcm: ShortArray, count: Int): ByteArray = buf(1 + 2 + count * 2).apply {
        put(AsrVersion.PAYLOAD_VERSION)
        putShort(count.toShort())
        (0 until count).forEach { putShort(pcm[it]) }
    }.array()

    // ── Camera Frame ─────────────────────────────────────────────────────────

    /**
     * Camera frame payload — payload_version=0x01, variable length.
     *
     * [0]     payload_version  UInt8
     * [1-2]   width            UInt16 pixels
     * [3-4]   height           UInt16 pixels
     * [5]     encoding         UInt8  (CameraEncoding.id)
     * [6]     quality          UInt8  (JPEG quality 0–100; 0 if not JPEG)
     * [7-10]  frame_id         UInt32 (monotonically increasing frame counter)
     * [11-18] exposure_time_ns UInt64 (sensor exposure in nanoseconds)
     * [19-20] iso              UInt16 (ISO/gain value; 0 if unavailable)
     * [21-22] reserved         UInt16
     * [23-30] sensor_ts_ns     UInt64 (hardware sensor timestamp, ns)
     * [31..]  frame_data       compressed/raw frame bytes
     */
    fun cameraFrame(
        width: Int,
        height: Int,
        encoding: CameraEncoding,
        quality: Int,
        frameId: Long,
        exposureTimeNs: Long,
        iso: Int,
        sensorTimestampNs: Long,
        data: ByteArray
    ): ByteArray = buf(1 + 2 + 2 + 1 + 1 + 4 + 8 + 2 + 2 + 8 + data.size).apply {
        put(AsrVersion.PAYLOAD_VERSION)
        putShort(width.toShort())
        putShort(height.toShort())
        put(encoding.id)
        put(quality.toByte())
        putInt(frameId.toInt())
        putLong(exposureTimeNs)
        putShort(iso.toShort())
        putShort(0)               // reserved
        putLong(sensorTimestampNs)
        put(data)
    }.array()

    // ── Capabilities ──────────────────────────────────────────────────────────

    /**
     * Builds the capabilities JSON payload for the handshake CAPABILITIES packet.
     * Called once on transport connect(). Returns UTF-8 encoded JSON bytes.
     */
    fun capabilities(context: Context, deviceId: Byte): ByteArray {
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

        fun hasSensor(type: Int) = sm.getDefaultSensor(type) != null

        val json = buildString {
            append("""{"payload_version":1""")
            append(""","asr_version":"${AsrVersion.RUNTIME_VERSION}"""")
            append(""","protocol_version":${AsrVersion.PROTOCOL_VERSION.toInt() and 0xFF}""")
            append(""","device_id":${deviceId.toInt() and 0xFF}""")
            append(""","android_api":${Build.VERSION.SDK_INT}""")
            append(""","device_model":"${Build.MODEL}"""")
            append(""","modules":{""")

            // Camera — actual resolution list deferred; bridge should query via COMMAND
            append(""""camera":{"back":true,"front":true,""")
            append(""""fps":[30,60],"resolutions":["640x480","1280x720","1920x1080"],""")
            append(""""encodings":["RAW_YUV","JPEG"]}""")

            // IMU
            append(""","imu":{""")
            append(""""accelerometer":${hasSensor(Sensor.TYPE_ACCELEROMETER)}""")
            append(""","gyroscope":${hasSensor(Sensor.TYPE_GYROSCOPE)}""")
            append(""","rotation_vector":${hasSensor(Sensor.TYPE_ROTATION_VECTOR)}""")
            append(""","gravity":${hasSensor(Sensor.TYPE_GRAVITY)}""")
            append(""","linear_acceleration":${hasSensor(Sensor.TYPE_LINEAR_ACCELERATION)}""")
            append(""","max_rate_hz":500}""")

            // GPS
            val hasGps = context.packageManager.hasSystemFeature(
                android.content.pm.PackageManager.FEATURE_LOCATION_GPS
            )
            append(""","gps":{"available":$hasGps,"provider":"gps"}""")

            // Audio
            append(""","audio":{"microphone":true,"sample_rate_hz":16000,"channels":1,"encoding":"PCM_16BIT"}""")

            // Environment
            append(""","environment":{""")
            append(""""pressure":${hasSensor(Sensor.TYPE_PRESSURE)}""")
            append(""","light":${hasSensor(Sensor.TYPE_LIGHT)}""")
            append(""","humidity":${hasSensor(Sensor.TYPE_RELATIVE_HUMIDITY)}""")
            append(""","ambient_temperature":${hasSensor(Sensor.TYPE_AMBIENT_TEMPERATURE)}""")
            append("}")

            append(""","battery":true,"speaker":true}""")
            append("}")
        }
        return json.toByteArray(Charsets.UTF_8)
    }

    // ── Command / ACK ─────────────────────────────────────────────────────────

    /**
     * ACK payload for responding to COMMAND packets.
     *
     * @param reqId   The request ID from the COMMAND packet.
     * @param status  "ok" or "error".
     * @param message Optional human-readable message (present on error).
     */
    fun ack(reqId: Int, status: String, message: String? = null): ByteArray =
        buildString {
            append("""{"payload_version":1,"req_id":$reqId,"status":"$status"""")
            if (message != null) append(""","message":"${message.replace("\"", "'")}"""")
            append("}")
        }.toByteArray(Charsets.UTF_8)

    /**
     * ERROR payload for reporting runtime errors.
     */
    fun error(code: Int, message: String): ByteArray =
        """{"payload_version":1,"code":$code,"message":"${message.replace("\"", "'")}"}"""
            .toByteArray(Charsets.UTF_8)

    /**
     * HEARTBEAT payload — payload_version=0x01, 9 bytes total.
     */
    fun heartbeat(uptimeMs: Long): ByteArray = buf(9).apply {
        put(AsrVersion.PAYLOAD_VERSION)
        putLong(uptimeMs)
    }.array()
}
