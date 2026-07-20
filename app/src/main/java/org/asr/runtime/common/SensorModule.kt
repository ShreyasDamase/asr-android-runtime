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
 */
interface SensorModule {
    /**
     * Unique identifier for the module (e.g., "camera", "imu").
     */
    val id: String

    /**
     * Current operational state.
     */
    var state: ModuleState

    /**
     * Start data acquisition.
     */
    fun start()

    /**
     * Stop data acquisition.
     */
    fun stop()

    /**
     * Release all resources.
     */
    fun release()
}
