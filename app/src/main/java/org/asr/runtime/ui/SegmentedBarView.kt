package org.asr.runtime.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import android.os.SystemClock

/**
 * Nothing UI signature segmented progress bar.
 * Renders discrete rectangular blocks with configurable count, filled blocks,
 * and Nothing status colors without any 3rd party dependencies.
 */
class SegmentedBarView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var totalSegments: Int = 20
        set(value) {
            field = value.coerceAtLeast(1)
            invalidate()
        }

    var filledSegments: Int = 0
        set(value) {
            field = value.coerceIn(0, totalSegments)
            invalidate()
        }

    var filledColor: Int = Color.parseColor("#555555") // Neutral Nothing-style activity color
        set(value) {
            field = value
            filledPaint.color = value
            invalidate()
        }

    var emptyColor: Int = Color.parseColor("#E3E5E8") // Nothing empty block gray
        set(value) {
            field = value
            emptyPaint.color = value
            invalidate()
        }

    var gapPx: Float = 4f // 2-4px gap between blocks
        set(value) {
            field = value
            invalidate()
        }

    var cornerRadiusPx: Float = 0f // Nothing uses square-ended blocks by default

    /** A short moving block window for work with no meaningful percentage. */
    var indeterminate: Boolean = false
        private set

    private var animationStartedAt = 0L
    private val animationFrame = object : Runnable {
        override fun run() {
            if (indeterminate && isShown) {
                invalidate()
                postDelayed(this, 90L)
            }
        }
    }

    private val filledPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = filledColor
        style = Paint.Style.FILL
    }

    private val emptyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = emptyColor
        style = Paint.Style.FILL
    }

    private val rect = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val minHeight = (10 * resources.displayMetrics.density).toInt()
        val height = resolveSize(minHeight, heightMeasureSpec)
        val width = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (totalSegments <= 0 || width <= 0 || height <= 0) return

        val totalGaps = (totalSegments - 1) * gapPx
        val availableWidth = (width - paddingLeft - paddingRight) - totalGaps
        if (availableWidth <= 0) return

        val segmentWidth = availableWidth / totalSegments
        val top = paddingTop.toFloat()
        val bottom = (height - paddingBottom).toFloat()

        var left = paddingLeft.toFloat()

        val activeCount = (totalSegments / 4).coerceAtLeast(2)
        val frame = if (indeterminate) {
            ((SystemClock.uptimeMillis() - animationStartedAt) / 90L).toInt() % totalSegments
        } else -1
        for (i in 0 until totalSegments) {
            val right = left + segmentWidth
            rect.set(left, top, right, bottom)
            val active = if (indeterminate) {
                (i - frame + totalSegments) % totalSegments < activeCount
            } else i < filledSegments
            val paint = if (active) filledPaint else emptyPaint

            if (cornerRadiusPx > 0f) {
                canvas.drawRoundRect(rect, cornerRadiusPx, cornerRadiusPx, paint)
            } else {
                canvas.drawRect(rect, paint)
            }
            left = right + gapPx
        }
    }

    /**
     * Set progress as a percentage float between 0.0f and 1.0f.
     */
    fun setProgress(progressRatio: Float, statusColor: Int? = null) {
        stopIndeterminate()
        val clamped = progressRatio.coerceIn(0f, 1f)
        filledSegments = (clamped * totalSegments).toInt()
        statusColor?.let { filledColor = it }
    }

    fun startIndeterminate() {
        if (indeterminate) return
        indeterminate = true
        animationStartedAt = SystemClock.uptimeMillis()
        removeCallbacks(animationFrame)
        if (isShown) postOnAnimation(animationFrame)
        invalidate()
    }

    fun stopIndeterminate() {
        if (!indeterminate) return
        indeterminate = false
        removeCallbacks(animationFrame)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (indeterminate) postOnAnimation(animationFrame)
    }

    override fun onDetachedFromWindow() {
        removeCallbacks(animationFrame)
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        removeCallbacks(animationFrame)
        if (indeterminate && isShown) postOnAnimation(animationFrame)
    }
}
