package com.example.fitvisor__demo.exercises.bicepscurl

import com.example.fitvisor__demo.exercises.ExerciseFrameOutput
import com.example.fitvisor__demo.exercises.SideViewAnalyzer
import com.example.fitvisor__demo.kinematics.KinematicCalculator
import com.example.fitvisor__demo.model.OverlayMetrics
import com.example.fitvisor__demo.pose.PoseLandmarkIndices
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Side-view biceps-curl analyzer. Uses the most reliable visible arm. Metrics:
 * elbow angle (shoulder-elbow-wrist), torso inclination (shoulder-hip vs
 * vertical) and upper-arm-to-torso angle (how far the elbow swings from the body).
 */
class BicepsCurlAnalyzer : SideViewAnalyzer() {

    private val engine = BicepsCurlRuleEngine()

    override fun leftLandmarks(landmarks: List<NormalizedLandmark>) = listOf(
        landmarks[PoseLandmarkIndices.L_SH],
        landmarks[PoseLandmarkIndices.L_ELBOW],
        landmarks[PoseLandmarkIndices.L_WRIST],
        landmarks[PoseLandmarkIndices.L_HIP]
    )

    override fun rightLandmarks(landmarks: List<NormalizedLandmark>) = listOf(
        landmarks[PoseLandmarkIndices.R_SH],
        landmarks[PoseLandmarkIndices.R_ELBOW],
        landmarks[PoseLandmarkIndices.R_WRIST],
        landmarks[PoseLandmarkIndices.R_HIP]
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

        val elbowAngle = KinematicCalculator.calculateAngle(
            shoulder, elbow, wrist, imageWidth, imageHeight
        )
        val torsoAngle = KinematicCalculator.angleFromVertical(
            shoulder, hip, imageWidth, imageHeight
        )
        val upperArmAngle = KinematicCalculator.upperArmToTorsoAngle(
            shoulder, elbow, hip, imageWidth, imageHeight
        )

        val result = engine.processFrame(elbowAngle, torsoAngle, upperArmAngle)

        val metrics = OverlayMetrics(
            values = linkedMapOf(
                "Elbow" to elbowAngle,
                "Upper arm" to upperArmAngle,
                "Torso" to torsoAngle
            ),
            warning = result.warning,
            phase = result.phaseName
        )
        return ExerciseFrameOutput(result, metrics)
    }

    override fun resetEngine() = engine.reset()
}
