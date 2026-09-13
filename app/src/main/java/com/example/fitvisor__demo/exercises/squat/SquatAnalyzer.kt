package com.example.fitvisor__demo.exercises.squat

import com.example.fitvisor__demo.exercises.ExerciseFrameOutput
import com.example.fitvisor__demo.exercises.SideViewAnalyzer
import com.example.fitvisor__demo.kinematics.KinematicCalculator
import com.example.fitvisor__demo.model.OverlayMetrics
import com.example.fitvisor__demo.pose.LandmarkConfidence
import com.example.fitvisor__demo.pose.PoseLandmarkIndices
import com.example.fitvisor__demo.pose.SideSelector
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Side-view squat analyzer. Preserves the original squat metrics: knee angle
 * (hip-knee-ankle) and torso inclination (shoulder-hip vs vertical). The 2D
 * knee-alignment rule stays disabled, as in the original implementation.
 *
 * Knee and torso angle prefer MediaPipe's real-world (metric) 3D landmarks
 * ([SideViewAnalyzer.worldLandmarks], via [KinematicCalculator]'s `*3D`
 * functions) when available, falling back to the original 2D image-space
 * calculation otherwise. The 2D angle is only accurate when the user is in
 * an exact side-on profile relative to the camera; any rotation distorts it,
 * and by a different amount as the joint moves through the rep, which reads
 * as inconsistent knee angles between otherwise-identical repetitions. The
 * 3D version is far less sensitive to that.
 *
 * Also computes the knee-over-toe check's kinematic inputs (ankle angle and
 * normalized knee-to-toe offset) for whichever leg [kneeOverToeLegTracker]
 * currently has locked in — independent of [useLeft] above, since it only
 * needs knee/ankle/foot-index confidence, not the shoulder/hip/knee/ankle set
 * used for the primary knee/torso angles. Same 3D-preferred, 2D-fallback
 * pattern as the primary angles.
 */
class SquatAnalyzer(
    poseQualityEnabled: () -> Boolean = { false }
) : SideViewAnalyzer(poseQualityEnabled) {

    private val engine = SquatRuleEngine()
    private val kneeOverToeLegTracker = KneeOverToeLegTracker()

    override fun leftLandmarks(landmarks: List<NormalizedLandmark>) = listOf(
        landmarks[PoseLandmarkIndices.L_SH],
        landmarks[PoseLandmarkIndices.L_HIP],
        landmarks[PoseLandmarkIndices.L_KNEE],
        landmarks[PoseLandmarkIndices.L_ANKLE]
    )

    override fun rightLandmarks(landmarks: List<NormalizedLandmark>) = listOf(
        landmarks[PoseLandmarkIndices.R_SH],
        landmarks[PoseLandmarkIndices.R_HIP],
        landmarks[PoseLandmarkIndices.R_KNEE],
        landmarks[PoseLandmarkIndices.R_ANKLE]
    )

    override fun analyzeSide(
        useLeft: Boolean,
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): ExerciseFrameOutput {
        val shIndex = if (useLeft) PoseLandmarkIndices.L_SH else PoseLandmarkIndices.R_SH
        val hipIndex = if (useLeft) PoseLandmarkIndices.L_HIP else PoseLandmarkIndices.R_HIP
        val kneeIndex = if (useLeft) PoseLandmarkIndices.L_KNEE else PoseLandmarkIndices.R_KNEE
        val ankleIndex = if (useLeft) PoseLandmarkIndices.L_ANKLE else PoseLandmarkIndices.R_ANKLE

        val shoulder = landmarks[shIndex]
        val hip = landmarks[hipIndex]
        val knee = landmarks[kneeIndex]
        val ankle = landmarks[ankleIndex]

        val kneeAngle = worldKneeAngle(hipIndex, kneeIndex, ankleIndex)
            ?: KinematicCalculator.calculateAngle(hip, knee, ankle, imageWidth, imageHeight)
        val torsoAngle = worldTorsoAngle(shIndex, hipIndex)
            ?: KinematicCalculator.angleFromVertical(shoulder, hip, imageWidth, imageHeight)

        // Knee alignment rule intentionally disabled (2D was unreliable).
        val kneeMisaligned = false

        val kneeOverToe = computeKneeOverToeMetrics(landmarks, imageWidth, imageHeight)
        val result = engine.processFrame(kneeAngle, torsoAngle, kneeMisaligned, kneeOverToe)

        val metrics = OverlayMetrics(
            values = linkedMapOf(
                "Knee" to kneeAngle,
                "Torso" to torsoAngle
            ),
            warning = result.warning,
            phase = result.phaseName
        )
        return ExerciseFrameOutput(result, metrics)
    }

    /** 3D knee (hip-knee-ankle) angle from [worldLandmarks], or null if unavailable. */
    private fun worldKneeAngle(hipIndex: Int, kneeIndex: Int, ankleIndex: Int): Double? {
        val hip = worldLandmarks.getOrNull(hipIndex) ?: return null
        val knee = worldLandmarks.getOrNull(kneeIndex) ?: return null
        val ankle = worldLandmarks.getOrNull(ankleIndex) ?: return null
        return KinematicCalculator.calculateAngle3D(hip, knee, ankle).takeUnless { it.isNaN() }
    }

    /** 3D torso-from-vertical angle from [worldLandmarks], or null if unavailable. */
    private fun worldTorsoAngle(shIndex: Int, hipIndex: Int): Double? {
        val shoulder = worldLandmarks.getOrNull(shIndex) ?: return null
        val hip = worldLandmarks.getOrNull(hipIndex) ?: return null
        return KinematicCalculator.angleFromVertical3D(shoulder, hip).takeUnless { it.isNaN() }
    }

    /**
     * Picks the more reliable leg (min confidence across knee/ankle/foot-index,
     * locked per-repetition by [kneeOverToeLegTracker]) and computes the ankle
     * angle + normalized knee-to-toe offset for it. Returns null when neither
     * leg currently meets the confidence threshold — callers must treat that
     * as "unknown", not as a technique failure.
     */
    private fun computeKneeOverToeMetrics(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): KneeOverToeMetrics? {
        val leftKnee = landmarks[PoseLandmarkIndices.L_KNEE]
        val leftAnkle = landmarks[PoseLandmarkIndices.L_ANKLE]
        val leftFootIndex = landmarks[PoseLandmarkIndices.L_FOOT_INDEX]
        val rightKnee = landmarks[PoseLandmarkIndices.R_KNEE]
        val rightAnkle = landmarks[PoseLandmarkIndices.R_ANKLE]
        val rightFootIndex = landmarks[PoseLandmarkIndices.R_FOOT_INDEX]

        val leftScore = LandmarkConfidence.minOfAll(leftKnee, leftAnkle, leftFootIndex)
        val rightScore = LandmarkConfidence.minOfAll(rightKnee, rightAnkle, rightFootIndex)

        val isStanding = engine.phaseName == SquatRuleEngine.State.UP.name
        val side = kneeOverToeLegTracker.update(leftScore, rightScore, isStanding) ?: return null

        val useLeftLeg = side == SideSelector.Side.LEFT
        val knee = if (useLeftLeg) leftKnee else rightKnee
        val ankle = if (useLeftLeg) leftAnkle else rightAnkle
        val footIndex = if (useLeftLeg) leftFootIndex else rightFootIndex
        val confidence = if (useLeftLeg) leftScore else rightScore

        val kneeIndex = if (useLeftLeg) PoseLandmarkIndices.L_KNEE else PoseLandmarkIndices.R_KNEE
        val ankleIndex = if (useLeftLeg) PoseLandmarkIndices.L_ANKLE else PoseLandmarkIndices.R_ANKLE
        val footIndexIndex =
            if (useLeftLeg) PoseLandmarkIndices.L_FOOT_INDEX else PoseLandmarkIndices.R_FOOT_INDEX

        val ankleAngle = worldAnkleAngle(kneeIndex, ankleIndex, footIndexIndex)
            ?: KinematicCalculator.calculateAngle(knee, ankle, footIndex, imageWidth, imageHeight)
        val kneeToeOffset = worldKneeToeOffset(kneeIndex, ankleIndex, footIndexIndex)
            ?: KinematicCalculator.normalizedKneeToeOffset(knee, ankle, footIndex, imageWidth, imageHeight)

        return KneeOverToeMetrics(
            legIsLeft = useLeftLeg,
            confidence = confidence,
            ankleAngle = ankleAngle,
            normalizedKneeToeOffset = kneeToeOffset
        )
    }

    /** 3D ankle (knee-ankle-footIndex) angle from [worldLandmarks], or null if unavailable. */
    private fun worldAnkleAngle(kneeIndex: Int, ankleIndex: Int, footIndexIndex: Int): Double? {
        val knee = worldLandmarks.getOrNull(kneeIndex) ?: return null
        val ankle = worldLandmarks.getOrNull(ankleIndex) ?: return null
        val footIndex = worldLandmarks.getOrNull(footIndexIndex) ?: return null
        return KinematicCalculator.calculateAngle3D(knee, ankle, footIndex).takeUnless { it.isNaN() }
    }

    /** 3D normalized knee-to-toe offset from [worldLandmarks], or null if unavailable. */
    private fun worldKneeToeOffset(kneeIndex: Int, ankleIndex: Int, footIndexIndex: Int): Double? {
        val knee = worldLandmarks.getOrNull(kneeIndex) ?: return null
        val ankle = worldLandmarks.getOrNull(ankleIndex) ?: return null
        val footIndex = worldLandmarks.getOrNull(footIndexIndex) ?: return null
        return KinematicCalculator.normalizedKneeToeOffset3D(knee, ankle, footIndex).takeUnless { it.isNaN() }
    }

    override fun resetEngine() {
        engine.reset()
        kneeOverToeLegTracker.reset()
    }
}
