package com.example.fitvisor__demo.pose

import com.example.fitvisor__demo.settings.AnalysisConfig
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Optional

/**
 * Applies Exponential Moving Average (EMA) smoothing to landmarks to reduce jitter.
 *
 * [alpha] weights the current frame against the previous smoothed value:
 *   alpha = 1.0   -> no smoothing (raw landmarks, no added latency)
 *   alpha -> 1.0  -> less smoothing / less latency
 *   alpha lower   -> stronger smoothing / more lag
 *
 * The smoother accepts an arbitrary alpha; the app-wide default lives in
 * [AnalysisConfig.LANDMARK_SMOOTHING_ALPHA] so it can be tuned in one place.
 *
 * Visibility/presence (confidence) are smoothed separately with
 * [confidenceAlpha], since they are not rendered and can afford stronger
 * smoothing than position without adding perceptible input lag -- see
 * [AnalysisConfig.CONFIDENCE_SMOOTHING_ALPHA].
 */
class LandmarkSmoother(
    private val alpha: Float = AnalysisConfig.LANDMARK_SMOOTHING_ALPHA,
    private val confidenceAlpha: Float = AnalysisConfig.CONFIDENCE_SMOOTHING_ALPHA
) {

    private var previousLandmarks: List<NormalizedLandmark>? = null

    /**
     * Smoothes the current list of landmarks using the previous state.
     */
    fun smooth(currentLandmarks: List<NormalizedLandmark>): List<NormalizedLandmark> {
        if (previousLandmarks == null || previousLandmarks!!.size != currentLandmarks.size) {
            previousLandmarks = currentLandmarks
            return currentLandmarks
        }

        val smoothed = currentLandmarks.mapIndexed { index, current ->
            val prev = previousLandmarks!![index]

            // EMA: smoothed = alpha * current + (1 - alpha) * previous
            val newX = alpha * current.x() + (1 - alpha) * prev.x()
            val newY = alpha * current.y() + (1 - alpha) * prev.y()
            val newZ = alpha * current.z() + (1 - alpha) * prev.z()
            val newVisibility = confidenceAlpha * current.visibility().orElse(0f) +
                (1 - confidenceAlpha) * prev.visibility().orElse(0f)
            val newPresence = confidenceAlpha * current.presence().orElse(0f) +
                (1 - confidenceAlpha) * prev.presence().orElse(0f)

            NormalizedLandmark.create(
                newX,
                newY,
                newZ,
                Optional.of(newVisibility),
                Optional.of(newPresence)
            )
        }

        previousLandmarks = smoothed
        return smoothed
    }

    fun reset() {
        previousLandmarks = null
    }
}
