package org.asr.runtime.audio

data class AudioSnapshot(
    val isRunning: Boolean = false,
    val sampleRateHz: Int = 16_000,
    val channelCount: Int = 1,
    val buffersRead: Long = 0,
    val samplesRead: Long = 0,
    val rms: Double = 0.0,
    val dbfs: Double = -120.0,
    val lastTimestampNs: Long = 0L,
    val lastError: String = ""
)
