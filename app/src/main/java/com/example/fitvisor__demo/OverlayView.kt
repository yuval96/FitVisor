package com.example.fitvisor__demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import kotlin.math.roundToInt

/**
 * Feedback UI Module.
 * Responsible for rendering the live skeleton and real-time corrective feedback.
 *
 * Metrics are exercise-agnostic: the overlay simply renders whatever labelled
 * angle values, phase and warning the current exercise provides via
 * [OverlayMetrics], instead of being hardcoded for knee/torso.
 */
class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private val mapper = CoordinateMapper()
    private var smoothedLandmarks: List<NormalizedLandmark>? = null

    private var metrics: OverlayMetrics = OverlayMetrics.EMPTY

    private var isDebugMode = false

    fun setDebugEnabled(enabled: Boolean) {
        isDebugMode = enabled
        invalidate()
    }

    private val pointPaint = Paint()
    private val linePaint = Paint()
    private val angleTextPaint = Paint()
    private val debugPaint = Paint()

    init {
        initPaints()
    }

    private fun initPaints() {
        pointPaint.color = Color.YELLOW
        pointPaint.style = Paint.Style.FILL
        pointPaint.strokeWidth = 10f

        linePaint.color = Color.GREEN
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 8f

        angleTextPaint.color = Color.CYAN
        angleTextPaint.textSize = 50f

        debugPaint.color = Color.MAGENTA
        debugPaint.style = Paint.Style.STROKE
        debugPaint.strokeWidth = 4f
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)
        if (!isDebugMode) return

        if (isDebugMode) {
            canvas.drawRect(mapper.getPreviewBounds(), debugPaint)
        }

        smoothedLandmarks?.let { landmarks ->
            PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
                val start = landmarks[connection.start()]
                val end = landmarks[connection.end()]

                canvas.drawLine(
                    mapper.mapX(start.x()), mapper.mapY(start.y()),
                    mapper.mapX(end.x()), mapper.mapY(end.y()),
                    linePaint
                )
            }

            for (landmark in landmarks) {
                canvas.drawPoint(mapper.mapX(landmark.x()), mapper.mapY(landmark.y()), pointPaint)
            }
        }

        drawMetrics(canvas)

    }

    /** Draws each metric line plus the phase, stacked up from the bottom-left. */
    private fun drawMetrics(canvas: Canvas) {
        val lines = ArrayList<String>()
        for ((label, value) in metrics.values) {
            lines.add("$label: ${formatAngle(value)}")
        }
        metrics.phase?.let { lines.add("Phase: $it") }

        val lineHeight = 55f
        var y = height * 0.65f
        // Render bottom-up so the first metric sits at the top of the stack.
        for (i in lines.indices.reversed()) {
            canvas.drawText(lines[i], 50f, y, angleTextPaint)
            y -= lineHeight
        }
    }

    private fun formatAngle(value: Double): String {
        return if (value.isNaN()) "--" else "${value.roundToInt()}°"
    }

    fun setResults(
        landmarks: List<NormalizedLandmark>?,
        imageHeight: Int,
        imageWidth: Int,
        metrics: OverlayMetrics
    ) {
        this.smoothedLandmarks = landmarks
        this.metrics = metrics

        // Sync mapper with layout - assume mirrored for front camera
        mapper.updateConfig(imageWidth, imageHeight, width, height, true)
        postInvalidateOnAnimation()
    }
}
