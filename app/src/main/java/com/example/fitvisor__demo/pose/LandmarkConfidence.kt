package com.example.fitvisor__demo.pose

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Confidence helpers for MediaPipe landmarks.
 *
 * A landmark's confidence is the minimum of its visibility and presence, so a
 * point is only trusted when the model is confident on both signals.
 */
object LandmarkConfidence {

    fun of(landmark: NormalizedLandmark): Float {
        val visibility = landmark.visibility().orElse(0f)
        val presence = landmark.presence().orElse(0f)
        return minOf(visibility, presence)
    }

    /**
     * Confidence of a group of landmarks: the weakest link. Used as the
     * confidence of a body side, since a rule needs *all* of its landmarks.
     */
    fun minOfAll(vararg landmarks: NormalizedLandmark): Float {
        return minOfAll(landmarks.asIterable())
    }

    /** Iterable overload lets analyzers reuse their already-declared required set. */
    fun minOfAll(landmarks: Iterable<NormalizedLandmark>): Float {
        var min = Float.MAX_VALUE
        var found = false
        for (landmark in landmarks) {
            found = true
            val c = of(landmark)
            if (c < min) min = c
        }
        return if (found) min else 0f
    }
}
