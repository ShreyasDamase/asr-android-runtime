package org.asr.runtime.protocol

import android.os.SystemClock
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32

/**
 * Decoded representation of an AsrPacket after parsing from bytes.
 *
 * @param version       Protocol version byte from header.
 * @param topicId       Topic identifier byte (see [TopicId]).
 * @param flags         Packet flags (see [PacketFlags]).
 * @param deviceId      Originating device ID (0–255).
 * @param timestampNs   Monotonic timestamp in nanoseconds (elapsedRealtimeNanos).
 * @param seq           Per-topic sequence number (bridge detects gaps).
 * @param payload       Raw payload bytes (first byte is payload_version).
 * @param crcValid      True if the computed CRC32 matches the stored CRC.
 */
data class AsrFrame(
    val version: Byte,
    val topicId: Byte,
    val flags: Byte,
    val deviceId: Byte,
    val timestampNs: Long,
    val seq: Int,
    val payload: ByteArray,
    val crcValid: Boolean
) {
    override fun equals(other: Any?) = other is AsrFrame &&
            topicId == other.topicId && seq == other.seq && crcValid == other.crcValid
    override fun hashCode() = 31 * topicId + seq
}

/**
 * Encodes and decodes ASR binary packets.
 *
 * This object is stateless — it has no sequence numbers, no counters.
 * Sequence numbers are owned by each module.
 *
 * Wire format: see [AsrVersion] header documentation.
 * CRC32 is computed over header[0..23] + payload.
 */
object AsrPacket {

    /**
     * Encodes a packet into a complete binary frame ready for transmission.
     *
     * @param topicId    [TopicId] byte identifying the data type.
     * @param seq        Monotonically increasing sequence number (caller-managed).
     * @param payload    Payload bytes — first byte MUST be the payload_version.
     * @param flags      Packet flags (default [PacketFlags.NONE]).
     * @param deviceId   Device identifier byte.
     * @return           Complete packet: 28-byte header + payload.
     */
    fun encode(
        topicId: Byte,
        seq: Int,
        payload: ByteArray,
        flags: Byte = PacketFlags.NONE,
        deviceId: Byte = 0
    ): ByteArray {
        val timestampNs = SystemClock.elapsedRealtimeNanos()
        val totalSize = AsrVersion.HEADER_SIZE + payload.size
        val buf = ByteBuffer.allocate(totalSize).order(ByteOrder.LITTLE_ENDIAN)

        // Header bytes [0..23] — everything before CRC
        buf.put(AsrVersion.MAGIC_0)                 // [0]
        buf.put(AsrVersion.MAGIC_1)                 // [1]
        buf.put(AsrVersion.PROTOCOL_VERSION)         // [2]
        buf.put(topicId)                             // [3]
        buf.put(flags)                               // [4]
        buf.put(deviceId)                            // [5]
        buf.putShort(0)                              // [6-7] reserved
        buf.putLong(timestampNs)                     // [8-15]
        buf.putInt(seq)                              // [16-19]
        buf.putInt(payload.size)                     // [20-23]

        // CRC32 over header[0..23] + payload
        val headerPart = buf.array().copyOf(24)
        val crc = computeCrc(headerPart, payload)
        buf.putInt(crc.toInt())                      // [24-27]

        // Payload
        buf.put(payload)

        return buf.array()
    }

    /**
     * Decodes a raw byte array into an [AsrFrame].
     *
     * Returns null if the byte array is too short or magic bytes don't match.
     * Always check [AsrFrame.crcValid] before processing the payload.
     */
    fun decode(bytes: ByteArray): AsrFrame? {
        if (bytes.size < AsrVersion.HEADER_SIZE) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

        if (buf.get() != AsrVersion.MAGIC_0 || buf.get() != AsrVersion.MAGIC_1) return null

        val version  = buf.get()
        val topicId  = buf.get()
        val flags    = buf.get()
        val deviceId = buf.get()
        buf.short                      // skip reserved
        val timestampNs = buf.long
        val seq         = buf.int
        val payloadLen  = buf.int
        val crcStored   = buf.int.toLong() and 0xFFFFFFFFL

        if (bytes.size < AsrVersion.HEADER_SIZE + payloadLen) return null

        val payload = bytes.copyOfRange(AsrVersion.HEADER_SIZE, AsrVersion.HEADER_SIZE + payloadLen)
        val headerPart = bytes.copyOf(24)
        val crcCalc = computeCrc(headerPart, payload)

        return AsrFrame(
            version     = version,
            topicId     = topicId,
            flags       = flags,
            deviceId    = deviceId,
            timestampNs = timestampNs,
            seq         = seq,
            payload     = payload,
            crcValid    = (crcCalc == crcStored)
        )
    }

    /**
     * Computes CRC32 over header bytes [0..23] concatenated with payload.
     * The CRC field itself (bytes [24..27]) is NOT included.
     */
    fun computeCrc(header0to23: ByteArray, payload: ByteArray): Long {
        val crc = CRC32()
        crc.update(header0to23)
        crc.update(payload)
        return crc.value
    }
}
