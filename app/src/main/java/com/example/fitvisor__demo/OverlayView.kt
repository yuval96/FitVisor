package com.example.fitvisor__demo

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class OverlayView(context: Context?, attrs: AttributeSet?) : View(context, attrs) {

    private var results: PoseLandmarkerResult? = null
    private var scaleFactor: Float = 1f
    private var imageWidth: Int = 1
    private var imageHeight: Int = 1
    private var offsetX: Float = 0f
    private var offsetY: Float = 0f

    private var kneeAngle: Double = 0.0
    private var torsoAngle: Double = 0.0
    private var repCount: Int = 0
    private var leftKneeStatus: String = "N/A"
    private var rightKneeStatus: String = "N/A"
    private var torsoStatus: String = ""

    private val pointPaint = Paint()
    private val linePaint = Paint()
    private val repTextPaint = Paint()
    private val otherTextPaint = Paint()
    private val leftKneeTextPaint = Paint()
    private val rightKneeTextPaint = Paint()
    private val torsoTextPaint = Paint()


    init {
        initPaints()
    }

    fun clear() {
        results = null
        pointPaint.reset()
        linePaint.reset()
        repTextPaint.reset()
        otherTextPaint.reset()
        leftKneeTextPaint.reset()
        rightKneeTextPaint.reset()
        torsoTextPaint.reset()
        invalidate()
        initPaints()
    }

    private fun initPaints() {
        pointPaint.color = Color.YELLOW
        pointPaint.style = Paint.Style.FILL
        pointPaint.strokeWidth = 8f

        linePaint.color = Color.GREEN
        linePaint.style = Paint.Style.STROKE
        linePaint.strokeWidth = 6f

        repTextPaint.color = Color.WHITE
        repTextPaint.textSize = 100f
        repTextPaint.textAlign = Paint.Align.LEFT

        otherTextPaint.color = Color.WHITE
        otherTextPaint.textSize = 50f
        otherTextPaint.textAlign = Paint.Align.LEFT

        leftKneeTextPaint.textSize = 50f
        leftKneeTextPaint.textAlign = Paint.Align.LEFT

        rightKneeTextPaint.textSize = 50f
        rightKneeTextPaint.textAlign = Paint.Align.LEFT

        torsoTextPaint.textSize = 50f
        torsoTextPaint.textAlign = Paint.Align.LEFT
    }

    override fun draw(canvas: Canvas) {
        super.draw(canvas)

        // Draw skeleton
        results?.let { poseLandmarkerResult ->
            for (landmark in poseLandmarkerResult.landmarks()) {
                // Draw lines and points for the mirrored skeleton
                PoseLandmarker.POSE_LANDMARKS.forEach { connection ->
                    val start = landmark[connection.start()]
                    val end = landmark[connection.end()]
                    
                    val startX = (1f - start.x()) * imageWidth * scaleFactor + offsetX
                    val startY = start.y() * imageHeight * scaleFactor + offsetY
                    val endX = (1f - end.x()) * imageWidth * scaleFactor + offsetX
                    val endY = end.y() * imageHeight * scaleFactor + offsetY

                    canvas.drawLine(startX, startY, endX, endY, linePaint)
                }

                for (normalizedLandmark in landmark) {
                    val x = (1f - normalizedLandmark.x()) * imageWidth * scaleFactor + offsetX
                    val y = normalizedLandmark.y() * imageHeight * scaleFactor + offsetY
                    canvas.drawPoint(x, y, pointPaint)
                }
            }
        }

        // Draw text (not mirrored)
        canvas.drawText("Reps: $repCount", 50f, 120f, repTextPaint)
        canvas.drawText("Knee Angle: ${String.format("%.2f", kneeAngle)}", 50f, 220f, otherTextPaint)

        // Set colors for status
        torsoTextPaint.color = if (torsoStatus == "Torso OK") Color.GREEN else Color.RED
        leftKneeTextPaint.color = if (leftKneeStatus == "OK") Color.GREEN else Color.RED
        rightKneeTextPaint.color = if (rightKneeStatus == "OK") Color.GREEN else Color.RED

        // Draw status
        canvas.drawText(torsoStatus, 50f, 320f, torsoTextPaint)
        canvas.drawText("L Knee: $leftKneeStatus", 50f, 420f, leftKneeTextPaint)
        canvas.drawText("R Knee: $rightKneeStatus", 50f, 520f, rightKneeTextPaint)
    }

    fun setResults(
        poseLandmarkerResult: PoseLandmarkerResult,
        imageHeight: Int,
        imageWidth: Int,
        kneeAngle: Double,
        torsoAngle: Double,
        repCount: Int,
        leftKneeStatus: String,
        rightKneeStatus: String,
        torsoStatus: String
    ) {
        results = poseLandmarkerResult
        this.imageHeight = imageHeight
        this.imageWidth = imageWidth
        this.kneeAngle = kneeAngle
        this.torsoAngle = torsoAngle
        this.repCount = repCount
        this.leftKneeStatus = leftKneeStatus
        this.rightKneeStatus = rightKneeStatus
        this.torsoStatus = torsoStatus

        val viewWidth = width
        val viewHeight = height

        val imageAspectRatio = imageWidth.toFloat() / imageHeight.toFloat()
        val viewAspectRatio = viewWidth.toFloat() / viewHeight.toFloat()

        var drawScale = 1f
        if (imageAspectRatio > viewAspectRatio) {
            drawScale = viewWidth.toFloat() / imageWidth
        } else {
            drawScale = viewHeight.toFloat() / imageHeight
        }

        scaleFactor = drawScale
        offsetX = (viewWidth - imageWidth * scaleFactor) / 2
        offsetY = (viewHeight - imageHeight * scaleFactor) / 2

        invalidate()
    }
}