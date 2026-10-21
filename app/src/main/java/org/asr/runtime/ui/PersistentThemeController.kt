package org.asr.runtime.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import java.util.IdentityHashMap

/** Persists the user's System/Light/Dark choice and applies the matching palette to a screen. */
class PersistentThemeController(private val activity: Activity) {
    var mode: Mode = readMode(activity)
        private set
    var isDark: Boolean = resolveDark(activity, mode)
        private set

    private val originalTextColors = IdentityHashMap<TextView, Int>()
    private val originalHintColors = IdentityHashMap<EditText, Int>()
    private val originalBackgrounds = IdentityHashMap<View, android.graphics.drawable.Drawable?>()
    private val originalBackgroundTints = IdentityHashMap<View, ColorStateList?>()

    fun apply(root: View) {
        applyToTree(root)
        activity.window.statusBarColor = if (isDark) Color.rgb(11, 11, 11) else Color.rgb(245, 247, 250)
        @Suppress("DEPRECATION")
        run {
            activity.window.decorView.systemUiVisibility = if (isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    fun cycle(root: View) {
        mode = when (mode) {
            Mode.SYSTEM -> Mode.LIGHT
            Mode.LIGHT -> Mode.DARK
            Mode.DARK -> Mode.SYSTEM
        }
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_THEME_MODE, mode.preferenceValue).apply()
        isDark = resolveDark(activity, mode)
        apply(root)
    }

    enum class Mode(val preferenceValue: String, val iconResource: Int, val accessibleName: String) {
        SYSTEM("system", org.asr.runtime.R.drawable.ic_theme_system, "System"),
        LIGHT("light", org.asr.runtime.R.drawable.ic_theme_light, "Light"),
        DARK("dark", org.asr.runtime.R.drawable.ic_theme_dark, "Dark")
    }

    companion object {
        private const val PREFS_NAME = "asr_prefs"
        private const val KEY_THEME_MODE = "theme_mode"

        fun currentMode(context: Context): Mode = readMode(context)

        fun isDark(context: Context): Boolean = resolveDark(context, readMode(context))

        private fun readMode(context: Context): Mode {
            val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_THEME_MODE, Mode.SYSTEM.preferenceValue)
            return Mode.entries.firstOrNull { it.preferenceValue == saved } ?: Mode.SYSTEM
        }

        private fun resolveDark(context: Context, mode: Mode): Boolean = when (mode) {
            Mode.SYSTEM -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
                Configuration.UI_MODE_NIGHT_YES
            Mode.LIGHT -> false
            Mode.DARK -> true
        }
    }

    private fun applyToTree(view: View) {
        if (view is TextView) {
            val original = originalTextColors.getOrPut(view) { view.currentTextColor }
            view.setTextColor(if (isDark) Palette.darkTextColors[original] ?: original else original)
            if (view is EditText) {
                val originalHint = originalHintColors.getOrPut(view) { view.currentHintTextColor }
                view.setHintTextColor(if (isDark) Palette.darkTextColors[originalHint] ?: originalHint else originalHint)
            }
        }

        originalBackgrounds.putIfAbsent(view, view.background?.constantState?.newDrawable()?.mutate())
        originalBackgroundTints.putIfAbsent(view, view.backgroundTintList)
        val originalBackground = originalBackgrounds[view]
        if (view.id == org.asr.runtime.R.id.btnThemeToggle) {
            val edge = if (isDark) Color.WHITE else Color.BLACK
            view.background = outline(edge)
        } else if (isActionButton(view.id)) {
            val isPrimary = view.id == org.asr.runtime.R.id.btnStartRuntime
            if (isDark) {
                view.background = actionButtonBackground(isPrimary = isPrimary)
                view.backgroundTintList = null
                (view as? TextView)?.setTextColor(actionButtonTextColors(isPrimary = isPrimary))
            } else if (originalBackground != null) {
                view.background = originalBackground.constantState?.newDrawable()?.mutate() ?: originalBackground
                view.backgroundTintList = originalBackgroundTints[view]
                (view as? TextView)?.let { textView ->
                    if (isPrimary) textView.setTextColor(Color.WHITE)
                    else textView.setTextColor(activity.getColorStateList(org.asr.runtime.R.color.btn_outline_text_nothing))
                }
            }
        } else if (
            view.id == org.asr.runtime.R.id.btnDetailsToggle ||
            view.id == org.asr.runtime.R.id.btnAbout ||
            view.id == org.asr.runtime.R.id.btnExitApp
        ) {
            if (isDark) {
                view.background = outlineButtonBackground(Color.WHITE)
                (view as? TextView)?.setTextColor(outlineButtonTextColors())
            } else if (originalBackground != null) {
                view.background = originalBackground.constantState?.newDrawable()?.mutate() ?: originalBackground
                (view as? TextView)?.setTextColor(
                    if (view.id == org.asr.runtime.R.id.btnAbout || view.id == org.asr.runtime.R.id.btnExitApp) {
                        activity.getColorStateList(org.asr.runtime.R.color.btn_outline_text_nothing)
                    } else {
                        ColorStateList.valueOf(originalTextColors[view] ?: Color.BLACK)
                    }
                )
            }
        } else if (view.id == org.asr.runtime.R.id.btnApplySensorSelection) {
            if (isDark) {
                view.background = ColorDrawable(Color.WHITE)
                (view as? TextView)?.setTextColor(Color.BLACK)
            } else if (originalBackground != null) {
                view.background = originalBackground.constantState?.newDrawable()?.mutate() ?: originalBackground
                (view as? TextView)?.setTextColor(Color.WHITE)
            }
        } else if (
            view.id == org.asr.runtime.R.id.runtimeCard ||
            view.id == org.asr.runtime.R.id.cardConnectedClientDetails ||
            view.id == org.asr.runtime.R.id.cardPermissionDetails ||
            view.id == org.asr.runtime.R.id.cardHardwareDetails ||
            view.id == org.asr.runtime.R.id.cardDiagnosticsDetails
        ) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * activity.resources.displayMetrics.density
                setColor(if (isDark) 0xFF171717.toInt() else Color.WHITE)
                val edge = if (isDark) 0xFF383838.toInt() else 0xFFE3E5E8.toInt()
                setStroke((activity.resources.displayMetrics.density + 0.5f).toInt(), edge)
            }
        } else if (view.id == org.asr.runtime.R.id.cardTcpTransport) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * activity.resources.displayMetrics.density
                setColor(if (isDark) 0xFF171717.toInt() else Color.WHITE)
                val edge = if (isDark) 0xFF383838.toInt() else 0xFFE3E5E8.toInt()
                setStroke((activity.resources.displayMetrics.density + 0.5f).toInt(), edge)
            }
        } else if (view.id == org.asr.runtime.R.id.cardRos2Dds) {
            view.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 8f * activity.resources.displayMetrics.density
                setColor(if (isDark) 0xFF171717.toInt() else Color.WHITE)
                val edge = if (isDark) 0xFF383838.toInt() else 0xFFE3E5E8.toInt()
                setStroke((activity.resources.displayMetrics.density + 0.5f).toInt(), edge)
            }
        } else if (view is EditText) {
            if (isDark) {
                view.setBackgroundResource(org.asr.runtime.R.drawable.input_underline_dark)
            } else if (originalBackground != null) {
                view.background = originalBackground.constantState?.newDrawable()?.mutate() ?: originalBackground
                view.backgroundTintList = originalBackgroundTints[view]
            }
        } else if (originalBackground is ColorDrawable) {
            val originalColor = originalBackground.color
            view.setBackgroundColor(if (isDark) Palette.darkBackgroundColors[originalColor] ?: originalColor else originalColor)
        } else if (!isDark && originalBackground != null) {
            view.background = originalBackground.constantState?.newDrawable()?.mutate() ?: originalBackground
            view.backgroundTintList = originalBackgroundTints[view]
        }

        if (view is SquareSlideSwitch) view.setDarkTheme(isDark)
        if (view is SegmentedBarView) {
            view.filledColor = if (isDark) 0xFFB8B8B8.toInt() else 0xFF555555.toInt()
            view.emptyColor = if (isDark) 0xFF383838.toInt() else 0xFFE3E5E8.toInt()
        }
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) applyToTree(view.getChildAt(index))
        }
    }

    private fun outline(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * activity.resources.displayMetrics.density
        setColor(Color.TRANSPARENT)
        setStroke((activity.resources.displayMetrics.density + 0.5f).toInt(), color)
    }

    private fun outlineButtonBackground(edge: Int) = StateListDrawable().apply {
        val density = activity.resources.displayMetrics.density
        val strokeWidth = (density + 0.5f).toInt()
        addState(
            intArrayOf(-android.R.attr.state_enabled),
            actionButtonShape(Color.TRANSPARENT, 0xFF555555.toInt(), strokeWidth)
        )
        addState(
            intArrayOf(android.R.attr.state_pressed),
            actionButtonShape(Color.BLACK, edge, strokeWidth)
        )
        addState(intArrayOf(), actionButtonShape(Color.TRANSPARENT, edge, strokeWidth))
    }

    private fun outlineButtonTextColors() = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf()
        ),
        intArrayOf(0xFF777777.toInt(), Color.WHITE, Color.WHITE)
    )

    private fun isActionButton(viewId: Int): Boolean = viewId == org.asr.runtime.R.id.btnStartRuntime ||
        viewId == org.asr.runtime.R.id.btnStopRuntime ||
        viewId == org.asr.runtime.R.id.btnRestartRuntime ||
        viewId == org.asr.runtime.R.id.btnConnectTransport ||
        viewId == org.asr.runtime.R.id.btnDisconnectTransport ||
        viewId == org.asr.runtime.R.id.btnReconnectTransport

    private fun actionButtonBackground(isPrimary: Boolean) = StateListDrawable().apply {
        val density = activity.resources.displayMetrics.density
        val strokeWidth = (density + 0.5f).toInt()
        val defaultFill = if (isPrimary) Color.WHITE else Color.TRANSPARENT
        val defaultStroke = Color.WHITE
        addState(
            intArrayOf(-android.R.attr.state_enabled),
            actionButtonShape(defaultFill, 0xFF555555.toInt(), strokeWidth)
        )
        addState(
            intArrayOf(android.R.attr.state_pressed),
            actionButtonShape(Color.BLACK, defaultStroke, strokeWidth)
        )
        addState(
            intArrayOf(),
            actionButtonShape(defaultFill, defaultStroke, strokeWidth)
        )
    }

    private fun actionButtonShape(fillColor: Int, strokeColor: Int, strokeWidth: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 4f * activity.resources.displayMetrics.density
        setColor(fillColor)
        setStroke(strokeWidth, strokeColor)
    }

    private fun actionButtonTextColors(isPrimary: Boolean) = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_pressed),
            intArrayOf()
        ),
        intArrayOf(
            0xFF777777.toInt(),
            Color.WHITE,
            if (isPrimary) Color.BLACK else Color.WHITE
        )
    )

    private object Palette {
        val darkTextColors = mapOf(
            0xFF000000.toInt() to 0xFFFFFFFF.toInt(),
            0xFF1A1A1A.toInt() to 0xFFFFFFFF.toInt(),
            0xFF999999.toInt() to 0xFFAAAAAA.toInt(),
            0xFF2D3748.toInt() to 0xFFE2E8F0.toInt(),
            0xFF1A202C.toInt() to 0xFFF7FAFC.toInt(),
            0xFF718096.toInt() to 0xFFA0AEC0.toInt(),
            0xFF4A5568.toInt() to 0xFFCBD5E0.toInt(),
            0xFF1B1F23.toInt() to 0xFFF7FAFC.toInt(),
            0xFF6B7280.toInt() to 0xFF9CA3AF.toInt()
        )
        val darkBackgroundColors = mapOf(
            0xFFF5F7FA.toInt() to 0xFF0B0B0B.toInt(),
            0xFFF5F5F5.toInt() to 0xFF0B0B0B.toInt(),
            0xFFFFFFFF.toInt() to 0xFF171717.toInt(),
            0xFFF7FAFC.toInt() to 0xFF202020.toInt(),
            0xFFEDF2F7.toInt() to 0xFF282828.toInt(),
            0xFFE2E8F0.toInt() to 0xFF383838.toInt(),
            0xFFEBF8FF.toInt() to 0xFF202020.toInt(),
            0xFFE3E5E8.toInt() to 0xFF383838.toInt(),
            0xFFFFF4E5.toInt() to 0xFF332719.toInt()
        )
    }
}
