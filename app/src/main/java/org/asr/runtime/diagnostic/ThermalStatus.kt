package org.asr.runtime.diagnostic

/**
 * Represents the current thermal throttling state of the device.
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
