package com.example.fitvisor__demo.exercises

import com.example.fitvisor__demo.model.ExerciseAnalysisResult
import com.example.fitvisor__demo.model.OverlayMetrics
import com.example.fitvisor__demo.pose.LandmarkConfidence
import com.example.fitvisor__demo.pose.SideSelector
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Shared plumbing for side-view exercises (squat, push-up, biceps curl):
 * per-side confidence, reliable-side selection, confidence gating and a short
 * missing-frame tolerance. Subclasses only declare the landmarks they need and
 * how to turn the chosen side into metrics + a rule-engine result.
 *
 * The rule engine is never fed during unreliable frames, so a repetition is not
 * reset or miscounted because of one bad frame. Only after several consecutive
 * unreliable frames is the engine reset.
 */
abstract class SideViewAnalyzer : ExerciseAnalyzer {

    protected val sideSelector = SideSelector()

    private var missingFrames = 0
    private var lastPhase: String? = null

    /** Landmarks required on the left / right side, for confidence + gating. */
    protected abstract fun leftLandmarks(
        landmarks: List<NormalizedLandmark>
    ): List<NormalizedLandmark>

    protected abstract fun rightLandmarks(
        landmarks: List<NormalizedLandmark>
    ): List<NormalizedLandmark>

    /** Computes metrics and feeds the engine for the chosen (reliable) side. */
    protected abstract fun analyzeSide(
        useLeft: Boolean,
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): ExerciseFrameOutput

    protected abstract fun resetEngine()

    final override fun analyze(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): ExerciseFrameOutput {

        if (landmarks.size < LANDMARK_COUNT) {
            return notVisible()
        }

        val left = leftLandmarks(landmarks)
        val right = rightLandmarks(landmarks)

        val leftConfidence = LandmarkConfidence.minOfAll(*left.toTypedArray())
        val rightConfidence = LandmarkConfidence.minOfAll(*right.toTypedArray())

        val side = sideSelector.select(leftConfidence, rightConfidence)
        val useLeft = side == SideSelector.Side.LEFT
        val selectedConfidence = if (useLeft) leftConfidence else rightConfidence

        if (selectedConfidence < SideSelector.MIN_SIDE_CONFIDENCE) {
            missingFrames++
            if (missingFrames > MAX_MISSING_FRAMES) {
                resetEngine()
            }
            return notVisible()
        }

        missingFrames = 0
        val output = analyzeSide(useLeft, landmarks, imageWidth, imageHeight)
        lastPhase = output.result.phaseName
        return output
    }

    private fun notVisible(): ExerciseFrameOutput {
        val result = ExerciseAnalysisResult(
            isRepCompleted = false,
            isRepCorrect = false,
            warning = WARNING_BODY_NOT_VISIBLE,
            phaseName = lastPhase ?: "-"
        )
        return ExerciseFrameOutput(
            result,
            OverlayMetrics(emptyMap(), WARNING_BODY_NOT_VISIBLE, lastPhase)
        )
    }

    final override fun reset() {
        sideSelector.reset()
        missingFrames = 0
        lastPhase = null
        resetEngine()
    }

    companion object {
        /** Full MediaPipe pose landmark count; a detected pose always has this. */
        private const val LANDMARK_COUNT = 33

        /** Tolerated consecutive unreliable frames before the rep is reset. */
        private const val MAX_MISSING_FRAMES = 5
    }
}
