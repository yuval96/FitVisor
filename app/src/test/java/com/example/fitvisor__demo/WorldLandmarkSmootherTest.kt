package com.example.fitvisor__demo

import com.example.fitvisor__demo.pose.WorldLandmarkSmoother
import com.google.mediapipe.tasks.components.containers.Landmark
import org.junit.Assert.assertEquals
import org.junit.Test

class WorldLandmarkSmootherTest {

    private fun lm(x: Float, y: Float, z: Float) = Landmark.create(x, y, z)

    @Test
    fun firstFrame_passesThroughUnchanged() {
        val smoother = WorldLandmarkSmoother(alpha = 0.5f)
        val first = listOf(lm(1f, 2f, 3f))

        val result = smoother.smooth(first)

        assertEquals(1f, result[0].x(), 0.0001f)
        assertEquals(2f, result[0].y(), 0.0001f)
        assertEquals(3f, result[0].z(), 0.0001f)
    }

    @Test
    fun secondFrame_blendsWithPrevious() {
        val smoother = WorldLandmarkSmoother(alpha = 0.5f)
        smoother.smooth(listOf(lm(0f, 0f, 0f)))

        val result = smoother.smooth(listOf(lm(1f, 1f, 1f)))

        // alpha=0.5 -> halfway between the previous (0) and current (1) frame.
        assertEquals(0.5f, result[0].x(), 0.0001f)
        assertEquals(0.5f, result[0].y(), 0.0001f)
        assertEquals(0.5f, result[0].z(), 0.0001f)
    }

    @Test
    fun repeatedIdenticalFrames_convergeToThatValue() {
        val smoother = WorldLandmarkSmoother(alpha = 0.3f)
        smoother.smooth(listOf(lm(0f, 0f, 0f)))

        var result = listOf(lm(0f, 0f, 0f))
        repeat(50) {
            result = smoother.smooth(listOf(lm(5f, -2f, 1f)))
        }

        assertEquals(5f, result[0].x(), 0.01f)
        assertEquals(-2f, result[0].y(), 0.01f)
        assertEquals(1f, result[0].z(), 0.01f)
    }

    @Test
    fun sizeMismatch_resetsToCurrentFrameUnchanged() {
        val smoother = WorldLandmarkSmoother(alpha = 0.5f)
        smoother.smooth(listOf(lm(0f, 0f, 0f), lm(0f, 0f, 0f)))

        val result = smoother.smooth(listOf(lm(9f, 9f, 9f)))

        assertEquals(1, result.size)
        assertEquals(9f, result[0].x(), 0.0001f)
    }

    @Test
    fun reset_forgetsPreviousFrame() {
        val smoother = WorldLandmarkSmoother(alpha = 0.5f)
        smoother.smooth(listOf(lm(0f, 0f, 0f)))
        smoother.reset()

        val result = smoother.smooth(listOf(lm(7f, 7f, 7f)))

        // No previous frame after reset -> passes through unchanged, exactly
        // like the very first frame.
        assertEquals(7f, result[0].x(), 0.0001f)
    }
}
