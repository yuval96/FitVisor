package com.example.fitvisor__demo.exercises.pushup

import com.example.fitvisor__demo.exercises.ExerciseFrameOutput
import com.example.fitvisor__demo.exercises.SideViewAnalyzer
import com.example.fitvisor__demo.kinematics.KinematicCalculator
import com.example.fitvisor__demo.model.OverlayMetrics
import com.example.fitvisor__demo.pose.PoseLandmarkIndices
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Side-view push-up analyzer. Metrics: elbow angle (wrist-elbow-shoulder),
 * body-line angle (shoulder-hip-ankle) and the hip-to-ankle tilt relative to
 * horizontal.
 */
class PushUpAnalyzer(
    poseQualityEnabled: () -> Boolean = { false }
) : SideViewAnalyzer(poseQualityEnabled) {

    private val engine = PushUpRuleEngine()

    override fun leftLandmarks(landmarks: List<NormalizedLandmark>) = listOf(
        landmarks[PoseLandmarkIndices.L_SH],
        landmarks[PoseLandmarkIndices.L_ELBOW],
        landmarks[PoseLandmarkIndices.L_WRIST],
        landmarks[PoseLandmarkIndices.L_HIP],
        landmarks[PoseLandmarkIndices.L_ANKLE]
    )

    override fun rightLandmarks(landmarks: List<NormalizedLandmark>) = listOf(
        landmarks[PoseLandmarkIndices.R_SH],
        landmarks[PoseLandmarkIndices.R_ELBOW],
        landmarks[PoseLandmarkIndices.R_WRIST],
        landmarks[PoseLandmarkIndices.R_HIP],
        landmarks[PoseLandmarkIndices.R_ANKLE]
    )

    override fun analyzeSide(
        useLeft: Boolean,
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): ExerciseFrameOutput {
        val shoulder = landmarks[if (useLeft) PoseLandmarkIndices.L_SH else PoseLandmarkIndices.R_SH]
        val elbow = landmarks[if (useLeft) PoseLandmarkIndices.L_ELBOW else PoseLandmarkIndices.R_ELBOW]
        val wrist = landmarks[if (useLeft) PoseLandmarkIndices.L_WRIST else PoseLandmarkIndices.R_WRIST]
        val hip = landmarks[if (useLeft) PoseLandmarkIndices.L_HIP else PoseLandmarkIndices.R_HIP]
        val ankle = landmarks[if (useLeft) PoseLandmarkIndices.L_ANKLE else PoseLandmarkIndices.R_ANKLE]

        val elbowAngle = KinematicCalculator.calculateAngle(
            wrist, elbow, shoulder, imageWidth, imageHeight
        )
        val bodyLineAngle = KinematicCalculator.bodyLineAngle(
            shoulder, hip, ankle, imageWidth, imageHeight
        )
        val horizontalAngle = KinematicCalculator.angleFromHorizontal(
            hip, ankle, imageWidth, imageHeight
        )

        val result = engine.processFrame(elbowAngle, bodyLineAngle, horizontalAngle)

        val metrics = OverlayMetrics(
            values = linkedMapOf(
                "Elbow" to elbowAngle,
                "Body line" to bodyLineAngle,
                "Horizontal" to horizontalAngle
            ),
            warning = result.warning,
            phase = result.phaseName
        )
        return ExerciseFrameOutput(result, metrics)
    }

    override fun resetEngine() = engine.reset()
}
