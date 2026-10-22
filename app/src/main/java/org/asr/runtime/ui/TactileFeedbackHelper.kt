package org.asr.runtime.ui

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import java.util.WeakHashMap

/**
 * Utility for Nothing / CMF tactile mechanical feedback on buttons and controls.
 * Provides sharp physical keypress click sounds and micro-haptic ticks.
 */
object TactileFeedbackHelper {

    private val soundPool = SoundPool.Builder()
        .setMaxStreams(4)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    @Volatile private var tactilePressId = 0
    @Volatile private var tactileReleaseId = 0
    @Volatile private var sliderOnId = 0
    @Volatile private var sliderOffId = 0
    @Volatile private var loaded = false
    private const val TACTILE_PRESS_DURATION_MS = 131L
    private val mainHandler = Handler(Looper.getMainLooper())
    private val pressStartedAt = WeakHashMap<View, Long>()

    private fun ensureSoundsLoaded(view: View) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val context = view.context.applicationContext
            tactilePressId = soundPool.load(context, org.asr.runtime.R.raw.tactile_press, 1)
            tactileReleaseId = soundPool.load(context, org.asr.runtime.R.raw.tactile_release, 1)
            sliderOnId = soundPool.load(context, org.asr.runtime.R.raw.slider_on, 1)
            sliderOffId = soundPool.load(context, org.asr.runtime.R.raw.slider_off, 1)
            loaded = true
        }
    }

    fun prepare(view: View) = ensureSoundsLoaded(view)

    private fun play(soundId: Int, playbackRate: Float = 1f) {
        if (soundId != 0) soundPool.play(soundId, 1f, 1f, 1, 0, playbackRate.coerceIn(0.5f, 2f))
    }

    fun playSliderSound(view: View, enabled: Boolean) {
        ensureSoundsLoaded(view)
        play(if (enabled) sliderOnId else sliderOffId)
    }

    /**
     * Plays an audible mechanical tactile key-click sound via system audio manager.
     */
    fun playClickSound(view: View) {
        ensureSoundsLoaded(view)
        val startedAt = synchronized(pressStartedAt) { pressStartedAt.remove(view) }
        val waitMs = startedAt?.let {
            (TACTILE_PRESS_DURATION_MS - (SystemClock.uptimeMillis() - it)).coerceAtLeast(0L)
        } ?: 0L
        if (waitMs == 0L) play(tactileReleaseId)
        else mainHandler.postDelayed({ play(tactileReleaseId) }, waitMs)
    }

    /**
     * Triggers a crisp physical tactile impulse (haptic tick).
     */
    fun performTactileTick(view: View) {
        // First try View's built-in haptic constant
        val performed = view.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )

        if (!performed) {
            triggerHardwareVibration(view.context)
        }
    }

    private fun triggerHardwareVibration(context: Context) {
        try {
            val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                vibrator?.vibrate(VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK))
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(VibrationEffect.createOneShot(12, 180))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(12)
            }
        } catch (_: Exception) {
            // Ignore if vibration permissions or hardware are restricted
        }
    }

    /**
     * Binds tactile press-and-release mechanics to any View:
     * - Immediate visual inversion & down-tick on ACTION_DOWN
     * - Auditory click + action execution ONLY on ACTION_UP when released within bounds
     */
    fun attachTactileFeedback(
        view: View,
        onReleaseAction: () -> Unit
    ) {
        ensureSoundsLoaded(view)
        view.isClickable = true
        view.isFocusable = true

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    ensureSoundsLoaded(v)
                    synchronized(pressStartedAt) { pressStartedAt[v] = SystemClock.uptimeMillis() }
                    play(tactilePressId)
                    performTactileTick(v)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val wasPressed = v.isPressed
                    v.isPressed = false
                    val releasedInside = wasPressed && event.x in 0f..v.width.toFloat() && event.y in 0f..v.height.toFloat()
                    if (releasedInside) {
                        playClickSound(v)
                        performTactileTick(v)
                        onReleaseAction.invoke()
                    }
                    synchronized(pressStartedAt) { pressStartedAt.remove(v) }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    synchronized(pressStartedAt) { pressStartedAt.remove(v) }
                    true
                }
                else -> false
            }
        }
    }

}
