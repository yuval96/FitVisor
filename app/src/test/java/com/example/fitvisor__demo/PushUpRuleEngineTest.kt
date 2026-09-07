package com.example.fitvisor__demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic angle-sequence tests for [PushUpRuleEngine].
 * Straight body = 178 deg body-line; near-horizontal hips = 5 deg.
 */
class PushUpRuleEngineTest {

    private val engine = PushUpRuleEngine()

    private fun feed(elbow: Double, bodyLine: Double = 178.0, horizontal: Double = 5.0) =
        engine.processFrame(elbow, bodyLine, horizontal)

    @Test
    fun validCycle_countsOneCorrect() {
        feed(170.0) // TOP
        feed(140.0) // descending
        feed(90.0)  // bottom depth reached
        feed(120.0) // ascending
        val end = feed(170.0) // back to top

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
    }

    @Test
    fun incompleteDepth_countsOneIncorrect() {
        feed(170.0)
        feed(130.0)          // descends but not to depth
        val end = feed(170.0) // returns up early

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_DEPTH))
    }

    @Test
    fun bodyLineViolation_remainsLatched() {
        feed(170.0)
        feed(140.0, bodyLine = 150.0) // bent body, frame 1 of the rep
        feed(90.0, bodyLine = 150.0)  // bent body, latches (2 consecutive)
        feed(120.0, bodyLine = 178.0) // straight again, ascending
        val end = feed(170.0, bodyLine = 178.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.BODY_NOT_STRAIGHT))
    }

    @Test
    fun debugMetrics_areCollectedOnCompletion() {
        feed(170.0)
        feed(140.0)
        feed(85.0) // bottom
        feed(120.0)
        val end = feed(170.0)

        val metrics = end.debugMetrics
        requireNotNull(metrics)
        assertEquals(85.0, metrics.values["minElbowAngle"]!!, 0.001)
        assertEquals(85.0, metrics.values["bottomElbowAngle"]!!, 0.001)
    }

    @Test
    fun stayingAtTop_doesNotDoubleCount() {
        feed(170.0)
        feed(140.0)
        feed(90.0)
        feed(120.0)
        feed(170.0) // one completed cycle

        var extraCompletions = 0
        repeat(4) {
            if (feed(170.0).isRepCompleted) extraCompletions++
        }
        assertEquals(0, extraCompletions)
    }
}
