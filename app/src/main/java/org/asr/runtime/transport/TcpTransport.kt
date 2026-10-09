package org.asr.runtime.transport

import android.util.Log
import org.asr.runtime.common.Transport
import org.asr.runtime.core.DevModeConfig
import org.asr.runtime.protocol.AsrVersion
import java.io.InputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ArrayBlockingQueue
import kotlin.concurrent.thread

/**
 * TCP transport for Wi-Fi streaming and USB-over-adb-forward.
 *
 * USB-over-TCP usage (no USB Accessory driver needed):
 *   adb forward tcp:4242 tcp:4242
 *   Then set host="127.0.0.1", port=4242
 *
 * Back-pressure policy: DROP_OLDEST
 *   Queue capacity: [QUEUE_CAPACITY] packets.
 *   When the queue is full, the oldest packet is evicted and the new one is enqueued.
 *   This prevents producer stalls at the cost of dropping old data.
 *   The alternative (DROP_NEWEST) was rejected because it would block the camera thread.
 *   The alternative (BLOCKING) was rejected to avoid backpressure into sensor acquisition.
 *
 * Thread model:
 *   - writeThread: dequeues packets and writes to socket output stream.
 *   - readThread:  reads incoming command packets from socket input stream.
 *   Both threads reconnect automatically on disconnect.
 *
 * @param host              Remote host (default "127.0.0.1" for adb forward).
 * @param port              Remote port (default 4242).
 * @param onPacketReceived  Callback for inbound packets (COMMAND, etc.). Called from readThread.
 */
class TcpTransport(
    var host: String = "127.0.0.1",
    var port: Int = 4242,
    private val onPacketReceived: ((ByteArray) -> Unit)? = null,
    private val onReconnect: (() -> Unit)? = null,
    private val onConnectionStateChanged: ((Boolean) -> Unit)? = null
) : Transport {

    override val type: String = "tcp"

    @Volatile private var connectedState = false
    override val isConnected: Boolean get() = connectedState

    private val sendQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    @Volatile private var dropCount = 0L
    @Volatile private var shouldRun = false
    @Volatile private var hasConnectedOnce = false

    @Volatile private var socket: Socket? = null
    private val connectionLock = Any()
    private var writeThread: Thread? = null
    private var readThread: Thread? = null

    /** Approximate number of packets currently waiting to be sent. */
    val queueDepth: Int get() = sendQueue.size

    /** Number of packets dropped due to back-pressure since last connect. */
    val queueDrops: Long get() = dropCount

    override fun connect() {
        if (shouldRun) return
        // A disconnect or peer-close wake-up may have left the writer sentinel
        // in the queue. It belongs to the previous lifecycle, never this one.
        sendQueue.clear()
        shouldRun = true
        dropCount = 0L
        writeThread = thread(name = "ASR-TcpWrite", isDaemon = true) { writeLoop() }
        readThread  = thread(name = "ASR-TcpRead",  isDaemon = true) { readLoop()  }
        Log.i(TAG, "[TCP] Transport started → $host:$port")
    }

    override fun send(packet: ByteArray) {
        if (!shouldRun) return
        if (!sendQueue.offer(packet)) {
            // Queue full — DROP OLDEST to make room (documented back-pressure policy)
            sendQueue.poll()
            sendQueue.offer(packet)
            dropCount++
            if (DevModeConfig.packetLog) {
                Log.w(TAG, "[TCP] Queue full — dropped oldest packet (total drops=$dropCount)")
            }
        }
    }

    override fun disconnect() {
        val activeSocket: Socket?
        val notifyDisconnected: Boolean
        synchronized(connectionLock) {
            shouldRun = false
            hasConnectedOnce = false
            activeSocket = socket
            socket = null
            notifyDisconnected = connectedState
            connectedState = false
        }
        if (notifyDisconnected) onConnectionStateChanged?.invoke(false)
        // Drain and unblock sendQueue.take() immediately.
        // Without this, the write thread blocks forever on take() and
        // writeThread.join(500) always times out, leaving a zombie thread.
        sendQueue.clear()
        sendQueue.offer(ByteArray(0))  // sentinel to wake take()
        runCatching { activeSocket?.close() }
        writeThread?.interrupt()
        readThread?.interrupt()
        writeThread?.join(500)
        readThread?.join(500)
        writeThread = null
        readThread  = null
        Log.i(TAG, "[TCP] Transport stopped")
    }

    // ── Write loop ────────────────────────────────────────────────────────────

    private fun writeLoop() {
        try {
            while (shouldRun && !Thread.currentThread().isInterrupted) {
                val sock = Socket()
                try {
                    Log.i(TAG, "[TCP] Connecting to $host:$port ...")
                    sock.connect(java.net.InetSocketAddress(host, port), 3000)
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    val output = sock.getOutputStream()
                    val connected = synchronized(connectionLock) {
                        if (shouldRun) {
                            socket = sock
                            val changed = !connectedState
                            connectedState = true
                            changed
                        } else {
                            false
                        }
                    }
                    if (!shouldRun || socket !== sock) break
                    if (connected && socket === sock && isConnected) onConnectionStateChanged?.invoke(true)
                    // Only report *re*connects — the first successful connect is
                    // not a reconnect and must not inflate reconnectCount.
                    if (hasConnectedOnce) onReconnect?.invoke()
                    hasConnectedOnce = true
                    Log.i(TAG, "[TCP] Connected to $host:$port")

                    while (shouldRun && !sock.isClosed && !Thread.currentThread().isInterrupted) {
                        val packet = try {
                            sendQueue.take()   // blocks until data available
                        } catch (e: InterruptedException) {
                            break
                        }
                        if (packet.isEmpty()) break      // disconnect() sentinel — exit write loop cleanly
                        try {
                            output.write(packet)
                            output.flush()
                        } catch (e: Exception) {
                            // Socket closed — break inner loop, outer will reconnect
                            break
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    signalConnectionClosed(sock, "write/connect error: ${e.message}")
                    if (shouldRun && !Thread.currentThread().isInterrupted) {
                        Log.w(TAG, "[TCP] Disconnected (${e.message}) — retrying in ${RETRY_DELAY_MS}ms")
                    }
                } finally {
                    signalConnectionClosed(sock, "connection closed")
                    runCatching { sock.close() }
                }
                // signalConnectionClosed() wakes a blocked writer with an empty
                // packet. Consume that old-lifecycle sentinel before reconnecting.
                sendQueue.clear()
                if (shouldRun && !Thread.currentThread().isInterrupted) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            }
        } finally {
            val notifyDisconnected = synchronized(connectionLock) {
                val changed = connectedState
                connectedState = false
                changed
            }
            if (notifyDisconnected) onConnectionStateChanged?.invoke(false)
        }
    }

    // ── Read loop ─────────────────────────────────────────────────────────────

    private fun readLoop() {
        try {
            while (shouldRun && !Thread.currentThread().isInterrupted) {
                val sock = socket
                if (sock == null || !sock.isConnected || sock.isClosed) {
                    try {
                        Thread.sleep(200)
                    } catch (e: InterruptedException) {
                        break
                    }
                    continue
                }
                try {
                    val ins = sock.getInputStream()
                    while (shouldRun && !sock.isClosed && !Thread.currentThread().isInterrupted) {
                        val header = ByteArray(AsrVersion.HEADER_SIZE)
                        if (!readFully(ins, header, 0, AsrVersion.HEADER_SIZE)) {
                            signalConnectionClosed(sock, "peer closed the connection")
                            break
                        }

                        // Parse payload length from header bytes [20-23]
                        val payloadLen = ByteBuffer.wrap(header, 20, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).int

                        // A corrupt or desynced stream can yield a negative length
                        // (NegativeArraySizeException) or an enormous one (OutOfMemoryError,
                        // an Error that the catch below would NOT catch → process crash).
                        // Bail out of the read loop instead of allocating on a bad length.
                        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {
                            Log.w(TAG, "[TCP] Invalid inbound payload length $payloadLen — aborting read loop")
                            signalConnectionClosed(sock, "invalid inbound payload length")
                            break
                        }

                        val fullPacket = ByteArray(AsrVersion.HEADER_SIZE + payloadLen)
                        System.arraycopy(header, 0, fullPacket, 0, AsrVersion.HEADER_SIZE)

                        if (payloadLen > 0 && !readFully(ins, fullPacket, AsrVersion.HEADER_SIZE, payloadLen)) {
                            signalConnectionClosed(sock, "peer closed during packet read")
                            break
                        }

                        onPacketReceived?.invoke(fullPacket)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (shouldRun && !Thread.currentThread().isInterrupted) {
                        Log.w(TAG, "[TCP] Read error: ${e.message}")
                        signalConnectionClosed(sock, "read error: ${e.message}")
                    }
                }
            }
        } finally {
            // Read loop terminated
        }
    }

    /** Close a failed socket and wake the writer so it can own reconnection. */
    private fun signalConnectionClosed(sock: Socket, reason: String) {
        var closedCurrent = false
        var notifyDisconnected = false
        synchronized(connectionLock) {
            if (socket === sock) {
                socket = null
                notifyDisconnected = connectedState
                connectedState = false
                closedCurrent = true
            }
        }
        if (!closedCurrent) return
        if (notifyDisconnected) onConnectionStateChanged?.invoke(false)
        Log.i(TAG, "[TCP] Connection ended ($reason)")
        sendQueue.clear()
        sendQueue.offer(ByteArray(0))
        runCatching { sock.close() }
    }

    private fun readFully(ins: InputStream, buf: ByteArray, offset: Int, length: Int): Boolean {
        var remaining = length
        var pos = offset
        while (remaining > 0 && shouldRun) {
            val read = ins.read(buf, pos, remaining)
            if (read < 0) return false
            pos += read
            remaining -= read
        }
        return remaining == 0
    }

    companion object {
        private const val TAG = "AsrTcp"

        /**
         * Maximum number of packets in the send queue.
         * At 30 FPS camera + 100Hz IMU, this represents ~4 seconds of data.
         * Exceeding this triggers DROP_OLDEST policy.
         */
        const val QUEUE_CAPACITY = 512

        /** Milliseconds to wait before retrying a failed connection. */
        const val RETRY_DELAY_MS = 2000L

        /**
         * Upper bound for an inbound packet payload. Inbound packets are COMMAND
         * JSON (tiny); this is a generous ceiling that still rejects garbage
         * lengths from a corrupt/desynced stream before they allocate.
         */
        const val MAX_PAYLOAD_BYTES = 8 * 1024 * 1024
    }
}
