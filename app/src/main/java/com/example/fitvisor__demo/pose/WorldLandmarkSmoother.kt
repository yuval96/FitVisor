package com.example.fitvisor__demo.pose

import com.example.fitvisor__demo.settings.AnalysisConfig
import com.google.mediapipe.tasks.components.containers.Landmark

/**
 * Applies Exponential Moving Average (EMA) smoothing to MediaPipe's
 * real-world (metric) [Landmark]s, exactly like [LandmarkSmoother] does for
 * the normalized 2D landmarks -- but on the depth (Z) axis too.
 *
 * Before the `*3D` angle functions in [com.example.fitvisor__demo.kinematics.KinematicCalculator]
 * existed, world landmarks were never consumed by the app, so this had no
 * reason to exist. Once exercises started reading them per frame, an
 * unsmoothed depth axis (the noisiest of the three in monocular pose
 * estimation) turned out to matter a lot more for a tight-threshold, mostly-
 * static check -- e.g. biceps curl's torso-stays-vertical check (10 deg) --
 * than for a wide-range one like squat's torso lean (45 deg), where the
 * intentional signal dwarfs the noise. Smoothing the world landmarks the
 * same way as the 2D ones removes that gap.
 */
class WorldLandmarkSmoother(private val alpha: Float = AnalysisConfig.LANDMARK_SMOOTHING_ALPHA) {

    private var previousLandmarks: List<Landmark>? = null

    fun smooth(currentLandmarks: List<Landmark>): List<Landmark> {
        val previous = previousLandmarks
        if (previous == null || previous.size != currentLandmarks.size) {
            previousLandmarks = currentLandmarks
            return currentLandmarks
        }

        val smoothed = currentLandmarks.mapIndexed { index, current ->
            val prev = previous[index]
            Landmark.create(
                alpha * current.x() + (1 - alpha) * prev.x(),
                alpha * current.y() + (1 - alpha) * prev.y(),
                alpha * current.z() + (1 - alpha) * prev.z()
            )
        }

        previousLandmarks = smoothed
        return smoothed
    }

    fun reset() {
        previousLandmarks = null
    }
}
