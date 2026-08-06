package org.asr.runtime.transport

import android.util.Log
import org.asr.runtime.common.Transport
import org.asr.runtime.core.DevModeConfig
import org.asr.runtime.protocol.AsrVersion
import org.asr.runtime.protocol.TopicId
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Debug transport that logs packet metadata to Logcat.
 *
 * Never logs the raw payload bytes to avoid log spam.
 * Logs topic ID, sequence number, payload size, and CRC.
 *
 * This transport is always "connected" — it never fails.
 * Useful as a fallback to confirm data is flowing when TCP is unavailable.
 */
class LogTransport : Transport {

    override val type: String = "log"

    override val isConnected: Boolean = true

    override fun connect() {
        Log.i(TAG, "[LOG] Log transport connected (always-on)")
    }

    override fun send(packet: ByteArray) {
        if (!DevModeConfig.packetLog) return
        if (packet.size < AsrVersion.HEADER_SIZE) {
            Log.w(TAG, "[LOG] Received malformed packet (size=${packet.size})")
            return
        }
        // Parse header fields for logging — transport does NOT interpret payload
        val buf = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buf.get(); buf.get()           // magic
        buf.get()                      // version
        val topicByte = buf.get()
        buf.get()                      // flags
        val deviceId  = buf.get().toInt() and 0xFF
        buf.short                      // reserved
        buf.long                       // timestamp
        val seq       = buf.int
        val payloadLen= buf.int
        val crc       = buf.int.toLong() and 0xFFFFFFFFL

        val topicName = TopicId.fromValue(topicByte)?.name ?: "0x${topicByte.toInt().and(0xFF).toString(16)}"
        Log.d(TAG, "[PACKET] topic=$topicName seq=$seq size=${packet.size} payload=$payloadLen crc=0x${crc.toString(16).uppercase()} dev=$deviceId")
    }

    override fun disconnect() {
        Log.i(TAG, "[LOG] Log transport disconnected")
    }

    companion object {
        private const val TAG = "AsrLogTransport"
    }
}
