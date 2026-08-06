package org.asr.runtime.common

/**
 * Contract for data transmission channels in ASR.
 *
 * Transport is a dumb byte pipe. It has no knowledge of:
 * - Camera, IMU, GPS, or any sensor type
 * - TopicId semantics
 * - ROS topic names
 *
 * It only moves complete [org.asr.runtime.protocol.AsrPacket] byte arrays.
 */
interface Transport {
    /** Short identifier for logging (e.g. "tcp", "log"). */
    val type: String

    /** True only when a live connection is confirmed. */
    val isConnected: Boolean

    /** Establish the connection. Implementations should be non-blocking. */
    fun connect()

    /**
     * Transmit a complete, pre-encoded packet byte array.
     *
     * The [packet] MUST be a fully encoded AsrPacket (28-byte header + payload).
     * Transport never inspects the content — it only forwards bytes.
     *
     * Back-pressure policy (TCP): DROP_OLDEST when queue is full.
     * This prevents memory exhaustion when the consumer is slower than the producer.
     */
    fun send(packet: ByteArray)

    /** Close the connection and release underlying resources. */
    fun disconnect()
}
