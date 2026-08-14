package org.asr.runtime.audio

import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Speaker Module — Manages audio output (Speaker) for the robot via Text-To-Speech (TTS).
 */
class SpeakerManager(private val context: Context) : SensorModule, TextToSpeech.OnInitListener {

    override val id: String = "speaker"
    override var state: ModuleState = ModuleState.STOPPED

    private var tts: TextToSpeech? = null
    @Volatile var isTtsReady = false
        private set

    val commandsSent = AtomicInteger(0)
    val completedCount = AtomicInteger(0)
    val failedCount = AtomicInteger(0)

    @Volatile var lastText: String = ""
        private set
    @Volatile var lastSpeechTimeMs: Long = 0L
        private set
    @Volatile var lastResult: String = "NONE"
        private set
    @Volatile var ttsEngine: String = "Google TTS"
        private set
    @Volatile var language: String = "en-US"
        private set

    fun getVolumePercentage(): Int {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            if (max > 0) (current * 100) / max else 80
        } catch (e: Exception) {
            80
        }
    }

    override fun start() {
        state = ModuleState.STARTING
        tts = TextToSpeech(context, this)
        Log.i("AsrSpeaker", "Speaker manager starting (TTS initializing...)")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            ttsEngine = tts?.defaultEngine ?: "Android TTS"
            language = tts?.language?.displayName ?: "en-US"
            isTtsReady = true

            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    Log.i("AsrSpeaker", "TTS Utterance Started: $utteranceId")
                    lastResult = "SPEAKING"
                }

                override fun onDone(utteranceId: String?) {
                    completedCount.incrementAndGet()
                    lastResult = "SUCCESS"
                    Log.i("AsrSpeaker", "TTS Utterance Completed: $utteranceId (Completed=${completedCount.get()})")
                }

                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {
                    failedCount.incrementAndGet()
                    lastResult = "FAILED"
                    Log.e("AsrSpeaker", "TTS Utterance Failed: $utteranceId")
                }
            })

            Log.i("AsrSpeaker", "TTS initialized successfully ($ttsEngine, $language)")
            state = ModuleState.RUNNING
        } else {
            Log.e("AsrSpeaker", "TTS initialization failed")
            state = ModuleState.ERROR
        }
    }

    fun speak(text: String): Boolean {
        commandsSent.incrementAndGet()
        lastText = text
        lastSpeechTimeMs = System.currentTimeMillis()

        return if (isTtsReady && tts != null) {
            val params = Bundle()
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "asr_utt_${commandsSent.get()}")
            val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "asr_utt_${commandsSent.get()}")
            if (result == TextToSpeech.SUCCESS) {
                lastResult = "QUEUED"
                Log.d("AsrSpeaker", "[OUTPUT] Speaking: \"$text\"")
                true
            } else {
                failedCount.incrementAndGet()
                lastResult = "FAILED"
                Log.e("AsrSpeaker", "TTS speak failed: \"$text\"")
                false
            }
        } else {
            failedCount.incrementAndGet()
            lastResult = "FAILED (NOT_READY)"
            Log.w("AsrSpeaker", "TTS not ready, ignored speech request: \"$text\"")
            false
        }
    }

    override fun stop() {
        state = ModuleState.STOPPING
        tts?.stop()
        Log.i("AsrSpeaker", "Speaker manager stopped")
        state = ModuleState.STOPPED
    }

    override fun release() {
        tts?.shutdown()
        tts = null
    }
}
