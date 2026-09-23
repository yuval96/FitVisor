package com.example.fitvisor__demo

import com.example.fitvisor__demo.pose.SideSelector
import org.junit.Assert.assertEquals
import org.junit.Test

class SideSelectorTest {

    @Test
    fun firstFrame_picksWhicheverSideIsMoreConfident_noLeftBias() {
        val rightFacing = SideSelector()
        assertEquals(
            SideSelector.Side.RIGHT,
            rightFacing.select(leftConfidence = 0.3f, rightConfidence = 0.9f)
        )

        val leftFacing = SideSelector()
        assertEquals(
            SideSelector.Side.LEFT,
            leftFacing.select(leftConfidence = 0.9f, rightConfidence = 0.3f)
        )
    }

    @Test
    fun oneNoisyFrame_doesNotFlipTheLockedSide() {
        val selector = SideSelector()
        // Lock onto RIGHT.
        selector.select(leftConfidence = 0.3f, rightConfidence = 0.9f)

        // A single frame where LEFT briefly looks clearly better must not
        // flip the tracked leg -- that would inject a discontinuous angle
        // into a mid-repetition state machine from one jittery reading.
        val result = selector.select(leftConfidence = 0.9f, rightConfidence = 0.2f)

        assertEquals(SideSelector.Side.RIGHT, result)
    }

    @Test
    fun sustainedConfidenceFlip_switchesAfterDebounce() {
        val selector = SideSelector()
        selector.select(leftConfidence = 0.3f, rightConfidence = 0.9f) // locks RIGHT

        selector.select(leftConfidence = 0.9f, rightConfidence = 0.2f) // frame 1, not yet
        val result = selector.select(leftConfidence = 0.9f, rightConfidence = 0.2f) // frame 2

        assertEquals(SideSelector.Side.LEFT, result)
    }

    @Test
    fun reset_clearsLockedSide_nextFrameIsUnbiasedAgain() {
        val selector = SideSelector()
        selector.select(leftConfidence = 0.9f, rightConfidence = 0.2f) // locks LEFT
        selector.reset()

        val result = selector.select(leftConfidence = 0.2f, rightConfidence = 0.9f)

        assertEquals(SideSelector.Side.RIGHT, result)
    }

    @Test
    fun currentSideUnreliableAndOtherReliable_stillRequiresDebounce() {
        val selector = SideSelector()
        selector.select(leftConfidence = 0.9f, rightConfidence = 0.2f) // locks LEFT

        // LEFT drops below MIN_SIDE_CONFIDENCE while RIGHT is reliable, but
        // only for one frame so far.
        val firstDrop = selector.select(leftConfidence = 0.4f, rightConfidence = 0.6f)
        assertEquals(SideSelector.Side.LEFT, firstDrop)

        val secondDrop = selector.select(leftConfidence = 0.4f, rightConfidence = 0.6f)
        assertEquals(SideSelector.Side.RIGHT, secondDrop)
    }
}
