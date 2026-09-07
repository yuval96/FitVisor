package com.example.fitvisor__demo

import com.example.fitvisor__demo.exercises.shoulderpress.ShoulderPressRuleEngine
import com.example.fitvisor__demo.model.RepError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic sequence tests for the phased [ShoulderPressRuleEngine].
 *
 * A repetition is `START -> TOP_REACHED -> START` and is counted only on the
 * final `TOP_REACHED -> START` transition.
 *  - START is elbows at shoulder height: `elbowShoulderVertical ~= 0`.
 *  - TOP_REACHED is `elbowAngle >= 150`.
 *
 * Defaults describe a clean racked frame (elbows at shoulder height, upright
 * torso). During a press the elbows rise well above the shoulders, so those
 * frames pass an explicit negative [elbowShoulderVertical]. [bodyScale] = 0.4
 * gives a shoulder-height tolerance of max(0.30*0.4, 0.10) = 0.12.
 */
class ShoulderPressRuleEngineTest {

    private val engine = ShoulderPressRuleEngine()

    private fun feed(
        elbow: Double,
        torso: Double = 3.0,
        elbowShoulderVertical: Double = 0.0,
        bodyScale: Double = 0.4
    ) = engine.processFrame(
        elbowAngle = elbow,
        torsoVerticalAngle = torso,
        elbowShoulderVertical = elbowShoulderVertical,
        bodyScale = bodyScale
    )

    private fun establishStart() {
        feed(100.0)
        feed(100.0) // two stable frames arm START
    }

    private fun reachTop(elbowShoulderVertical: Double = -0.30) {
        feed(160.0, elbowShoulderVertical = elbowShoulderVertical)
        feed(160.0, elbowShoulderVertical = elbowShoulderVertical)
    }

    private fun returnToStart() = feed(100.0, elbowShoulderVertical = 0.0).let {
        feed(100.0, elbowShoulderVertical = 0.0)
    }

    @Test
    fun fullSequence_countsOneCorrect_onlyOnReturn() {
        establishStart()

        val pressing = feed(155.0, elbowShoulderVertical = -0.30)
        assertFalse(pressing.isRepCompleted)
        assertEquals("PRESSING", pressing.phaseName)

        val press = feed(155.0, elbowShoulderVertical = -0.30)
        assertFalse("reaching the top must not count", press.isRepCompleted)
        assertEquals("TOP_REACHED", press.phaseName)

        val hold = feed(175.0, elbowShoulderVertical = -0.40)  // still at the top
        assertFalse(hold.isRepCompleted)

        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)
        assertTrue("rep counts on returning to START", end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertEquals("START", end.phaseName)
    }

    @Test
    fun completedRepDebugMetricsSeparateAnglesFromFlags() {
        establishStart()
        reachTop()
        val end = returnToStart()

        val metrics = end.debugMetrics!!
        assertTrue("startDetected/topReached belong in flags, not the angle map",
            metrics.values.keys.none { it == "startDetected" || it == "topReached" })
        assertEquals(mapOf("startDetected" to true, "topReached" to true), metrics.flags)
        assertTrue(metrics.values.containsKey("minElbowAngle"))
    }

    @Test
    fun reachingTopWithoutReturning_doesNotCount() {
        establishStart()
        reachTop()

        var completions = 0
        repeat(5) {
            if (feed(175.0, elbowShoulderVertical = -0.40).isRepCompleted) completions++
        }
        assertEquals(0, completions) // holding at the top never counts
    }

    @Test
    fun heldTopEvenInsideHeightTolerance_neverRepeats() {
        establishStart()

        var completions = 0
        repeat(12) {
            // Reproduces the device issue: straight elbows at the top while the
            // noisy height reading also falls inside the old START tolerance.
            if (feed(170.0, elbowShoulderVertical = 0.0).isRepCompleted) completions++
        }

        assertEquals(0, completions)
    }

    @Test
    fun partialPress_neverReachingTop_doesNotCount() {
        establishStart()
        feed(140.0, elbowShoulderVertical = -0.20)   // pressed, but elbow < 150
        val back = feed(100.0, elbowShoulderVertical = 0.0) // back to shoulder height

        assertFalse(back.isRepCompleted) // no TOP_REACHED -> no rep
    }

    @Test
    fun torsoLeanDuringRep_countsOneIncorrect() {
        establishStart()
        feed(155.0, elbowShoulderVertical = -0.30, torso = 20.0) // gate count 1
        feed(175.0, elbowShoulderVertical = -0.40, torso = 20.0) // gate trips (2 consec)
        feed(100.0, elbowShoulderVertical = 0.0, torso = 3.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0, torso = 3.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun stayingAtStartAfterRep_doesNotDoubleCount() {
        establishStart()
        reachTop()
        returnToStart()

        var extra = 0
        repeat(4) {
            if (feed(100.0, elbowShoulderVertical = 0.0).isRepCompleted) extra++
        }
        assertEquals(0, extra)
    }

    @Test
    fun secondFullSequence_countsAgain() {
        // Rep 1
        establishStart()
        reachTop()
        val first = returnToStart()
        assertTrue(first.isRepCompleted)

        // Rep 2
        reachTop()
        val second = returnToStart()
        assertTrue(second.isRepCompleted)
        assertTrue(second.isRepCorrect)
    }
}
