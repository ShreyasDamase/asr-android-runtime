package org.asr.runtime.common

/**
 * Represents the operational state of a sensor module.
 */
enum class ModuleState {
    STOPPED,
    STARTING,
    RUNNING,
    ERROR,
    STOPPING
}

/**
 * Common interface for all hardware abstraction modules in ASR.
 *
 * Rules:
 * - Modules must never reference Transport or ROS types directly.
 * - Modules publish via PacketSerializer → TransportManager.send().
 * - start()/stop()/release() must never throw.
 */
interface SensorModule {
    /** Unique identifier (e.g. "camera", "imu", "gps"). */
    val id: String

    /** Current operational state. */
    var state: ModuleState

    /** Begin hardware acquisition. */
    fun start()

    /** Halt hardware acquisition cleanly. */
    fun stop()

    /** Release all held resources. Safe to call multiple times. */
    fun release()

    // ── v1.0 extensions — default implementations keep all existing modules compatible ──

    /**
     * Applies a JSON configuration string to the module at runtime.
     * Each module defines which keys it accepts.
     */
    fun configure(json: String) {}

    /**
     * Returns a snapshot of the module's current operational metrics.
     * Always includes at minimum "id" and "state".
     */
    fun status(): Map<String, Any> = mapOf("id" to id, "state" to state.name)

    /**
     * Returns the list of capabilities this module provides.
     * Used by CapabilityManager to build the handshake packet.
     */
    fun capabilities(): List<String> = emptyList()
}
