package org.asr.runtime.transport

import android.util.Log
import org.asr.runtime.common.Transport
import org.asr.runtime.core.DevModeConfig
import org.asr.runtime.protocol.AsrVersion
import java.io.InputStream
import java.io.OutputStream
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

    @Volatile
    override var isConnected: Boolean = false
        private set(value) {
            if (field != value) {
                field = value
                onConnectionStateChanged?.invoke(value)
            }
        }

    private val sendQueue = ArrayBlockingQueue<ByteArray>(QUEUE_CAPACITY)

    @Volatile private var dropCount = 0L
    @Volatile private var shouldRun = false
    @Volatile private var hasConnectedOnce = false

    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var writeThread: Thread? = null
    private var readThread: Thread? = null

    /** Approximate number of packets currently waiting to be sent. */
    val queueDepth: Int get() = sendQueue.size

    /** Number of packets dropped due to back-pressure since last connect. */
    val queueDrops: Long get() = dropCount

    override fun connect() {
        if (shouldRun) return
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
        shouldRun = false
        hasConnectedOnce = false
        // Drain and unblock sendQueue.take() immediately.
        // Without this, the write thread blocks forever on take() and
        // writeThread.join(500) always times out, leaving a zombie thread.
        sendQueue.clear()
        sendQueue.offer(ByteArray(0))  // sentinel to wake take()
        runCatching { socket?.close() }
        writeThread?.interrupt()
        readThread?.interrupt()
        writeThread?.join(500)
        readThread?.join(500)
        writeThread = null
        readThread  = null
        isConnected = false
        Log.i(TAG, "[TCP] Transport stopped")
    }

    // ── Write loop ────────────────────────────────────────────────────────────

    private fun writeLoop() {
        try {
            while (shouldRun && !Thread.currentThread().isInterrupted) {
                try {
                    Log.i(TAG, "[TCP] Connecting to $host:$port ...")
                    val sock = Socket()
                    sock.connect(java.net.InetSocketAddress(host, port), 3000)
                    sock.tcpNoDelay = true
                    sock.keepAlive = true
                    socket = sock
                    outputStream = sock.getOutputStream()
                    isConnected = true
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
                            outputStream?.write(packet)
                            outputStream?.flush()
                        } catch (e: Exception) {
                            // Socket closed — break inner loop, outer will reconnect
                            break
                        }
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    isConnected = false
                    if (shouldRun && !Thread.currentThread().isInterrupted) {
                        Log.w(TAG, "[TCP] Disconnected (${e.message}) — retrying in ${RETRY_DELAY_MS}ms")
                        try {
                            Thread.sleep(RETRY_DELAY_MS)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }
            }
        } finally {
            isConnected = false
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
                        if (!readFully(ins, header, 0, AsrVersion.HEADER_SIZE)) break

                        // Parse payload length from header bytes [20-23]
                        val payloadLen = ByteBuffer.wrap(header, 20, 4)
                            .order(ByteOrder.LITTLE_ENDIAN).int

                        // A corrupt or desynced stream can yield a negative length
                        // (NegativeArraySizeException) or an enormous one (OutOfMemoryError,
                        // an Error that the catch below would NOT catch → process crash).
                        // Bail out of the read loop instead of allocating on a bad length.
                        if (payloadLen < 0 || payloadLen > MAX_PAYLOAD_BYTES) {
                            Log.w(TAG, "[TCP] Invalid inbound payload length $payloadLen — aborting read loop")
                            break
                        }

                        val fullPacket = ByteArray(AsrVersion.HEADER_SIZE + payloadLen)
                        System.arraycopy(header, 0, fullPacket, 0, AsrVersion.HEADER_SIZE)

                        if (payloadLen > 0 && !readFully(ins, fullPacket, AsrVersion.HEADER_SIZE, payloadLen)) break

                        onPacketReceived?.invoke(fullPacket)
                    }
                } catch (e: InterruptedException) {
                    break
                } catch (e: Exception) {
                    if (shouldRun && !Thread.currentThread().isInterrupted) {
                        Log.w(TAG, "[TCP] Read error: ${e.message}")
                        try {
                            Thread.sleep(200)
                        } catch (e: InterruptedException) {
                            break
                        }
                    }
                }
            }
        } finally {
            // Read loop terminated
        }
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
