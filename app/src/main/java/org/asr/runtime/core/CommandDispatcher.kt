package org.asr.runtime.core

import android.util.Log
import org.asr.runtime.protocol.AsrPacket
import org.asr.runtime.protocol.PacketSerializer
import org.asr.runtime.protocol.TopicId
import org.asr.runtime.transport.TransportManager
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Routes inbound COMMAND packets to registered [CommandHandler] instances.
 *
 * This dispatcher uses a registry pattern — there is no switch/when block
 * over command names. Each module registers its own handlers at startup.
 *
 * Command packet JSON format:
 *   { "cmd": "camera.set_fps", "params": { "fps": 60 }, "req_id": 1 }
 *
 * ACK packet JSON format:
 *   { "req_id": 1, "status": "ok" }
 *   { "req_id": 1, "status": "error", "message": "..." }
 */
class CommandDispatcher(
    private val transportManager: TransportManager,
    private val deviceId: Byte
) {
    private val handlers = mutableMapOf<String, CommandHandler>()
    private val ackSeq = AtomicInteger(0)

    /**
     * Register a command handler. Call this during AsrService.onCreate() for each module.
     * If a handler for [handler.command] already exists, it will be replaced.
     */
    fun register(handler: CommandHandler) {
        handlers[handler.command] = handler
        Log.d(TAG, "[CMD] Registered handler for '${handler.command}'")
    }

    /**
     * Dispatches a raw AsrPacket byte array received from a transport read loop.
     *
     * - Validates CRC (drops silently on failure, increments counter)
     * - Validates topic is COMMAND
     * - Parses JSON and routes to registered handler
     * - Always sends an ACK packet
     */
    fun dispatch(rawPacket: ByteArray) {
        val frame = AsrPacket.decode(rawPacket)
        if (frame == null) {
            Log.w(TAG, "[CMD] Failed to decode packet (too short or bad magic)")
            transportManager.notifyCrcFailure()
            return
        }
        if (!frame.crcValid) {
            Log.w(TAG, "[CMD] CRC failure on inbound packet — dropping")
            transportManager.notifyCrcFailure()
            return
        }
        if (frame.topicId != TopicId.COMMAND.value) return

        var reqId = -1
        try {
            val json   = JSONObject(String(frame.payload, Charsets.UTF_8))
            reqId      = json.optInt("req_id", -1)
            val cmd    = json.getString("cmd")
            val params = json.optJSONObject("params") ?: JSONObject()

            Log.i(TAG, "[CMD] '$cmd' req_id=$reqId")

            val handler = handlers[cmd]
            if (handler == null) {
                Log.w(TAG, "[CMD] No handler for '$cmd'. Registered: ${handlers.keys}")
                sendAck(reqId, "error", "Unknown command: $cmd")
                return
            }

            val result = handler.execute(params)
            if (result.isSuccess) {
                sendAck(reqId, "ok")
            } else {
                sendAck(reqId, "error", result.exceptionOrNull()?.message)
            }

        } catch (e: Exception) {
            Log.e(TAG, "[CMD] Dispatch exception (req_id=$reqId)", e)
            sendAck(reqId, "error", e.message)
        }
    }

    /** Returns all currently registered command names. */
    fun registeredCommands(): Set<String> = handlers.keys.toSet()

    private fun sendAck(reqId: Int, status: String, message: String? = null) {
        val payload = PacketSerializer.ack(reqId, status, message)
        val packet  = AsrPacket.encode(
            topicId  = TopicId.ACK.value,
            seq      = ackSeq.getAndIncrement(),
            payload  = payload,
            deviceId = deviceId
        )
        transportManager.send(packet)
    }

    companion object {
        private const val TAG = "AsrCommand"
    }
}
