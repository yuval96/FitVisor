package com.example.fitvisor__demo

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Side-view squat analyzer. Preserves the original squat metrics: knee angle
 * (hip-knee-ankle) and torso inclination (shoulder-hip vs vertical). The 2D
 * knee-alignment rule stays disabled, as in the original implementation.
 *
 * Also computes the knee-over-toe check's kinematic inputs (ankle angle and
 * normalized knee-to-toe offset) for whichever leg [kneeOverToeLegTracker]
 * currently has locked in — independent of [useLeft] above, since it only
 * needs knee/ankle/foot-index confidence, not the shoulder/hip/knee/ankle set
 * used for the primary knee/torso angles.
 */
class SquatAnalyzer : SideViewAnalyzer() {

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
        val shoulder = landmarks[if (useLeft) PoseLandmarkIndices.L_SH else PoseLandmarkIndices.R_SH]
        val hip = landmarks[if (useLeft) PoseLandmarkIndices.L_HIP else PoseLandmarkIndices.R_HIP]
        val knee = landmarks[if (useLeft) PoseLandmarkIndices.L_KNEE else PoseLandmarkIndices.R_KNEE]
        val ankle = landmarks[if (useLeft) PoseLandmarkIndices.L_ANKLE else PoseLandmarkIndices.R_ANKLE]

        val kneeAngle = KinematicCalculator.calculateAngle(
            hip, knee, ankle, imageWidth, imageHeight
        )
        val torsoAngle = KinematicCalculator.angleFromVertical(
            shoulder, hip, imageWidth, imageHeight
        )

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

        return KneeOverToeMetrics(
            legIsLeft = useLeftLeg,
            confidence = confidence,
            ankleAngle = KinematicCalculator.calculateAngle(knee, ankle, footIndex, imageWidth, imageHeight),
            normalizedKneeToeOffset = KinematicCalculator.normalizedKneeToeOffset(
                knee, ankle, footIndex, imageWidth, imageHeight
            )
        )
    }

    override fun resetEngine() {
        engine.reset()
        kneeOverToeLegTracker.reset()
    }
}
