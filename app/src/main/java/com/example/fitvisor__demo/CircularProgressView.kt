package com.example.fitvisor__demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.min

/**
 * Simple circular determinate progress ring used for the workout "Form Score".
 * Draws a full track ring plus a progress arc starting at the top. The progress
 * value (0..100) and its colour are set from code so the summary can colour it
 * by result (green / warning / error).
 */
class CircularProgressView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var progress: Float = 0f
    private var strokeWidthPx: Float = dp(12f)

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val progressPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }

    private val arcBounds = RectF()

    init {
        trackPaint.color = ContextCompat.getColor(context, R.color.bg_elevated)
        progressPaint.color = ContextCompat.getColor(context, R.color.brand_green)

        attrs?.let {
            val a = context.obtainStyledAttributes(it, R.styleable.CircularProgressView)
            try {
                progress = a.getFloat(R.styleable.CircularProgressView_cpvProgress, 0f)
                strokeWidthPx = a.getDimension(
                    R.styleable.CircularProgressView_cpvStrokeWidth, strokeWidthPx
                )
                if (a.hasValue(R.styleable.CircularProgressView_cpvProgressColor)) {
                    progressPaint.color =
                        a.getColor(R.styleable.CircularProgressView_cpvProgressColor, progressPaint.color)
                }
                if (a.hasValue(R.styleable.CircularProgressView_cpvTrackColor)) {
                    trackPaint.color =
                        a.getColor(R.styleable.CircularProgressView_cpvTrackColor, trackPaint.color)
                }
            } finally {
                a.recycle()
            }
        }

        trackPaint.strokeWidth = strokeWidthPx
        progressPaint.strokeWidth = strokeWidthPx
    }

    /** @param value form score, 0..100 (clamped). */
    fun setProgress(value: Float) {
        progress = value.coerceIn(0f, 100f)
        invalidate()
    }

    fun setProgressColor(color: Int) {
        progressPaint.color = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val size = min(width, height).toFloat()
        val half = strokeWidthPx / 2f
        val left = (width - size) / 2f + half
        val top = (height - size) / 2f + half
        arcBounds.set(left, top, left + size - strokeWidthPx, top + size - strokeWidthPx)

        // Full track ring.
        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        // Progress arc, clockwise from the top.
        val sweep = 360f * (progress / 100f)
        canvas.drawArc(arcBounds, -90f, sweep, false, progressPaint)
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
