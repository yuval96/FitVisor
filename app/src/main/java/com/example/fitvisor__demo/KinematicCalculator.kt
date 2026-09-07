package com.example.fitvisor__demo

import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.atan2

/**
 * Normalization and Kinematic Calculator Module.
 * Responsible for computing joint angles and biomechanical features from landmarks.
 *
 * All 2D angle calculations multiply the normalized coordinates by the image
 * width/height first, because MediaPipe normalizes X and Y independently on each
 * axis; skipping this would distort every angle on non-square frames.
 */
object KinematicCalculator {

    /**
     * Angle at [midPoint] formed by the three landmarks, in degrees (0..180).
     * @param firstPoint first landmark (e.g. Hip)
     * @param midPoint   middle landmark / joint vertex (e.g. Knee)
     * @param lastPoint  last landmark (e.g. Ankle)
     */
    fun calculateAngle(
        firstPoint: NormalizedLandmark,
        midPoint: NormalizedLandmark,
        lastPoint: NormalizedLandmark,
        imageWidth: Int,
        imageHeight: Int
    ): Double {

        val firstX = firstPoint.x() * imageWidth
        val firstY = firstPoint.y() * imageHeight

        val midX = midPoint.x() * imageWidth
        val midY = midPoint.y() * imageHeight

        val lastX = lastPoint.x() * imageWidth
        val lastY = lastPoint.y() * imageHeight

        val firstVectorAngle = atan2(
            firstY - midY,
            firstX - midX
        )

        val secondVectorAngle = atan2(
            lastY - midY,
            lastX - midX
        )

        var angle = Math.toDegrees(
            abs(secondVectorAngle - firstVectorAngle).toDouble()
        )

        if (angle > 180.0) {
            angle = 360.0 - angle
        }

        return angle
    }

    /**
     * Deviation of the segment [a]->[b] from the vertical axis, in degrees
     * (0..90). 0 means perfectly vertical, 90 means horizontal.
     * Returns [Double.NaN] for a degenerate (zero-length) segment.
     */
    fun angleFromVertical(
        a: NormalizedLandmark,
        b: NormalizedLandmark,
        imageWidth: Int,
        imageHeight: Int
    ): Double {
        val deltaX = (a.x() - b.x()) * imageWidth
        val deltaY = (a.y() - b.y()) * imageHeight

        if (deltaX == 0f && deltaY == 0f) {
            return Double.NaN
        }

        return Math.toDegrees(
            atan2(abs(deltaX), abs(deltaY)).toDouble()
        )
    }

    /**
     * Signed tilt of the segment [from]->[to] relative to the horizontal axis,
     * folded to the range (-90..90]. 0 means horizontal, +/-90 means vertical.
     * The sign reflects the direction of the vertical component in image space
     * (image Y grows downward), so its exact meaning depends on which way the
     * user faces; magnitude is the reliable quantity.
     * Returns [Double.NaN] for a degenerate (zero-length) segment.
     */
    fun angleFromHorizontal(
        from: NormalizedLandmark,
        to: NormalizedLandmark,
        imageWidth: Int,
        imageHeight: Int
    ): Double {
        val deltaX = (to.x() - from.x()) * imageWidth
        val deltaY = (to.y() - from.y()) * imageHeight

        if (deltaX == 0f && deltaY == 0f) {
            return Double.NaN
        }

        var angle = Math.toDegrees(
            atan2(deltaY, deltaX).toDouble()
        )

        // Fold to (-90..90] so opposite-facing directions read the same tilt.
        if (angle > 90.0) angle -= 180.0
        else if (angle < -90.0) angle += 180.0

        return angle
    }

    /**
     * Body-line angle: the angle at the hip formed by shoulder-hip-ankle
     * (~180 when the body is a straight line). Convenience over [calculateAngle].
     */
    fun bodyLineAngle(
        shoulder: NormalizedLandmark,
        hip: NormalizedLandmark,
        ankle: NormalizedLandmark,
        imageWidth: Int,
        imageHeight: Int
    ): Double = calculateAngle(shoulder, hip, ankle, imageWidth, imageHeight)

    /**
     * Angle between the upper arm (shoulder->elbow) and the torso (shoulder->hip),
     * i.e. the angle at the shoulder in elbow-shoulder-hip.
     */
    fun upperArmToTorsoAngle(
        shoulder: NormalizedLandmark,
        elbow: NormalizedLandmark,
        hip: NormalizedLandmark,
        imageWidth: Int,
        imageHeight: Int
    ): Double = calculateAngle(elbow, shoulder, hip, imageWidth, imageHeight)

    /**
     * Signed normalized vertical distance between [upper] and [lower] landmarks,
     * in normalized image units (0..1). Positive means [upper] sits *below*
     * [lower] on screen (image Y grows downward). Axis-independent so no image
     * dimensions are needed. Useful for "elbow at shoulder height" checks.
     */
    fun normalizedVerticalDistance(
        upper: NormalizedLandmark,
        lower: NormalizedLandmark
    ): Double = (upper.y() - lower.y()).toDouble()

    /**
     * Torso inclination relative to the vertical axis (kept for the existing
     * squat call site). Delegates to [angleFromVertical].
     */
    fun calculateTorsoAngle(
        shoulder: NormalizedLandmark,
        hip: NormalizedLandmark,
        imageWidth: Int,
        imageHeight: Int
    ): Double = angleFromVertical(shoulder, hip, imageWidth, imageHeight)
}
