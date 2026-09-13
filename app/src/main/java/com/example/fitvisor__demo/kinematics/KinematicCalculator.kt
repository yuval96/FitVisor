package com.example.fitvisor__demo.kinematics

import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * Normalization and Kinematic Calculator Module.
 * Responsible for computing joint angles and biomechanical features from landmarks.
 *
 * All 2D angle calculations multiply the normalized coordinates by the image
 * width/height first, because MediaPipe normalizes X and Y independently on each
 * axis; skipping this would distort every angle on non-square frames.
 *
 * The `*3D` functions below instead work on MediaPipe's [Landmark] ("world
 * landmark") output -- real-world, metric 3D coordinates -- rather than the
 * normalized 2D image-space [NormalizedLandmark]. A 2D projection of a joint
 * angle is only accurate when the joint's plane of motion is parallel to the
 * camera's image plane (e.g. an exact side-on profile for a squat); any
 * rotation of the subject relative to the camera distorts it, and by a
 * different amount depending on the joint's momentary 3D orientation -- so
 * the same *true* angle can read very differently frame to frame as a limb
 * moves, even with a perfectly steady camera and consistent form. The 3D
 * versions compute the real angle between world-space vectors instead, which
 * is far less sensitive to exactly how the subject is rotated toward the
 * camera.
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
     * 3D generalization of [calculateAngle]: the real angle at [midPoint],
     * in degrees (0..180), computed from MediaPipe's real-world (metric)
     * [Landmark] coordinates instead of a 2D image projection. Unlike the 2D
     * version this does not depend on the subject being in an exact profile
     * stance relative to the camera -- see the class doc.
     * Returns [Double.NaN] for a degenerate (zero-length) input vector.
     */
    fun calculateAngle3D(
        firstPoint: Landmark,
        midPoint: Landmark,
        lastPoint: Landmark
    ): Double {
        val v1x = (firstPoint.x() - midPoint.x()).toDouble()
        val v1y = (firstPoint.y() - midPoint.y()).toDouble()
        val v1z = (firstPoint.z() - midPoint.z()).toDouble()

        val v2x = (lastPoint.x() - midPoint.x()).toDouble()
        val v2y = (lastPoint.y() - midPoint.y()).toDouble()
        val v2z = (lastPoint.z() - midPoint.z()).toDouble()

        val v1Length = sqrt(v1x * v1x + v1y * v1y + v1z * v1z)
        val v2Length = sqrt(v2x * v2x + v2y * v2y + v2z * v2z)
        if (v1Length == 0.0 || v2Length == 0.0) return Double.NaN

        val dot = v1x * v2x + v1y * v2y + v1z * v2z
        // Clamp before acos: floating-point error can push a near-parallel or
        // near-opposite pair's cosine fractionally outside [-1, 1], which
        // would otherwise make acos return NaN for a perfectly valid angle.
        val cosAngle = (dot / (v1Length * v2Length)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosAngle))
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
     * 3D generalization of [angleFromVertical]: deviation of the segment
     * [a]->[b] from the vertical axis, in degrees (0..90), using MediaPipe's
     * real-world (metric) [Landmark] coordinates. The vertical axis is the
     * same one the world landmarks are estimated against (Y, matching the 2D
     * image axis) -- valid as long as the phone is held reasonably level,
     * exactly like the 2D version. Unlike the 2D version, this is not
     * distorted by the subject's rotation around that vertical axis relative
     * to the camera (e.g. not standing in an exact profile stance).
     * Returns [Double.NaN] for a degenerate (zero-length) segment.
     */
    fun angleFromVertical3D(a: Landmark, b: Landmark): Double {
        val deltaX = (a.x() - b.x()).toDouble()
        val deltaY = (a.y() - b.y()).toDouble()
        val deltaZ = (a.z() - b.z()).toDouble()

        val length = sqrt(deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ)
        if (length == 0.0) return Double.NaN

        val cosAngle = (abs(deltaY) / length).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosAngle))
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

    /**
     * How far [knee] sits forward of [ankle], projected onto the ankle->
     * [footIndex] direction and normalized by foot length (the ankle-to-toe
     * distance) — a "knee-over-toe" signal in units of foot lengths: ~0 means
     * the knee is directly above the ankle, and approaching/exceeding 1.0
     * means it has traveled as far forward as the toe itself.
     *
     * Deliberately vector/foot-relative rather than a raw `knee.x > foot.x`
     * comparison: since both vectors are derived from the same two body
     * landmarks, the result is correct regardless of which way the user faces,
     * front-camera mirroring, or distance from the camera (no absolute image
     * coordinate or resolution dependence).
     *
     * Returns [Double.NaN] for a degenerate (zero-length) foot vector.
     */
    fun normalizedKneeToeOffset(
        knee: NormalizedLandmark,
        ankle: NormalizedLandmark,
        footIndex: NormalizedLandmark,
        imageWidth: Int,
        imageHeight: Int
    ): Double {
        val footX = (footIndex.x() - ankle.x()) * imageWidth
        val footY = (footIndex.y() - ankle.y()) * imageHeight
        val footLength = kotlin.math.sqrt((footX * footX + footY * footY).toDouble())
        if (footLength == 0.0) return Double.NaN

        val kneeX = (knee.x() - ankle.x()) * imageWidth
        val kneeY = (knee.y() - ankle.y()) * imageHeight

        // Scalar projection of the knee vector onto the foot's forward axis.
        val forwardDistance = (kneeX * footX + kneeY * footY) / footLength
        return forwardDistance / footLength
    }
}
