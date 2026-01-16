package com.example.fitvisor__demo

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Applies Exponential Moving Average (EMA) smoothing to landmarks to reduce jitter.
 */
class LandmarkSmoother(private val alpha: Float = 0.35f) {

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
            
            NormalizedLandmark.create(
                newX, 
                newY, 
                newZ, 
                current.visibility(), 
                current.presence()
            )
        }

        previousLandmarks = smoothed
        return smoothed
    }

    fun reset() {
        previousLandmarks = null
    }
}
