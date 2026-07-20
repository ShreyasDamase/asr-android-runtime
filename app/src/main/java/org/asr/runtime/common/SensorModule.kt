package org.asr.runtime.common

/**
 * Common interface for all hardware abstraction modules in ASR.
 */
interface SensorModule {
    /**
     * Unique identifier for the module (e.g., "camera", "imu").
     */
    val id: String

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
