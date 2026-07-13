package com.example.cameraros.camera

import android.os.Build

/**
 * Represents the current thermal throttling state of the device.
 *
 * Maps directly to [android.os.PowerManager] thermal status constants (API 29+).
 * On older devices, the status remains [UNKNOWN].
 *
 * Logged in telemetry as the enum name (e.g. thermal=MODERATE).
 * When real-world data justifies it, the camera pipeline can be degraded
 * based on this status — but that policy is deferred until evidence is collected.
 */
enum class ThermalStatus {
    UNKNOWN,
    NONE,
    LIGHT,
    MODERATE,
    SEVERE,
    CRITICAL,
    EMERGENCY;

    companion object {
        /**
         * Converts an Android [PowerManager] thermal status int to [ThermalStatus].
         * Constants from [android.os.PowerManager]:
         *   THERMAL_STATUS_NONE = 0
         *   THERMAL_STATUS_LIGHT = 1
         *   THERMAL_STATUS_MODERATE = 2
         *   THERMAL_STATUS_SEVERE = 3
         *   THERMAL_STATUS_CRITICAL = 4
         *   THERMAL_STATUS_EMERGENCY = 5
         *   THERMAL_STATUS_SHUTDOWN = 6
         */
        fun fromAndroid(status: Int): ThermalStatus = when (status) {
            0 -> NONE
            1 -> LIGHT
            2 -> MODERATE
            3 -> SEVERE
            4 -> CRITICAL
            5 -> EMERGENCY
            else -> UNKNOWN
        }
    }
}
