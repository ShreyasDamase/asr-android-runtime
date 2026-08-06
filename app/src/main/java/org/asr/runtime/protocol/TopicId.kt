package org.asr.runtime.protocol

/**
 * Frozen TopicId registry for ASR v1.0.
 *
 * FROZEN — new IDs require a protocol version bump to 0x02.
 * The ROS bridge maps each TopicId to exactly one ROS entity.
 * Android never uses ROS topic name strings.
 */
enum class TopicId(val value: Byte) {
    CAMERA_FRAME (0x01),
    IMU          (0x02),
    GPS          (0x03),
    BATTERY      (0x04),
    AUDIO        (0x05),
    ENVIRONMENT  (0x06),
    CAPABILITIES (0x07),  // Handshake — sent immediately on connect()
    COMMAND      (0x08),  // ROS → Android
    ACK          (0x09),  // Android → ROS (response to COMMAND)
    ERROR        (0x0A),
    DIAGNOSTICS  (0x0B),
    MOTION       (0x0C),
    HEARTBEAT    (0x0D);

    companion object {
        fun fromValue(v: Byte): TopicId? = entries.firstOrNull { it.value == v }
    }
}
