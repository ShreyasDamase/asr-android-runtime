package org.asr.runtime.ros2

import android.os.SystemClock

/**
 * Converts Android sensor timestamps to Unix time for ROS 2 message headers.
 *
 * Sensors and cameras stamp samples from a since-boot clock. ROS 2 nodes on other
 * machines expect Unix time, the same base as `ros2 topic echo` on a host with
 * use_sim_time off. The sample's own timing is kept; only the base changes.
 * Accuracy across machines depends on the phone's and the host's clock sync.
 */
object RosClock {
    @Volatile private var elapsedToUnixNs = 0L
    @Volatile private var monotonicToUnixNs = 0L

    init {
        sync()
    }

    /** Samples the clock offsets. Call on node start so message time stays steady while running. */
    fun sync() {
        val unixNs = System.currentTimeMillis() * 1_000_000L
        elapsedToUnixNs = unixNs - SystemClock.elapsedRealtimeNanos()
        monotonicToUnixNs = unixNs - System.nanoTime()
    }

    /** Some devices stamp from the monotonic clock instead, so use whichever base the sample is closest to. */
    fun toUnixNs(sensorNs: Long): Long {
        val fromElapsed = Math.abs(sensorNs - SystemClock.elapsedRealtimeNanos())
        val fromMonotonic = Math.abs(sensorNs - System.nanoTime())
        return sensorNs + if (fromElapsed <= fromMonotonic) elapsedToUnixNs else monotonicToUnixNs
    }
}
