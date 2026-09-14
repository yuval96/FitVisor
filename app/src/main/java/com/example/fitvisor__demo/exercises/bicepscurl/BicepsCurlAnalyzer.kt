package com.example.fitvisor__demo.exercises.bicepscurl

import com.example.fitvisor__demo.exercises.ExerciseFrameOutput
import com.example.fitvisor__demo.exercises.SideViewAnalyzer
import com.example.fitvisor__demo.kinematics.KinematicCalculator
import com.example.fitvisor__demo.model.OverlayMetrics
import com.example.fitvisor__demo.pose.LandmarkConfidence
import com.example.fitvisor__demo.pose.PoseLandmarkIndices
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Side-view biceps-curl analyzer. Uses the most reliable visible arm. Metrics:
 * elbow angle (shoulder-elbow-wrist), torso inclination (shoulder-hip vs
 * vertical) and upper-arm-to-torso angle (how far the elbow swings from the
 * body). Can compute all three from MediaPipe's real-world (metric) 3D
 * landmarks ([SideViewAnalyzer.worldLandmarks]) via the `world*` helpers
 * below, falling back to the original 2D image-space calculation when 3D is
 * off or unavailable -- see [KinematicCalculator]'s class doc for why 3D
 * exists at all.
 *
 * @param use3D defaults **off**. The torso-stays-vertical check here has by
 *   far the tightest threshold of any exercise and the least intentional
 *   motion to dwarf depth (Z) noise (unlike squat's wide-range lean check),
 *   which on-device showed up as a persistent, orientation-dependent false
 *   "torso leaning" reading. Biceps curl already instructs the user to set
 *   up side-on ("Stand sideways or slightly angled..."), which is exactly
 *   the condition the 2D calculation needs to be accurate, so the 3D
 *   robustness gain matters less here than the false positives it
 *   introduced. See [SideViewAnalyzer]'s doc for how to flip this back on
 *   for testing.
 */
class BicepsCurlAnalyzer(
    use3D: Boolean = false,
    poseQualityEnabled: () -> Boolean = { false }
) : SideViewAnalyzer(poseQualityEnabled, use3D) {

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

        val elbowAngle = worldAngle3(shIndex, elbowIndex, wristIndex, shoulder, elbow, wrist)
            ?: KinematicCalculator.calculateAngle(shoulder, elbow, wrist, imageWidth, imageHeight)
        val torsoAngle = worldTorsoAngle(shIndex, hipIndex, shoulder, hip)
            ?: KinematicCalculator.angleFromVertical(shoulder, hip, imageWidth, imageHeight)
        val upperArmAngle = worldAngle3(elbowIndex, shIndex, hipIndex, elbow, shoulder, hip)
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

    /**
     * 3D angle at [midIndex] from [worldLandmarks], or null if unavailable or
     * if any of the corresponding 2D landmarks isn't confidently observed
     * enough to trust its depth estimate (see [LandmarkConfidence.trustedForWorldLandmarks]).
     */
    private fun worldAngle3(
        firstIndex: Int, midIndex: Int, lastIndex: Int,
        first2D: NormalizedLandmark, mid2D: NormalizedLandmark, last2D: NormalizedLandmark
    ): Double? {
        if (!LandmarkConfidence.trustedForWorldLandmarks(first2D, mid2D, last2D)) return null
        val first = worldLandmarks.getOrNull(firstIndex) ?: return null
        val mid = worldLandmarks.getOrNull(midIndex) ?: return null
        val last = worldLandmarks.getOrNull(lastIndex) ?: return null
        return KinematicCalculator.calculateAngle3D(first, mid, last).takeUnless { it.isNaN() }
    }

    /**
     * 3D torso-from-vertical angle from [worldLandmarks], or null if
     * unavailable or not confidently observed; see [worldAngle3].
     */
    private fun worldTorsoAngle(
        shIndex: Int, hipIndex: Int,
        shoulder2D: NormalizedLandmark, hip2D: NormalizedLandmark
    ): Double? {
        if (!LandmarkConfidence.trustedForWorldLandmarks(shoulder2D, hip2D)) return null
        val shoulder = worldLandmarks.getOrNull(shIndex) ?: return null
        val hip = worldLandmarks.getOrNull(hipIndex) ?: return null
        return KinematicCalculator.angleFromVertical3D(shoulder, hip).takeUnless { it.isNaN() }
    }

    override fun resetEngine() = engine.reset()
}
