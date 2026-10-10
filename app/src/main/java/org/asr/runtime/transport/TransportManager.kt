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
    @Volatile
    override var state: ModuleState = ModuleState.STOPPED

    private val transports = mutableListOf<Transport>()
    private val enabledTransportTypes = mutableSetOf<String>()

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

    fun isAnyConnected(): Boolean = transports.any { it.type != "log" && it.isConnected }

    /** Called by [org.asr.runtime.transport.TcpTransport] when a reconnect occurs. */
    fun notifyReconnect() {
        _reconnectCount.incrementAndGet()
    }

    /** Called by [org.asr.runtime.core.CommandDispatcher] on CRC failure. */
    fun notifyCrcFailure() {
        _totalCrcFailures.incrementAndGet()
    }

    @Synchronized
    fun startTransport(type: String) {
        val transport = transports.firstOrNull { it.type == type }
        if (transport == null) {
            Log.w(TAG, "Ignoring start request for unknown transport '$type'")
            return
        }
        if (!enabledTransportTypes.add(type)) return
        try {
            transport.connect()
            updateState()
        } catch (t: Throwable) {
            enabledTransportTypes.remove(type)
            updateState()
            Log.e(TAG, "Failed to start transport '$type'", t)
        }
    }

    @Synchronized
    fun stopTransport(type: String) {
        val transport = transports.firstOrNull { it.type == type } ?: return
        if (!enabledTransportTypes.remove(type)) return
        try {
            transport.disconnect()
        } catch (t: Throwable) {
            Log.w(TAG, "Error stopping transport '$type'", t)
        } finally {
            updateState()
        }
    }

    @Synchronized
    fun isTransportEnabled(type: String): Boolean = type in enabledTransportTypes

    @Synchronized
    override fun start() {
        // Transport lifecycles are controlled by their own switches. This
        // SensorModule hook runs with the sensor runtime and must not start TCP.
        updateState()
    }

    @Synchronized
    override fun stop() {
        // Runtime stop leaves explicitly enabled transports alone.
        updateState()
    }

    @Synchronized
    override fun release() {
        // Service destruction is a final cleanup boundary, unlike the runtime
        // stop hook above. Disconnect any transport still enabled here.
        transports.forEach { transport ->
            if (enabledTransportTypes.remove(transport.type)) {
                runCatching { transport.disconnect() }
                    .onFailure { Log.w(TAG, "Error releasing transport '${transport.type}'", it) }
            }
        }
        updateState()
        transports.clear()
    }

    /**
     * Fan-out a pre-encoded AsrPacket to all connected transports.
     *
     * Transport never inspects the packet content — it is a pure byte pipe.
     */
    fun send(packet: ByteArray) {
        var sentToNetwork = false
        transports.forEach { transport ->
            if (transport.isConnected) {
                transport.send(packet)
                if (transport.type != "log") sentToNetwork = true
            }
        }
        if (sentToNetwork) {
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

    private fun updateState() {
        state = if (enabledTransportTypes.any { it != "log" }) ModuleState.RUNNING else ModuleState.STOPPED
    }

    companion object {
        private const val TAG = "AsrTransport"
    }
}
