package com.example.fitvisor__demo

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Side-view squat analyzer. Preserves the original squat metrics: knee angle
 * (hip-knee-ankle) and torso inclination (shoulder-hip vs vertical). The 2D
 * knee-alignment rule stays disabled, as in the original implementation.
 */
class SquatAnalyzer : SideViewAnalyzer() {

    private val engine = SquatRuleEngine()

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

        val result = engine.processFrame(kneeAngle, torsoAngle, kneeMisaligned)

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

    override fun resetEngine() = engine.reset()
}
