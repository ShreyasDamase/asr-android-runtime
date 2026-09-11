package com.asr.runtime.ui

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import com.asr.runtime.R

/**
 * Reusable dot + label used for every status surface in the app:
 * Runtime state, TCP state, ROS2 state, per-sensor Hardware/Runtime/ROS rows.
 *
 * One view class = one place that owns "how a status looks", so a card's
 * XML never needs its own if/else for color. Bind it from a ViewModel like:
 *
 *   statusRuntime.setStatus("RUNNING", R.color.asr_status_running)
 *   statusRuntime.setLabel("Runtime") // optional, e.g. inside a sensor row
 */
class StatusIndicatorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    private val dot: ImageView
    private val tvStatus: TextView
    private val tvLabel: TextView

    init {
        inflate(context, R.layout.view_status_indicator, this)
        dot = findViewById(R.id.dot)
        tvStatus = findViewById(R.id.tvStatus)
        tvLabel = findViewById(R.id.tvLabel)

        context.theme.obtainStyledAttributes(attrs, R.styleable.StatusIndicatorView, 0, 0).apply {
            try {
                val label = getString(R.styleable.StatusIndicatorView_label)
                if (!label.isNullOrEmpty()) setLabel(label)
            } finally {
                recycle()
            }
        }
    }

    /** Sets the status text and tints the dot with [colorRes]. */
    fun setStatus(text: String, @ColorRes colorRes: Int) {
        tvStatus.text = text
        val color = ContextCompat.getColor(context, colorRes)
        val wrapped = DrawableCompat.wrap(dot.drawable.mutate())
        DrawableCompat.setTint(wrapped, color)
        dot.setImageDrawable(wrapped)
    }

    /** Optional small caption shown before the dot, e.g. "Hardware", "ROS". */
    fun setLabel(text: String?) {
        if (text.isNullOrEmpty()) {
            tvLabel.visibility = GONE
        } else {
            tvLabel.text = text
            tvLabel.visibility = VISIBLE
        }
    }
}
