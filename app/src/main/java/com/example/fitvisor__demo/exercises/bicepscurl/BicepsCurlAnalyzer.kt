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
 * vertical) and upper-arm-to-torso angle (how far the elbow swings from the
 * body). All three prefer MediaPipe's real-world (metric) 3D landmarks
 * ([SideViewAnalyzer.worldLandmarks]) when available, falling back to the
 * original 2D image-space calculation otherwise -- the 2D version is only
 * accurate in an exact side-on profile stance; see [KinematicCalculator]'s
 * class doc.
 */
class BicepsCurlAnalyzer(
    poseQualityEnabled: () -> Boolean = { false }
) : SideViewAnalyzer(poseQualityEnabled) {

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
        val shIndex = if (useLeft) PoseLandmarkIndices.L_SH else PoseLandmarkIndices.R_SH
        val elbowIndex = if (useLeft) PoseLandmarkIndices.L_ELBOW else PoseLandmarkIndices.R_ELBOW
        val wristIndex = if (useLeft) PoseLandmarkIndices.L_WRIST else PoseLandmarkIndices.R_WRIST
        val hipIndex = if (useLeft) PoseLandmarkIndices.L_HIP else PoseLandmarkIndices.R_HIP

        val shoulder = landmarks[shIndex]
        val elbow = landmarks[elbowIndex]
        val wrist = landmarks[wristIndex]
        val hip = landmarks[hipIndex]

        val elbowAngle = worldAngle3(shIndex, elbowIndex, wristIndex)
            ?: KinematicCalculator.calculateAngle(shoulder, elbow, wrist, imageWidth, imageHeight)
        val torsoAngle = worldTorsoAngle(shIndex, hipIndex)
            ?: KinematicCalculator.angleFromVertical(shoulder, hip, imageWidth, imageHeight)
        val upperArmAngle = worldAngle3(elbowIndex, shIndex, hipIndex)
            ?: KinematicCalculator.upperArmToTorsoAngle(shoulder, elbow, hip, imageWidth, imageHeight)

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

    /** 3D angle at [midIndex] from [worldLandmarks], or null if unavailable. */
    private fun worldAngle3(firstIndex: Int, midIndex: Int, lastIndex: Int): Double? {
        val first = worldLandmarks.getOrNull(firstIndex) ?: return null
        val mid = worldLandmarks.getOrNull(midIndex) ?: return null
        val last = worldLandmarks.getOrNull(lastIndex) ?: return null
        return KinematicCalculator.calculateAngle3D(first, mid, last).takeUnless { it.isNaN() }
    }

    /** 3D torso-from-vertical angle from [worldLandmarks], or null if unavailable. */
    private fun worldTorsoAngle(shIndex: Int, hipIndex: Int): Double? {
        val shoulder = worldLandmarks.getOrNull(shIndex) ?: return null
        val hip = worldLandmarks.getOrNull(hipIndex) ?: return null
        return KinematicCalculator.angleFromVertical3D(shoulder, hip).takeUnless { it.isNaN() }
    }

    override fun resetEngine() = engine.reset()
}
