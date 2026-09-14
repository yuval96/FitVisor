package com.example.fitvisor__demo.exercises.pushup

import com.example.fitvisor__demo.exercises.ExerciseFrameOutput
import com.example.fitvisor__demo.exercises.SideViewAnalyzer
import com.example.fitvisor__demo.kinematics.KinematicCalculator
import com.example.fitvisor__demo.model.OverlayMetrics
import com.example.fitvisor__demo.pose.LandmarkConfidence
import com.example.fitvisor__demo.pose.PoseLandmarkIndices
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Side-view push-up analyzer. Metrics: elbow angle (wrist-elbow-shoulder),
 * body-line angle (shoulder-hip-ankle) and the hip-to-ankle tilt relative to
 * horizontal. Can compute all three from MediaPipe's real-world (metric) 3D
 * landmarks ([SideViewAnalyzer.worldLandmarks]) via the `world*` helpers
 * below, falling back to the original 2D image-space calculation when 3D is
 * off or unavailable -- see [KinematicCalculator]'s class doc for why 3D
 * exists at all.
 *
 * @param use3D defaults **off**. Push-up is a horizontal plank held close to
 *   a low, roughly-floor-level camera -- a very different geometry from the
 *   standing exercises 3D was built for, with far more foreshortening and
 *   faster near-camera hand motion. In practice this made depth (Z)
 *   estimation unreliable enough that rep detection stopped working
 *   altogether. Push-up already instructs the user to set the phone up
 *   side-on ("Place the phone sideways..."), which is exactly the condition
 *   the 2D calculation needs to be accurate, so the 3D robustness gain
 *   matters less here than the regression it introduced. See
 *   [SideViewAnalyzer]'s doc for how to flip this back on for testing.
 */
class PushUpAnalyzer(
    use3D: Boolean = false,
    poseQualityEnabled: () -> Boolean = { false }
) : SideViewAnalyzer(poseQualityEnabled, use3D) {

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
        val shIndex = if (useLeft) PoseLandmarkIndices.L_SH else PoseLandmarkIndices.R_SH
        val elbowIndex = if (useLeft) PoseLandmarkIndices.L_ELBOW else PoseLandmarkIndices.R_ELBOW
        val wristIndex = if (useLeft) PoseLandmarkIndices.L_WRIST else PoseLandmarkIndices.R_WRIST
        val hipIndex = if (useLeft) PoseLandmarkIndices.L_HIP else PoseLandmarkIndices.R_HIP
        val ankleIndex = if (useLeft) PoseLandmarkIndices.L_ANKLE else PoseLandmarkIndices.R_ANKLE

        val shoulder = landmarks[shIndex]
        val elbow = landmarks[elbowIndex]
        val wrist = landmarks[wristIndex]
        val hip = landmarks[hipIndex]
        val ankle = landmarks[ankleIndex]

        val elbowAngle = worldAngle3(wristIndex, elbowIndex, shIndex, wrist, elbow, shoulder)
            ?: KinematicCalculator.calculateAngle(wrist, elbow, shoulder, imageWidth, imageHeight)
        val bodyLineAngle = worldAngle3(shIndex, hipIndex, ankleIndex, shoulder, hip, ankle)
            ?: KinematicCalculator.bodyLineAngle(shoulder, hip, ankle, imageWidth, imageHeight)
        val horizontalAngle = worldHorizontalAngle(hipIndex, ankleIndex, hip, ankle)
            ?: KinematicCalculator.angleFromHorizontal(hip, ankle, imageWidth, imageHeight)

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
     * 3D horizontal-deviation magnitude from [worldLandmarks], or null if
     * unavailable or not confidently observed (see [worldAngle3]). Unsigned
     * (see [KinematicCalculator.angleFromHorizontal3D]), which is safe here:
     * [PushUpRuleEngine] only ever compares `abs(...)`.
     */
    private fun worldHorizontalAngle(
        fromIndex: Int, toIndex: Int,
        from2D: NormalizedLandmark, to2D: NormalizedLandmark
    ): Double? {
        if (!LandmarkConfidence.trustedForWorldLandmarks(from2D, to2D)) return null
        val from = worldLandmarks.getOrNull(fromIndex) ?: return null
        val to = worldLandmarks.getOrNull(toIndex) ?: return null
        return KinematicCalculator.angleFromHorizontal3D(from, to).takeUnless { it.isNaN() }
    }

    override fun resetEngine() = engine.reset()
}
