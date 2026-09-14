package com.example.fitvisor__demo.pose

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Confidence helpers for MediaPipe landmarks.
 *
 * A landmark's confidence is the minimum of its visibility and presence, so a
 * point is only trusted when the model is confident on both signals.
 */
object LandmarkConfidence {

    /**
     * Stricter confidence bar than [SideSelector.MIN_SIDE_CONFIDENCE], used to
     * decide whether a landmark's depth (Z) estimate -- read from MediaPipe's
     * real-world (metric) landmarks, via [com.example.fitvisor__demo.kinematics.KinematicCalculator]'s
     * `*3D` functions -- is trustworthy enough to use, as opposed to falling
     * back to the 2D projection.
     *
     * A landmark can clear the general visibility bar (enough to place its
     * X/Y reasonably) while still being borderline or partially occluded
     * enough that its *depth* is closer to a guess than an observation:
     * monocular depth estimation is far more sensitive to marginal
     * visibility than X/Y ever is, since X/Y come directly from where the
     * point is seen in the image while Z is inferred from the model's
     * learned prior. Without this, a poorly-observed joint can silently
     * produce a confidently-wrong 3D angle instead of the 2D fallback, which
     * degrades more gracefully under the same occlusion.
     */
    const val WORLD_LANDMARK_TRUST_CONFIDENCE = 0.7f

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

    /**
     * True when every one of [landmarks] clears [WORLD_LANDMARK_TRUST_CONFIDENCE] --
     * i.e. it's safe to use their real-world (metric) depth estimate for a
     * `*3D` angle rather than falling back to the 2D projection.
     */
    fun trustedForWorldLandmarks(vararg landmarks: NormalizedLandmark): Boolean =
        minOfAll(*landmarks) >= WORLD_LANDMARK_TRUST_CONFIDENCE
}
