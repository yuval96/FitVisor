package com.example.fitvisor__demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

/**
 * Feedback UI Module.
 * Responsible for rendering the live skeleton and real-time corrective feedback.
 */
class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private val mapper = CoordinateMapper()
    private var smoothedLandmarks: List<NormalizedLandmark>? = null
    
    private var kneeAngle: Double = 0.0
    private var torsoAngle: Double = 0.0
    private var warningMessage: String? = null
    
    private var isDebugMode = false

    private val pointPaint = Paint()
    private val linePaint = Paint()
    private val warningTextPaint = Paint()
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

        warningTextPaint.color = Color.RED
        warningTextPaint.textSize = 80f
        warningTextPaint.isFakeBoldText = true
        warningTextPaint.textAlign = Paint.Align.CENTER

        angleTextPaint.color = Color.CYAN
        angleTextPaint.textSize = 50f
        
        debugPaint.color = Color.MAGENTA
        debugPaint.style = Paint.Style.STROKE
        debugPaint.strokeWidth = 4f
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

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

        // Real-time feedback
        canvas.drawText("Knee: ${kneeAngle.toInt()}°", 50f, height - 100f, angleTextPaint)
        canvas.drawText("Torso: ${torsoAngle.toInt()}°", 50f, height - 50f, angleTextPaint)

        warningMessage?.let {
            canvas.drawText(it, width / 2f, height - 250f, warningTextPaint)
        }
    }

    fun setResults(
        landmarks: List<NormalizedLandmark>?,
        imageHeight: Int,
        imageWidth: Int,
        kneeAngle: Double,
        torsoAngle: Double,
        warning: String?
    ) {
        this.smoothedLandmarks = landmarks
        this.kneeAngle = kneeAngle
        this.torsoAngle = torsoAngle
        this.warningMessage = warning

        // Sync mapper with layout - assume mirrored for front camera
        mapper.updateConfig(imageWidth, imageHeight, width, height, true)
        invalidate()
    }
}
