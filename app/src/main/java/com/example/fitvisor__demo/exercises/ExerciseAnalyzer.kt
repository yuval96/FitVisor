package com.example.fitvisor__demo.exercises

import com.example.fitvisor__demo.model.ExerciseAnalysisResult
import com.example.fitvisor__demo.model.OverlayMetrics
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/** Shown when the landmarks required for the exercise are not reliable enough. */
const val WARNING_BODY_NOT_VISIBLE = "Keep your body visible"

/**
 * Combined output of a single analyzed frame: the rule-engine [result] plus the
 * [metrics] to draw on the overlay.
 */
data class ExerciseFrameOutput(
    val result: ExerciseAnalysisResult,
    val metrics: OverlayMetrics
)

/**
 * Turns a frame of pose landmarks into an [ExerciseFrameOutput].
 *
 * Each implementation owns the concerns that differ per exercise: which
 * landmarks matter, how the reliable body side is selected, confidence gating,
 * short missing-frame tolerance, metric computation and feeding its rule engine.
 * This keeps [WorkoutActivity] thin and the rule engines free of Android/landmark
 * dependencies (so they stay unit-testable with plain angle values).
 */
interface ExerciseAnalyzer {

    /**
     * @param landmarks smoothed, non-empty landmark list for the detected pose.
     * @param imageWidth  width of the analyzed frame in pixels.
     * @param imageHeight height of the analyzed frame in pixels.
     */
    fun analyze(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): ExerciseFrameOutput

    /** Clears any accumulated state so a fresh session starts clean. */
    fun reset()
}
