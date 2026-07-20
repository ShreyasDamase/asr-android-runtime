package org.asr.runtime.sensor

data class AndroidSensorSnapshot(
    val availableSensors: Int = 0,
    val activeSensors: Int = 0,
    val totalEvents: Long = 0,
    val failedSensors: List<String> = emptyList(),
    val lastSensorName: String = "",
    val lastSensorType: String = "",
    val lastValues: List<Float> = emptyList(),
    val lastTimestampNs: Long = 0L
)
