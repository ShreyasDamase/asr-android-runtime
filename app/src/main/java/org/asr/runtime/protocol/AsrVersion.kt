package org.asr.runtime.protocol

/**
 * ASR protocol constants.
 * FROZEN at v1.0 — header structure changes require a protocol version bump.
 *
 * Header layout (28 bytes, Little Endian throughout):
 *   [0]    MAGIC_0        0xA5
 *   [1]    MAGIC_1        0xAA
 *   [2]    version        UInt8
 *   [3]    topic_id       UInt8  (see TopicId)
 *   [4]    flags          UInt8  (see PacketFlags)
 *   [5]    device_id      UInt8  (0-255)
 *   [6-7]  reserved       UInt16 (always 0x0000)
 *   [8-15] timestamp_ns   UInt64 (SystemClock.elapsedRealtimeNanos)
 *   [16-19] seq           UInt32 (per-topic sequence number)
 *   [20-23] payload_len   UInt32 (bytes in payload)
 *   [24-27] crc32         UInt32 (CRC32 over bytes [0..23] + payload)
 *
 * Payload layout:
 *   [0]    payload_version UInt8 (allows independent payload evolution)
 *   [1..]  payload data
 */
object AsrVersion {
    const val PROTOCOL_VERSION: Byte = 0x01
    const val MAGIC_0: Byte = 0xA5.toByte()
    const val MAGIC_1: Byte = 0xAA.toByte()

    /** Fixed header size in bytes. */
    const val HEADER_SIZE = 28

    /** Payload version for all v1.0 payloads. */
    const val PAYLOAD_VERSION: Byte = 0x01

    const val RUNTIME_VERSION = "1.0.0"

    /** Specification version for ASR Protocol. */
    const val SPECIFICATION_VERSION = "1.0.0"
}
