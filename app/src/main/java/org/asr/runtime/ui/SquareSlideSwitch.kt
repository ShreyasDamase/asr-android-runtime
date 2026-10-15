package org.asr.runtime.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Build
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.CompoundButton
import androidx.core.animation.doOnEnd
import kotlin.math.abs

/** A sharp-cornered, tactile on/off slider for the ASR instrument UI. */
class SquareSlideSwitch @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : CompoundButton(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create("monospace", Typeface.BOLD)
        textSize = 10f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
    }
    private val bounds = RectF()
    private var thumbFraction = 0f
    private var downX = 0f
    private var dragging = false
    private var darkTheme = false
    private var moveAnimator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
        setPadding(0, 0, 0, 0)
        minimumWidth = dp(TRACK_WIDTH_DP)
        minimumHeight = dp(44)
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    fun setDarkTheme(dark: Boolean) {
        darkTheme = dark
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val desiredWidth = dp(TRACK_WIDTH_DP) + paddingLeft + paddingRight
        val desiredHeight = dp(44) + paddingTop + paddingBottom
        setMeasuredDimension(
            resolveSize(desiredWidth.coerceAtLeast(minimumWidth), widthMeasureSpec),
            resolveSize(desiredHeight.coerceAtLeast(minimumHeight), heightMeasureSpec)
        )
    }

    override fun onDraw(canvas: Canvas) {
        val stroke = dp(1).toFloat()
        val inset = stroke / 2f
        val trackHeight = dp(TRACK_HEIGHT_DP).toFloat()
        val trackTop = (height - trackHeight) / 2f
        bounds.set(inset, trackTop + inset, width - inset, trackTop + trackHeight - inset)
        trackPaint.style = Paint.Style.FILL
        trackPaint.color = if (isChecked) COLOR_ON else if (darkTheme) COLOR_OFF_DARK else COLOR_OFF
        canvas.drawRect(bounds, trackPaint)

        trackPaint.style = Paint.Style.STROKE
        trackPaint.strokeWidth = stroke
        trackPaint.color = if (darkTheme) COLOR_OUTLINE_DARK else COLOR_OUTLINE
        canvas.drawRect(bounds, trackPaint)

        val margin = dp(2).toFloat()
        val thumbSize = trackHeight - margin * 2f
        val travel = (width - margin * 2f - thumbSize).coerceAtLeast(0f)
        val fraction = if (!dragging && moveAnimator == null) {
            if (isChecked) 1f else 0f
        } else {
            thumbFraction
        }
        val thumbLeft = margin + travel * fraction
        val thumbTop = trackTop + margin
        thumbPaint.color = if (isChecked) COLOR_THUMB_ON else if (darkTheme) COLOR_THUMB_OFF_DARK else COLOR_THUMB_OFF
        canvas.drawRect(thumbLeft, thumbTop, thumbLeft + thumbSize, thumbTop + thumbSize, thumbPaint)

        labelPaint.color = if (isChecked) COLOR_LABEL_ON else if (darkTheme) COLOR_LABEL_OFF_DARK else COLOR_LABEL_OFF
        val label = if (isChecked) "ON" else "OFF"
        val labelCenterX = if (isChecked) {
            (inset + thumbLeft) / 2f
        } else {
            (thumbLeft + thumbSize + width - inset) / 2f
        }
        val labelCenterY = trackTop + trackHeight / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f
        canvas.drawText(label, labelCenterX, labelCenterY, labelPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                moveAnimator?.cancel()
                moveAnimator = null
                downX = event.x
                thumbFraction = if (isChecked) 1f else 0f
                dragging = false
                isPressed = true
                TactileFeedbackHelper.performTactileTick(this)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (abs(event.x - downX) > touchSlop) dragging = true
                if (dragging) {
                    updateThumbFromTouch(event.x)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!dragging && (event.x < 0f || event.x > width || event.y < 0f || event.y > height)) {
                    dragging = false
                    isPressed = false
                    animateThumb(thumbFraction, if (isChecked) 1f else 0f)
                    parent?.requestDisallowInterceptTouchEvent(false)
                    return true
                }
                if (dragging) updateThumbFromTouch(event.x)
                val changedTo = if (dragging) thumbFraction >= 0.5f else !isChecked
                val fromFraction = thumbFraction
                dragging = false
                isPressed = false
                val didChange = changedTo != isChecked
                if (didChange) {
                    isChecked = changedTo
                    TactileFeedbackHelper.playSliderSound(this, changedTo)
                }
                animateThumb(fromFraction, if (isChecked) 1f else 0f)
                sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                val wasDragging = dragging
                val changedTo = if (wasDragging) thumbFraction >= 0.5f else isChecked
                val fromFraction = thumbFraction
                val didChange = changedTo != isChecked
                dragging = false
                isPressed = false
                if (didChange) {
                    isChecked = changedTo
                    TactileFeedbackHelper.playSliderSound(this, changedTo)
                }
                animateThumb(fromFraction, if (isChecked) 1f else 0f)
                if (didChange) {
                    sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
                }
                parent?.requestDisallowInterceptTouchEvent(false)
                return true
            }
        }
        return true
    }

    override fun performClick(): Boolean {
        val fromFraction = if (isChecked) 1f else 0f
        isChecked = !isChecked
        TactileFeedbackHelper.playSliderSound(this, isChecked)
        animateThumb(fromFraction, if (isChecked) 1f else 0f)
        sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_CLICKED)
        return true
    }

    override fun onInitializeAccessibilityNodeInfo(info: android.view.accessibility.AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.Switch"
        info.isCheckable = true
        info.isChecked = isChecked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            info.stateDescription = if (isChecked) "On" else "Off"
        }
    }

    private fun animateThumb(from: Float, to: Float) {
        moveAnimator?.cancel()
        if (from == to) {
            thumbFraction = to
            moveAnimator = null
            invalidate()
            return
        }
        moveAnimator = ValueAnimator.ofFloat(from, to).apply {
            duration = 140L
            addUpdateListener {
                thumbFraction = it.animatedValue as Float
                invalidate()
            }
            doOnEnd {
                thumbFraction = to
                moveAnimator = null
                invalidate()
            }
            start()
        }
    }

    private fun updateThumbFromTouch(x: Float) {
        val margin = dp(2).toFloat()
        val thumbSize = dp(TRACK_HEIGHT_DP).toFloat() - margin * 2f
        val travel = (width - margin * 2f - thumbSize).coerceAtLeast(1f)
        thumbFraction = ((x - margin - thumbSize / 2f) / travel).coerceIn(0f, 1f)
        invalidate()
    }

    private fun dp(value: Int): Int = (value * density + 0.5f).toInt()

    private companion object {
        const val TRACK_WIDTH_DP = 76
        const val TRACK_HEIGHT_DP = 36
        const val COLOR_ON = 0xFF000000.toInt()
        const val COLOR_OFF = 0xFFF0F0F0.toInt()
        const val COLOR_OUTLINE = 0xFF000000.toInt()
        const val COLOR_THUMB_ON = 0xFFFFFFFF.toInt()
        const val COLOR_THUMB_OFF = 0xFF000000.toInt()
        const val COLOR_LABEL_ON = 0xFFFFFFFF.toInt()
        const val COLOR_LABEL_OFF = 0xFF000000.toInt()
        const val COLOR_OFF_DARK = 0xFF282828.toInt()
        const val COLOR_OUTLINE_DARK = 0xFFEEEEEE.toInt()
        const val COLOR_THUMB_OFF_DARK = 0xFFFFFFFF.toInt()
        const val COLOR_LABEL_OFF_DARK = 0xFFFFFFFF.toInt()
    }
}
