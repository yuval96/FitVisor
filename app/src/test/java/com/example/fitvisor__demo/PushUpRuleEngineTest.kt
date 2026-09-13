package com.example.fitvisor__demo

import com.example.fitvisor__demo.exercises.pushup.PushUpRuleEngine
import com.example.fitvisor__demo.model.RepError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic angle-sequence tests for [PushUpRuleEngine].
 * Straight body = 178 deg body-line; near-horizontal hips = 5 deg.
 *
 * The four cycle-defining elbow-angle thresholds are each debounced (2
 * consecutive frames), so every transition below is driven by a pair of
 * identical frames via [descend] / [reachBottom] / [exitBottom] /
 * [returnToTop] rather than a single reading.
 */
class PushUpRuleEngineTest {

    private val engine = PushUpRuleEngine()

    private fun feed(elbow: Double, bodyLine: Double = 178.0, horizontal: Double = 5.0) =
        engine.processFrame(elbow, bodyLine, horizontal)

    /** Two identical frames -> leaves the top (TOP -> DESCENDING). */
    private fun descend(bodyLine: Double = 178.0, horizontal: Double = 5.0) {
        feed(140.0, bodyLine, horizontal)
        feed(140.0, bodyLine, horizontal)
    }

    /** Two identical frames -> reaches required depth (-> BOTTOM). */
    private fun reachBottom(bodyLine: Double = 178.0, horizontal: Double = 5.0) {
        feed(90.0, bodyLine, horizontal)
        feed(90.0, bodyLine, horizontal)
    }

    /** Two identical frames -> leaves the bottom (BOTTOM -> ASCENDING). */
    private fun exitBottom(bodyLine: Double = 178.0, horizontal: Double = 5.0) {
        feed(120.0, bodyLine, horizontal)
        feed(120.0, bodyLine, horizontal)
    }

    /** Two identical top frames -> completes the repetition (correct, or as insufficient depth). */
    private fun returnToTop(bodyLine: Double = 178.0, horizontal: Double = 5.0) =
        feed(170.0, bodyLine, horizontal).let { feed(170.0, bodyLine, horizontal) }

    @Test
    fun validCycle_countsOneCorrect() {
        feed(170.0) // TOP
        descend()   // descending
        reachBottom() // bottom depth reached
        exitBottom()  // ascending
        val end = returnToTop() // back to top

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
    }

    @Test
    fun incompleteDepth_countsOneIncorrect() {
        feed(170.0)
        descend()             // descends but not to depth
        val end = returnToTop() // returns up early

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_DEPTH))
    }

    @Test
    fun bodyLineViolation_remainsLatched() {
        feed(170.0)
        descend() // cleanly enters DESCENDING first (startRep resets the body-line gate on its own trip frame)
        feed(130.0, bodyLine = 150.0) // bent body, violation frame 1
        feed(130.0, bodyLine = 150.0) // violation frame 2 -> latches (2 consecutive)
        reachBottom(bodyLine = 178.0)
        exitBottom(bodyLine = 178.0) // straight again, ascending
        val end = returnToTop(bodyLine = 178.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.BODY_NOT_STRAIGHT))
    }

    @Test
    fun debugMetrics_areCollectedOnCompletion() {
        feed(170.0)
        descend()
        feed(85.0) // bottom
        feed(85.0) // depth frame 2 -> BOTTOM
        exitBottom()
        val end = returnToTop()

        val metrics = end.debugMetrics
        requireNotNull(metrics)
        assertEquals(85.0, metrics.values["minElbowAngle"]!!, 0.001)
        assertEquals(85.0, metrics.values["bottomElbowAngle"]!!, 0.001)
    }

    @Test
    fun stayingAtTop_doesNotDoubleCount() {
        feed(170.0)
        descend()
        reachBottom()
        exitBottom()
        returnToTop() // one completed cycle

        var extraCompletions = 0
        repeat(4) {
            if (feed(170.0).isRepCompleted) extraCompletions++
        }
        assertEquals(0, extraCompletions)
    }
}
