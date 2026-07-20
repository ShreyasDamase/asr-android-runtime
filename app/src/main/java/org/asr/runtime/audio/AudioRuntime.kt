package org.asr.runtime.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import org.asr.runtime.common.SensorModule
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

class AudioRuntime(private val context: Context) : SensorModule {

    override val id: String = "audio"

    companion object {
        private const val TAG = "AsrAudio"
        private const val SAMPLE_RATE_HZ = 16_000
        private const val LOG_INTERVAL_BUFFERS = 50L
    }

    @Volatile
    var snapshot = AudioSnapshot(sampleRateHz = SAMPLE_RATE_HZ)
        private set

    @Volatile
    private var shouldRun = false

    private var audioRecord: AudioRecord? = null
    private var worker: Thread? = null

    override fun start() {
        if (shouldRun) return

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            snapshot = snapshot.copy(lastError = "RECORD_AUDIO permission not granted")
            Log.w(TAG, "Microphone not started: RECORD_AUDIO permission not granted")
            return
        }

        val minBufferBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )

        if (minBufferBytes <= 0) {
            snapshot = snapshot.copy(lastError = "Invalid min buffer size: $minBufferBytes")
            Log.e(TAG, "Microphone not started: invalid min buffer size $minBufferBytes")
            return
        }

        val bufferSizeBytes = minBufferBytes * 2
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.UNPROCESSED,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            bufferSizeBytes
        )

        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            snapshot = snapshot.copy(lastError = "AudioRecord failed to initialize")
            Log.e(TAG, "Microphone not started: AudioRecord failed to initialize")
            return
        }

        audioRecord = recorder
        shouldRun = true
        snapshot = AudioSnapshot(isRunning = true, sampleRateHz = SAMPLE_RATE_HZ)

        worker = thread(name = "ASR-AudioRuntime") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            readLoop(recorder, ShortArray(bufferSizeBytes / 2))
        }

        Log.i(TAG, "Microphone runtime started sample_rate_hz=$SAMPLE_RATE_HZ buffer_bytes=$bufferSizeBytes")
    }

    override fun stop() {
        shouldRun = false
        worker?.join(500)
        worker = null
        audioRecord?.let {
            runCatching { it.stop() }
            it.release()
        }
        audioRecord = null
        snapshot = snapshot.copy(isRunning = false)
        Log.i(TAG, "Microphone runtime stopped")
    }

    override fun release() {
        stop()
    }

    private fun readLoop(recorder: AudioRecord, buffer: ShortArray) {
        try {
            recorder.startRecording()
            var buffersRead = 0L
            var samplesRead = 0L

            while (shouldRun) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) continue

                buffersRead++
                samplesRead += read
                val rms = calculateRms(buffer, read)
                val dbfs = if (rms <= 0.0) -120.0 else 20.0 * log10(rms / Short.MAX_VALUE.toDouble())

                snapshot = AudioSnapshot(
                    isRunning = true,
                    sampleRateHz = SAMPLE_RATE_HZ,
                    buffersRead = buffersRead,
                    samplesRead = samplesRead,
                    rms = rms,
                    dbfs = dbfs,
                    lastTimestampNs = System.nanoTime()
                )

                if (buffersRead % LOG_INTERVAL_BUFFERS == 0L) {
                    Log.d(TAG, "[AUDIO] buffers=$buffersRead samples=$samplesRead rms=${"%.1f".format(rms)} dbfs=${"%.1f".format(dbfs)}")
                }
            }
        } catch (e: RuntimeException) {
            snapshot = snapshot.copy(isRunning = false, lastError = e.message.orEmpty())
            Log.e(TAG, "Microphone runtime failed", e)
        }
    }

    private fun calculateRms(buffer: ShortArray, read: Int): Double {
        var sumSquares = 0.0
        for (i in 0 until read) {
            val sample = buffer[i].toDouble()
            sumSquares += sample * sample
        }
        return sqrt(sumSquares / read.toDouble())
    }
}
