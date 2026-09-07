package com.example.fitvisor__demo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
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

    /**
     * The exact camera frame the current [smoothedLandmarks] were computed from.
     * Drawing it here (instead of relying on the live PreviewView) keeps the body
     * image and the skeleton in perfect sync, so the skeleton never trails the
     * live movement — at the cost of the shown image lagging reality by the
     * pipeline latency, which is acceptable for form feedback.
     */
    private var frameBitmap: Bitmap? = null

    private var metrics: OverlayMetrics = OverlayMetrics.EMPTY

    private var isDebugMode = false

    /** When false, no synced frame or skeleton is drawn; the live preview shows through. */
    private var showSkeleton = true

    fun setDebugEnabled(enabled: Boolean) {
        isDebugMode = enabled
        invalidate()
    }

    /** Toggles the synced camera frame + skeleton, independently of debug mode. */
    fun setShowSkeleton(enabled: Boolean) {
        showSkeleton = enabled
        invalidate()
    }

    private val pointPaint = Paint()
    private val linePaint = Paint()
    private val angleTextPaint = Paint()
    private val debugPaint = Paint()
    private val framePaint = Paint().apply { isFilterBitmap = true; isAntiAlias = true }
    private val frameMatrix = Matrix()

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

        // When the skeleton is on, paint the synced frame + skeleton. When off,
        // draw nothing here so the zero-latency live PreviewView shows through.
        if (showSkeleton) {
            frameBitmap?.let { drawFrame(canvas, it) }
            drawSkeleton(canvas)
        }

        if (isDebugMode) {
            canvas.drawRect(mapper.getPreviewBounds(), debugPaint)
            drawMetrics(canvas)
        }
    }

    /** Blits the analyzed frame into the same bounds/mirroring the mapper uses for landmarks. */
    private fun drawFrame(canvas: Canvas, bitmap: Bitmap) {
        if (bitmap.width <= 0 || bitmap.height <= 0) return
        val bounds = mapper.getPreviewBounds()
        if (bounds.width() <= 0f || bounds.height() <= 0f) return

        frameMatrix.reset()
        frameMatrix.setScale(bounds.width() / bitmap.width, bounds.height() / bitmap.height)
        frameMatrix.postTranslate(bounds.left, bounds.top)
        if (mapper.isMirrored()) {
            frameMatrix.postScale(-1f, 1f, bounds.centerX(), bounds.centerY())
        }
        canvas.drawBitmap(bitmap, frameMatrix, framePaint)
    }

    private fun drawSkeleton(canvas: Canvas) {
        val landmarks = smoothedLandmarks ?: return
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
        frame: Bitmap?,
        imageHeight: Int,
        imageWidth: Int,
        metrics: OverlayMetrics
    ) {
        this.smoothedLandmarks = landmarks
        this.frameBitmap = frame
        this.metrics = metrics

        // Sync mapper with layout - assume mirrored for front camera
        mapper.updateConfig(imageWidth, imageHeight, width, height, true)
        postInvalidateOnAnimation()
    }
}
