package com.example.fitvisor__demo.exercises

import com.example.fitvisor__demo.pose.LandmarkConfidence
import com.example.fitvisor__demo.pose.SideSelector
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Debug-only pose-quality values measured from the exact required landmarks
 * and side(s) selected by the real analyzer for this frame.
 *
 * [rawPositions] is a short interleaved x/y array (at most eight landmarks),
 * used only until the next valid frame. [angles] reuses the analyzer's existing
 * output values, so neither landmarks nor angles are recalculated here.
 */
data class PoseQualityFrameSample(
    val selectionKey: String,
    val currentVisibility: Double,
    val minimumVisibility: Double,
    val isBelowThreshold: Boolean,
    val landmarkCount: Int,
    val rawPositions: FloatArray,
    val coordinatesReliable: Boolean,
    val angles: Map<String, Double> = emptyMap()
) {
    fun withAngles(values: Map<String, Double>): PoseQualityFrameSample =
        if (coordinatesReliable) copy(angles = values) else this
}

/** Builds one lightweight sample without duplicating exercise-side selection. */
internal object PoseQualityMeasurement {
    fun measure(
        selectionKey: String,
        requiredLandmarks: List<NormalizedLandmark>,
        visibilityThreshold: Float = SideSelector.MIN_SIDE_CONFIDENCE
    ): PoseQualityFrameSample? {
        if (requiredLandmarks.isEmpty()) return null

        var visibilitySum = 0.0
        var minimumVisibility = Double.POSITIVE_INFINITY
        var isBelowThreshold = false
        val rawPositions = FloatArray(requiredLandmarks.size * COORDINATES_PER_LANDMARK)

        for ((index, landmark) in requiredLandmarks.withIndex()) {
            val visibility = landmark.visibility().orElse(0f).toDouble()
            visibilitySum += visibility
            if (visibility < minimumVisibility) minimumVisibility = visibility
            if (visibility < visibilityThreshold) isBelowThreshold = true
            rawPositions[index * COORDINATES_PER_LANDMARK] = landmark.x()
            rawPositions[index * COORDINATES_PER_LANDMARK + 1] = landmark.y()
        }

        return PoseQualityFrameSample(
            selectionKey = selectionKey,
            currentVisibility = visibilitySum / requiredLandmarks.size,
            minimumVisibility = minimumVisibility,
            isBelowThreshold = isBelowThreshold,
            landmarkCount = requiredLandmarks.size,
            rawPositions = rawPositions,
            coordinatesReliable =
                LandmarkConfidence.minOfAll(requiredLandmarks) >= visibilityThreshold
        )
    }

    private const val COORDINATES_PER_LANDMARK = 2
}
