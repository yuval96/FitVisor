package com.example.fitvisor__demo

import com.example.fitvisor__demo.kinematics.KinematicCalculator
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Optional

/**
 * Geometry tests for [KinematicCalculator]. A square image is used so the
 * normalized coordinates map straight to a clean geometric shape.
 */
class KinematicCalculatorTest {

    private val size = 100

    private fun lm(x: Float, y: Float): NormalizedLandmark =
        NormalizedLandmark.create(x, y, 0f, Optional.of(1f), Optional.of(1f))

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
}
