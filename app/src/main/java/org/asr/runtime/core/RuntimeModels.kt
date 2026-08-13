package org.asr.runtime.core

/**
 * High-level state of the ASR hardware runtime.
 */
enum class RuntimeState {
    STOPPED,
    STARTING,
    CONNECTING,
    CONNECTED,
    STREAMING,
    STOPPING,
    ERROR
}

/**
 * Information about the currently connected remote client.
 */
data class ClientInfo(
    val application: String = "Unknown",
    val version: String = "0.0.0",
    val protocol: String = "v1",
    val transport: String = "TCP",
    val host: String = "Unknown",
    val port: Int = 0,
    val connectedSince: Long = 0,
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0
)

/**
 * Live diagnostics snapshot for the UI.
 */
data class DiagnosticsSnapshot(
    val packetsSent: Long = 0,
    val packetsReceived: Long = 0,
    val queueDepth: Int = 0,
    val droppedPackets: Long = 0,
    val reconnectCount: Long = 0,
    val socketState: String = "CLOSED",
    val lastHeartbeat: Long = 0,
    val uptimeMs: Long = 0
)
