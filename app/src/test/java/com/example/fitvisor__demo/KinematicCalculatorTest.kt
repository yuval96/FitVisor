package com.example.fitvisor__demo

import com.example.fitvisor__demo.kinematics.KinematicCalculator
import com.google.mediapipe.tasks.components.containers.Landmark
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Optional
import kotlin.math.cos
import kotlin.math.sin

/**
 * Geometry tests for [KinematicCalculator]. A square image is used so the
 * normalized coordinates map straight to a clean geometric shape.
 */
class KinematicCalculatorTest {

    private val size = 100

    private fun lm(x: Float, y: Float): NormalizedLandmark =
        NormalizedLandmark.create(x, y, 0f, Optional.of(1f), Optional.of(1f))

    private fun wlm(x: Float, y: Float, z: Float = 0f): Landmark = Landmark.create(x, y, z)

    @Test
    fun threePointAngle_isNinetyDegrees() {
        val angle = KinematicCalculator.calculateAngle(
            lm(0f, 1f), // down from the vertex
            lm(0f, 0f), // vertex
            lm(1f, 0f), // right of the vertex
            size, size
        )
        assertEquals(90.0, angle, 0.5)
    }

    @Test
    fun threePointAngle_straightLineIsOneEighty() {
        val angle = KinematicCalculator.calculateAngle(
            lm(0f, 0f),
            lm(0.5f, 0f),
            lm(1f, 0f),
            size, size
        )
        assertEquals(180.0, angle, 0.5)
    }

    @Test
    fun verticalSegment_isZeroFromVertical() {
        val angle = KinematicCalculator.angleFromVertical(
            lm(0.5f, 0.1f),
            lm(0.5f, 0.9f),
            size, size
        )
        assertEquals(0.0, angle, 0.5)
    }

    @Test
    fun horizontalSegment_isZeroFromHorizontal() {
        val angle = KinematicCalculator.angleFromHorizontal(
            lm(0.1f, 0.5f),
            lm(0.9f, 0.5f),
            size, size
        )
        assertEquals(0.0, angle, 0.5)
    }

    @Test
    fun degenerateSegment_returnsNaN() {
        val angle = KinematicCalculator.angleFromVertical(
            lm(0.5f, 0.5f),
            lm(0.5f, 0.5f),
            size, size
        )
        assertTrue(angle.isNaN())
    }

    @Test
    fun kneeToeOffset_kneeDirectlyAboveAnkle_isZero() {
        val offset = KinematicCalculator.normalizedKneeToeOffset(
            knee = lm(0.5f, 0.3f),
            ankle = lm(0.5f, 0.5f),
            footIndex = lm(0.7f, 0.5f), // foot points toward +X
            size, size
        )
        assertEquals(0.0, offset, 0.01)
    }

    @Test
    fun kneeToeOffset_kneeOneFootLengthPastToe_isOne() {
        val offset = KinematicCalculator.normalizedKneeToeOffset(
            knee = lm(0.7f, 0.5f), // one foot-length forward, same direction as the foot
            ankle = lm(0.5f, 0.5f),
            footIndex = lm(0.7f, 0.5f),
            size, size
        )
        assertEquals(1.0, offset, 0.01)
    }

    @Test
    fun kneeToeOffset_kneeBehindAnkle_isNegative() {
        val offset = KinematicCalculator.normalizedKneeToeOffset(
            knee = lm(0.3f, 0.5f), // behind the ankle, opposite the foot direction
            ankle = lm(0.5f, 0.5f),
            footIndex = lm(0.7f, 0.5f),
            size, size
        )
        assertEquals(-1.0, offset, 0.01)
    }

    /**
     * Same geometric relationship (knee one foot-length forward) as
     * [kneeToeOffset_kneeOneFootLengthPastToe_isOne], but with the user facing
     * the opposite way (foot pointing -X instead of +X). A hardcoded
     * `knee.x > foot.x` rule would flip sign here; the vector-projection
     * implementation must not, since it reads direction from the foot itself.
     */
    @Test
    fun kneeToeOffset_isOrientationIndependent() {
        val offset = KinematicCalculator.normalizedKneeToeOffset(
            knee = lm(0.3f, 0.5f),
            ankle = lm(0.5f, 0.5f),
            footIndex = lm(0.3f, 0.5f), // foot points toward -X this time
            size, size
        )
        assertEquals(1.0, offset, 0.01)
    }

    /**
     * Realistic geometry: the ankle landmark sits above the floor while the
     * toe is on it, so the ankle->toe vector slopes down, and the knee is far
     * above both. Regression: the old full-vector projection read ~0 here
     * (the shank's vertical component swamped the forward distance) even
     * though the knee is exactly over the toe tip.
     */
    @Test
    fun kneeToeOffset_slopedFoot_kneeAboveToeTip_isOne() {
        val offset = KinematicCalculator.normalizedKneeToeOffset(
            knee = lm(0.67f, 0.13f),     // 42 units above the floor, directly over the toe
            ankle = lm(0.50f, 0.47f),    // 8 units above the floor
            footIndex = lm(0.67f, 0.55f), // toe on the floor, 17 units forward
            size, size
        )
        assertEquals(1.0, offset, 0.01)
    }

    /** Vertical jitter of the toe landmark used to swing the result by ~0.12 per unit; now it has no effect. */
    @Test
    fun kneeToeOffset_isInsensitiveToVerticalToeJitter() {
        val knee = lm(0.72f, 0.15f)
        val ankle = lm(0.50f, 0.47f)
        val low = KinematicCalculator.normalizedKneeToeOffset(knee, ankle, lm(0.67f, 0.57f), size, size)
        val high = KinematicCalculator.normalizedKneeToeOffset(knee, ankle, lm(0.67f, 0.53f), size, size)
        assertEquals(low, high, 1e-9)
        assertTrue(low > 1.0) // knee 5 units past the toe
    }

    /** Foot pointing at the camera (mostly vertical on screen): no usable forward axis. */
    @Test
    fun kneeToeOffset_footNotSideOn_returnsNaN() {
        val offset = KinematicCalculator.normalizedKneeToeOffset(
            knee = lm(0.52f, 0.13f),
            ankle = lm(0.50f, 0.47f),
            footIndex = lm(0.51f, 0.55f), // 1 unit sideways vs 8 units down
            size, size
        )
        assertTrue(offset.isNaN())
    }

    @Test
    fun kneeToeOffset_degenerateFoot_returnsNaN() {
        val offset = KinematicCalculator.normalizedKneeToeOffset(
            knee = lm(0.5f, 0.3f),
            ankle = lm(0.5f, 0.5f),
            footIndex = lm(0.5f, 0.5f), // ankle == footIndex
            size, size
        )
        assertTrue(offset.isNaN())
    }

    // --- 3D (world-landmark) geometry --------------------------------------

    @Test
    fun threePointAngle3D_isNinetyDegrees() {
        val angle = KinematicCalculator.calculateAngle3D(
            wlm(0f, 1f, 0f), // below the vertex
            wlm(0f, 0f, 0f), // vertex
            wlm(1f, 0f, 0f)  // right of the vertex
        )
        assertEquals(90.0, angle, 0.01)
    }

    @Test
    fun threePointAngle3D_straightLineIsOneEighty() {
        val angle = KinematicCalculator.calculateAngle3D(
            wlm(0f, 0f, 0f),
            wlm(0.5f, 0f, 0f),
            wlm(1f, 0f, 0f)
        )
        assertEquals(180.0, angle, 0.01)
    }

    @Test
    fun threePointAngle3D_degenerateVector_returnsNaN() {
        val angle = KinematicCalculator.calculateAngle3D(
            wlm(0f, 0f, 0f),
            wlm(0f, 0f, 0f), // coincides with the vertex
            wlm(1f, 0f, 0f)
        )
        assertTrue(angle.isNaN())
    }

    /**
     * The whole point of the 3D version: a joint bent 90 degrees stays
     * readable as 90 degrees no matter which way the subject is rotated
     * (around the vertical Y axis) relative to the camera -- unlike a 2D
     * image-space projection of the same joint, which distorts as soon as
     * the subject isn't in an exact profile stance.
     */
    @Test
    fun threePointAngle3D_isInvariantToRotationAroundVerticalAxis() {
        fun angleAtRotation(radians: Double): Double {
            // A right angle in the X-Z plane at the origin: one arm along
            // +X, the other along +Z, then both rotated together by
            // [radians] around the vertical (Y) axis -- simulating the
            // subject turning left/right relative to the camera while
            // holding the same true 3D joint angle.
            fun rotate(x: Float, z: Float): Pair<Float, Float> {
                val rx = x * cos(radians) - z * sin(radians)
                val rz = x * sin(radians) + z * cos(radians)
                return rx.toFloat() to rz.toFloat()
            }
            val (x1, z1) = rotate(1f, 0f)
            val (x2, z2) = rotate(0f, 1f)
            return KinematicCalculator.calculateAngle3D(
                wlm(x1, 0f, z1),
                wlm(0f, 0f, 0f),
                wlm(x2, 0f, z2)
            )
        }

        assertEquals(90.0, angleAtRotation(0.0), 0.01)
        assertEquals(90.0, angleAtRotation(Math.PI / 4), 0.01)   // 45 degrees
        assertEquals(90.0, angleAtRotation(Math.PI / 2), 0.01)   // 90 degrees
        assertEquals(90.0, angleAtRotation(1.1), 0.01)           // arbitrary angle
    }

    @Test
    fun verticalSegment3D_isZeroFromVertical() {
        val angle = KinematicCalculator.angleFromVertical3D(
            wlm(0.5f, 0.1f, 0f),
            wlm(0.5f, 0.9f, 0f)
        )
        assertEquals(0.0, angle, 0.01)
    }

    @Test
    fun verticalSegment3D_withMatchingXZ_isStillZeroRegardlessOfZ() {
        // Same X/Z at both ends (pure vertical segment) reads 0 deg from
        // vertical no matter what that shared X/Z offset is -- i.e. no
        // matter how far forward/back (Z) the subject stands from the
        // camera, unlike the 2D version which has no Z axis at all.
        val angle = KinematicCalculator.angleFromVertical3D(
            wlm(0.5f, 0.1f, 0.7f),
            wlm(0.5f, 0.9f, 0.7f)
        )
        assertEquals(0.0, angle, 0.01)
    }

    @Test
    fun tiltedSegment3D_notPurelyVertical_isGreaterThanZero() {
        val angle = KinematicCalculator.angleFromVertical3D(
            wlm(0.3f, 0.1f, 0.4f),
            wlm(0.6f, 0.9f, 0.1f)
        )
        assertTrue("differing X/Z must not read as perfectly vertical", angle > 0.0)
    }

    @Test
    fun horizontalSegment3D_isNinetyFromVertical() {
        val angle = KinematicCalculator.angleFromVertical3D(
            wlm(0.1f, 0.5f, 0f),
            wlm(0.9f, 0.5f, 0f)
        )
        assertEquals(90.0, angle, 0.01)
    }

    @Test
    fun degenerateSegment3D_returnsNaN() {
        val angle = KinematicCalculator.angleFromVertical3D(
            wlm(0.5f, 0.5f, 0.5f),
            wlm(0.5f, 0.5f, 0.5f)
        )
        assertTrue(angle.isNaN())
    }
}
