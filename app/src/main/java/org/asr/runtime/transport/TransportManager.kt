package org.asr.runtime.transport

import android.util.Log
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import org.asr.runtime.common.Transport
import org.asr.runtime.protocol.AsrVersion
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicLong

/**
 * Multiplexes published packets to all registered [Transport] implementations.
 *
 * Transport rules enforced here:
 * - send() is called with a complete AsrPacket byte array
 * - No topic strings, no TopicId inspection — pure byte forwarding
 * - Tracks total packets sent and drop count for diagnostics
 */
class TransportManager : SensorModule {

    override val id: String = "transport"
    override var state: ModuleState = ModuleState.STOPPED

    private val transports = mutableListOf<Transport>()

    private val _totalPacketsSent  = AtomicLong(0)
    private val _totalPacketDrops  = AtomicLong(0)
    private val _totalCrcFailures  = AtomicLong(0)
    private val _reconnectCount    = AtomicLong(0)

    val totalPacketsSent: Long  get() = _totalPacketsSent.get()
    val totalPacketDrops: Long  get() = _totalPacketDrops.get()
    val totalCrcFailures: Long  get() = _totalCrcFailures.get()
    val reconnectCount: Long    get() = _reconnectCount.get()

    fun addTransport(transport: Transport) {
        transports.add(transport)
    }

    fun isAnyConnected(): Boolean = transports.any { it.isConnected }

    /** Called by [org.asr.runtime.transport.TcpTransport] when a reconnect occurs. */
    fun notifyReconnect() {
        _reconnectCount.incrementAndGet()
    }

    /** Called by [org.asr.runtime.core.CommandDispatcher] on CRC failure. */
    fun notifyCrcFailure() {
        _totalCrcFailures.incrementAndGet()
    }

    override fun start() {
        state = ModuleState.STARTING
        transports.forEach { it.connect() }
        Log.i(TAG, "Transport manager started with ${transports.size} transport(s)")
        state = ModuleState.RUNNING
    }

    override fun stop() {
        state = ModuleState.STOPPING
        transports.forEach { it.disconnect() }
        Log.i(TAG, "Transport manager stopped")
        state = ModuleState.STOPPED
    }

    override fun release() {
        stop()
        transports.clear()
    }

    /**
     * Fan-out a pre-encoded AsrPacket to all connected transports.
     *
     * Transport never inspects the packet content — it is a pure byte pipe.
     */
    fun send(packet: ByteArray) {
        var sentToAny = false
        transports.forEach { transport ->
            if (transport.isConnected) {
                transport.send(packet)
                sentToAny = true
            }
        }
        if (sentToAny) {
            _totalPacketsSent.incrementAndGet()
        } else {
            _totalPacketDrops.incrementAndGet()
        }
    }

    override fun status(): Map<String, Any> = mapOf(
        "id"            to id,
        "state"         to state.name,
        "transports"    to transports.map { "${it.type}:${if (it.isConnected) "CONNECTED" else "DISCONNECTED"}" },
        "packets_sent"  to _totalPacketsSent.get(),
        "packet_drops"  to _totalPacketDrops.get(),
        "crc_failures"  to _totalCrcFailures.get(),
        "reconnects"    to _reconnectCount.get()
    )

    companion object {
        private const val TAG = "AsrTransport"
    }
}
