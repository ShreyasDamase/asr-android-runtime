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
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import org.asr.runtime.protocol.AsrPacket
import org.asr.runtime.protocol.PacketSerializer
import org.asr.runtime.protocol.TopicId
import org.asr.runtime.transport.TransportManager
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.math.log10
import kotlin.math.sqrt

class AudioRuntime(
    private val context: Context,
    private val transportManager: TransportManager? = null,
    private val deviceId: Byte = 0
) : SensorModule {

    override val id: String = "audio"
    override var state: ModuleState = ModuleState.STOPPED

    private val seq = AtomicInteger(0)
    // Raw PCM stream is a distinct topic (RESERVED_10) and must carry its own
    // per-topic sequence, otherwise the receiver's gap detection sees every
    // other packet missing on both the AUDIO and raw streams.
    private val rawSeq = AtomicInteger(0)

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
        state = ModuleState.STARTING

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            snapshot = snapshot.copy(lastError = "RECORD_AUDIO permission not granted")
            Log.w(TAG, "Microphone not started: RECORD_AUDIO permission not granted")
            state = ModuleState.ERROR
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
            state = ModuleState.ERROR
            return
        }

        val bufferSizeBytes = minBufferBytes * 2
        var recorder: AudioRecord? = null
        val audioSources = listOf(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            MediaRecorder.AudioSource.MIC,
            MediaRecorder.AudioSource.UNPROCESSED,
            MediaRecorder.AudioSource.DEFAULT
        )

        for (source in audioSources) {
            try {
                val candidate = AudioRecord(
                    source,
                    SAMPLE_RATE_HZ,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSizeBytes
                )
                if (candidate.state == AudioRecord.STATE_INITIALIZED) {
                    recorder = candidate
                    Log.i(TAG, "AudioRecord initialized using source=$source")
                    break
                } else {
                    candidate.release()
                }
            } catch (e: Exception) {
                Log.w(TAG, "AudioSource $source failed: ${e.message}")
            }
        }

        if (recorder == null || recorder.state != AudioRecord.STATE_INITIALIZED) {
            snapshot = snapshot.copy(lastError = "AudioRecord failed to initialize")
            Log.e(TAG, "Microphone not started: AudioRecord failed to initialize")
            state = ModuleState.ERROR
            return
        }

        audioRecord = recorder
        shouldRun = true
        state = ModuleState.RUNNING
        snapshot = AudioSnapshot(isRunning = true, sampleRateHz = SAMPLE_RATE_HZ)

        worker = thread(name = "ASR-AudioRuntime") {
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            readLoop(recorder, ShortArray(bufferSizeBytes / 2))
        }

        Log.i(TAG, "Microphone runtime started sample_rate_hz=$SAMPLE_RATE_HZ buffer_bytes=$bufferSizeBytes")
    }

    override fun stop() {
        state = ModuleState.STOPPING
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
        state = ModuleState.STOPPED
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
                if (read == 0) continue
                if (read < 0) {
                    // Negative values are AudioRecord error codes (ERROR_DEAD_OBJECT,
                    // ERROR_INVALID_OPERATION, ...). Continuing would busy-spin the
                    // CPU forever, so surface the error and stop the loop.
                    snapshot = snapshot.copy(isRunning = false, lastError = "AudioRecord.read error $read")
                    Log.e(TAG, "Microphone read returned error code $read — stopping capture loop")
                    state = ModuleState.ERROR
                    break
                }

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

                // Calculate Peak level
                var maxPeak = 0
                for (i in 0 until read) {
                    val absVal = kotlin.math.abs(buffer[i].toInt())
                    if (absVal > maxPeak) maxPeak = absVal
                }
                val peakLevel = (maxPeak / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)
                val rmsNorm = (rms / Short.MAX_VALUE.toDouble()).toFloat().coerceIn(0f, 1f)
                val voiceActive = rmsNorm > 0.04f || peakLevel > 0.12f

                // Send live Audio telemetry packet (~20-50 Hz)
                transportManager?.let { tm ->
                    val payload = PacketSerializer.audioLevel(
                        rmsLevel = rmsNorm,
                        peakLevel = peakLevel,
                        noiseFloorDbfs = dbfs.toFloat(),
                        voiceActive = voiceActive,
                        sampleRateHz = SAMPLE_RATE_HZ
                    )
                    val packet = AsrPacket.encode(
                        topicId = TopicId.AUDIO.value,
                        seq = seq.getAndIncrement(),
                        payload = payload,
                        deviceId = deviceId
                    )
                    tm.send(packet)

                    // Also stream raw audio PCM packets on RESERVED_10 for live desktop monitor & ROS 2 /audio/raw
                    val rawPayload = PacketSerializer.audio(buffer, read)
                    val rawPacket = AsrPacket.encode(
                        topicId = TopicId.RESERVED_10.value,
                        seq = rawSeq.getAndIncrement(),
                        payload = rawPayload,
                        deviceId = deviceId
                    )
                    tm.send(rawPacket)
                }

                if (buffersRead % LOG_INTERVAL_BUFFERS == 0L) {
                    Log.d(TAG, "[AUDIO] buffers=$buffersRead samples=$samplesRead rms=${"%.1f".format(rms)} dbfs=${"%.1f".format(dbfs)}")
                }
            }
        } catch (e: RuntimeException) {
            snapshot = snapshot.copy(isRunning = false, lastError = e.message.orEmpty())
            Log.e(TAG, "Microphone runtime failed", e)
            state = ModuleState.ERROR
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
