package org.asr.runtime.core

/**
 * Developer mode flags for ASR runtime.
 *
 * These are volatile singletons — safe to read from any thread.
 * Set via ADB broadcast: adb shell am broadcast -a org.asr.runtime.DEV_MODE
 *   --es flag <flag_name> --ez value true
 *
 * All flags default to minimal verbosity to avoid log spam in production.
 */
object DevModeConfig {
    /** Log every IMU sensor event (can be 500+ lines/second). */
    @Volatile var rawImuLog: Boolean = false

    /** Log per-frame details: seq, size, encoding, pipeline latency. */
    @Volatile var frameDebug: Boolean = false

    /**
     * Log every packet sent via transport:
     * [PACKET] topic=IMU seq=1234 size=72 crc=0xABCD1234 dev_id=42
     */
    @Volatile var packetLog: Boolean = false

    /** Include FPS, latency, thermal data in the 1Hz heartbeat. Default ON. */
    @Volatile var benchmark: Boolean = true

    /** Enable Android Profiler-compatible trace markers. */
    @Volatile var profiling: Boolean = false

    /** Log every thermal status transition (not just SEVERE+). */
    @Volatile var thermalVerbose: Boolean = false

    /** Reflects the name/value pair from the DEV_MODE broadcast. */
    fun applyFlag(flag: String, value: Boolean) {
        when (flag) {
            "raw_imu_log"    -> rawImuLog = value
            "frame_debug"    -> frameDebug = value
            "packet_log"     -> packetLog = value
            "benchmark"      -> benchmark = value
            "profiling"      -> profiling = value
            "thermal_verbose"-> thermalVerbose = value
        }
    }
}
