package org.asr.runtime.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.speech.tts.TextToSpeech
import android.util.Log
import org.asr.runtime.common.ModuleState
import org.asr.runtime.common.SensorModule
import java.util.Locale

/**
 * Manages audio output (Speaker) for the robot.
 * Supports Text-to-Speech (TTS) and system tones.
 */
class SpeakerManager(private val context: Context) : SensorModule, TextToSpeech.OnInitListener {

    override val id: String = "speaker"
    override var state: ModuleState = ModuleState.STOPPED

    private var tts: TextToSpeech? = null
    private var isTtsReady = false

    override fun start() {
        state = ModuleState.STARTING
        tts = TextToSpeech(context, this)
        Log.i("AsrSpeaker", "Speaker manager starting (TTS initializing...)")
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts?.language = Locale.US
            isTtsReady = true
            Log.i("AsrSpeaker", "TTS initialized successfully")
            state = ModuleState.RUNNING
        } else {
            Log.e("AsrSpeaker", "TTS initialization failed")
            state = ModuleState.ERROR
        }
    }

    fun speak(text: String) {
        if (isTtsReady) {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "asr_msg")
            Log.d("AsrSpeaker", "[OUTPUT] Speaking: \"$text\"")
        } else {
            Log.w("AsrSpeaker", "TTS not ready, ignored speech request: \"$text\"")
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
