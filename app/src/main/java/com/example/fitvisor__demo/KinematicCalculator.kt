package com.example.fitvisor__demo

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Normalization and Kinematic Calculator Module.
 * Responsible for computing joint angles and biomechanical features from landmarks.
 */
object KinematicCalculator {

    /**
     * Calculates the angle between three landmarks in degrees.
     * @param firstPoint The first landmark (e.g., Hip)
     * @param midPoint The middle landmark (e.g., Knee - the joint vertex)
     * @param lastPoint The last landmark (e.g., Ankle)
     */
    fun calculateAngle(
        firstPoint: NormalizedLandmark,
        midPoint: NormalizedLandmark,
        lastPoint: NormalizedLandmark
    ): Double {
        val radians = atan2(lastPoint.y() - midPoint.y(), lastPoint.x() - midPoint.x()) -
                atan2(firstPoint.y() - midPoint.y(), firstPoint.x() - midPoint.x())
        var degrees = Math.toDegrees(abs(radians).toDouble())
        if (degrees > 180.0) {
            degrees = 360.0 - degrees
        }
        return degrees
    }

    /**
     * Calculates the torso inclination angle relative to the vertical axis.
     */
    fun calculateTorsoAngle(
        leftShoulder: NormalizedLandmark,
        rightShoulder: NormalizedLandmark,
        leftHip: NormalizedLandmark,
        rightHip: NormalizedLandmark
    ): Double {
        val midShoulderX = (leftShoulder.x() + rightShoulder.x()) / 2
        val midShoulderY = (leftShoulder.y() + rightShoulder.y()) / 2

        val midHipX = (leftHip.x() + rightHip.x()) / 2
        val midHipY = (leftHip.y() + rightHip.y()) / 2

        val vecX = midShoulderX - midHipX
        val vecY = midShoulderY - midHipY

        // Vertical vector (pointing up)
        val verticalVecX = 0f
        val verticalVecY = -1f

        val dotProduct = (vecX * verticalVecX) + (vecY * verticalVecY)
        val vecMagnitude = sqrt((vecX * vecX + vecY * vecY).toDouble())
        val verticalMagnitude = 1.0 // sqrt(0^2 + (-1)^2)

        val cosTheta = dotProduct / (vecMagnitude * verticalMagnitude)
        // Clamp cosTheta to avoid NaN from acos due to precision
        val clampedCos = cosTheta.coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(clampedCos))
    }
}
