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
 * horizontal. All three prefer MediaPipe's real-world (metric) 3D landmarks
 * ([SideViewAnalyzer.worldLandmarks]) when available, falling back to the
 * original 2D image-space calculation otherwise -- the 2D version is only
 * accurate in an exact side-on profile stance; see [KinematicCalculator]'s
 * class doc.
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

        val elbowAngle = worldAngle3(wristIndex, elbowIndex, shIndex)
            ?: KinematicCalculator.calculateAngle(wrist, elbow, shoulder, imageWidth, imageHeight)
        val bodyLineAngle = worldAngle3(shIndex, hipIndex, ankleIndex)
            ?: KinematicCalculator.bodyLineAngle(shoulder, hip, ankle, imageWidth, imageHeight)
        val horizontalAngle = worldHorizontalAngle(hipIndex, ankleIndex)
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

    /** 3D angle at [midIndex] from [worldLandmarks], or null if unavailable. */
    private fun worldAngle3(firstIndex: Int, midIndex: Int, lastIndex: Int): Double? {
        val first = worldLandmarks.getOrNull(firstIndex) ?: return null
        val mid = worldLandmarks.getOrNull(midIndex) ?: return null
        val last = worldLandmarks.getOrNull(lastIndex) ?: return null
        return KinematicCalculator.calculateAngle3D(first, mid, last).takeUnless { it.isNaN() }
    }

    /**
     * 3D horizontal-deviation magnitude from [worldLandmarks], or null if
     * unavailable. Unsigned (see [KinematicCalculator.angleFromHorizontal3D]),
     * which is safe here: [PushUpRuleEngine] only ever compares `abs(...)`.
     */
    private fun worldHorizontalAngle(fromIndex: Int, toIndex: Int): Double? {
        val from = worldLandmarks.getOrNull(fromIndex) ?: return null
        val to = worldLandmarks.getOrNull(toIndex) ?: return null
        return KinematicCalculator.angleFromHorizontal3D(from, to).takeUnless { it.isNaN() }
    }

    override fun resetEngine() = engine.reset()
}
