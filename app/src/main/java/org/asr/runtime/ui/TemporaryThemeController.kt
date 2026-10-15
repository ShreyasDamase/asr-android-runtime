package org.asr.runtime.ui

import android.app.Activity
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.TextView
import android.widget.Button
import java.util.IdentityHashMap

/** Applies an in-memory theme preview and restores the original screen palette exactly. */
class TemporaryThemeController(private val activity: Activity) {
    var isDark: Boolean = false
        private set

    private val originalTextColors = IdentityHashMap<TextView, Int>()
    private val originalHintColors = IdentityHashMap<EditText, Int>()
    private val originalBackgrounds = IdentityHashMap<View, android.graphics.drawable.Drawable?>()
    private val originalBackgroundTints = IdentityHashMap<View, ColorStateList?>()

    fun toggle(root: View) {
        isDark = !isDark
        applyToTree(root)
        activity.window.statusBarColor = if (isDark) Color.BLACK else Color.rgb(245, 247, 250)
        @Suppress("DEPRECATION")
        run {
            activity.window.decorView.systemUiVisibility = if (isDark) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    private fun applyToTree(view: View) {
        if (view is TextView) {
            val original = originalTextColors.getOrPut(view) { view.currentTextColor }
            view.setTextColor(if (isDark) darkTextColors[original] ?: original else original)
            if (view is EditText) {
                val originalHint = originalHintColors.getOrPut(view) { view.currentHintTextColor }
                view.setHintTextColor(if (isDark) darkTextColors[originalHint] ?: originalHint else originalHint)
            }
        }

        originalBackgrounds.putIfAbsent(view, view.background?.constantState?.newDrawable()?.mutate())
        originalBackgroundTints.putIfAbsent(view, view.backgroundTintList)
        val originalBackground = originalBackgrounds[view]
        if (view.id == org.asr.runtime.R.id.btnThemeToggle) {
            val edge = if (isDark) Color.WHITE else Color.BLACK
            view.background = outline(edge)
        } else if (
            view.id == org.asr.runtime.R.id.btnDetailsToggle ||
            view.id == org.asr.runtime.R.id.btnAbout
        ) {
            if (isDark) {
                view.background = outline(Color.WHITE)
                (view as? TextView)?.setTextColor(Color.WHITE)
            } else if (originalBackground != null) {
                view.background = originalBackground.constantState?.newDrawable()?.mutate() ?: originalBackground
                (view as? TextView)?.setTextColor(originalTextColors[view] ?: Color.BLACK)
            }
        } else if (view.id == org.asr.runtime.R.id.btnStopRuntime || view.id == org.asr.runtime.R.id.btnRestartRuntime) {
            if (isDark) view.background = outline(Color.WHITE)
            else if (originalBackground != null) view.background = originalBackground.constantState?.newDrawable()?.mutate() ?: originalBackground
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
        } else if (
            view.id == org.asr.runtime.R.id.btnConnectTransport ||
            view.id == org.asr.runtime.R.id.btnDisconnectTransport ||
            view.id == org.asr.runtime.R.id.btnReconnectTransport
        ) {
            if (isDark) view.background = outline(Color.WHITE)
            else if (originalBackground != null) view.background = originalBackground.constantState?.newDrawable()?.mutate() ?: originalBackground
        } else if (originalBackground is ColorDrawable) {
            val originalColor = originalBackground.color
            view.setBackgroundColor(if (isDark) darkBackgroundColors[originalColor] ?: originalColor else originalColor)
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

    private companion object {
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
