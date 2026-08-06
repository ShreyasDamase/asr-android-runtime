package org.asr.runtime.protocol

/**
 * Packet flags — single byte in header position [4].
 *
 * In v1.0 all packets use NONE.
 * COMPRESSED and FRAGMENTED are reserved for v1.1.
 */
object PacketFlags {
    const val NONE          : Byte = 0x00
    /** Payload is compressed (lz4 or gzip — algorithm in payload header). */
    const val COMPRESSED    : Byte = 0x01
    /** This is a fragment of a larger logical packet. */
    const val FRAGMENTED    : Byte = 0x02
    /** Payload contains an error description (used with ERROR topic). */
    const val ERROR         : Byte = 0x04
    /** Last fragment of a fragmented frame. */
    const val END_OF_FRAME  : Byte = 0x08
}

/** Camera encoding identifiers used in CameraFrame payload. */
enum class CameraEncoding(val id: Byte) {
    RAW_YUV (0x00),
    JPEG    (0x01),
    H264    (0x02)   // v1.1 only
}
