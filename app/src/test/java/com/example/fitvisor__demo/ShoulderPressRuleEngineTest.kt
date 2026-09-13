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
        bodyScale: Double = 0.4,
        leftElbow: Double = Double.NaN,
        rightElbow: Double = Double.NaN,
        leftArmVertical: Double = Double.NaN,
        rightArmVertical: Double = Double.NaN
    ) = engine.processFrame(
        elbowAngle = elbow,
        torsoVerticalAngle = torso,
        elbowShoulderVertical = elbowShoulderVertical,
        bodyScale = bodyScale,
        leftElbowAngle = leftElbow,
        rightElbowAngle = rightElbow,
        leftArmVertical = leftArmVertical,
        rightArmVertical = rightArmVertical
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

    // --- Arm verticality (shoulder->wrist vs. vertical) ------------------

    @Test
    fun armsClearlyNotVertical_duringRep_countsOneIncorrect() {
        establishStart()
        // Pressing forward: elbows reach lockout, but the shoulder->wrist
        // vector stays well past the 30-degree violation threshold.
        repeat(3) {
            feed(160.0, elbowShoulderVertical = -0.30, leftArmVertical = 40.0, rightArmVertical = 38.0)
        }
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.ARMS_NOT_VERTICAL))
    }

    @Test
    fun armsInGreyZone_neverInvalidatesRep() {
        establishStart()
        // 20-30 degrees is a tolerated grey zone, not a violation.
        repeat(5) {
            feed(160.0, elbowShoulderVertical = -0.30, leftArmVertical = 25.0, rightArmVertical = 24.0)
        }
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertFalse(end.errors.contains(RepError.ARMS_NOT_VERTICAL))
    }

    @Test
    fun singleFrameArmsNotVertical_doesNotInvalidate() {
        establishStart()
        feed(160.0, elbowShoulderVertical = -0.30, leftArmVertical = 45.0, rightArmVertical = 45.0)
        feed(160.0, elbowShoulderVertical = -0.30) // back to vertical (no data -> no issue), gate resets
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
    }

    @Test
    fun restingAtRackPosition_doesNotTriggerArmsNotVertical() {
        // Regression: the shoulder->wrist vector is naturally near-horizontal
        // (~80-90 deg from vertical) at the racked START position by
        // definition -- the wrist sits at shoulder height there. Holding at
        // rest, even for many frames, must never itself count as "arms not
        // vertical"; the check only applies once genuinely near lockout.
        establishStart()
        repeat(10) {
            feed(100.0, elbowShoulderVertical = 0.0, leftArmVertical = 85.0, rightArmVertical = 82.0)
        }
        reachTop()
        val end = returnToStart()

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertFalse(end.errors.contains(RepError.ARMS_NOT_VERTICAL))
    }

    // --- Left/right symmetry ----------------------------------------------

    @Test
    fun asymmetricArms_duringRep_countsOneIncorrect() {
        establishStart()
        repeat(3) {
            feed(160.0, elbowShoulderVertical = -0.30, leftElbow = 170.0, rightElbow = 130.0) // diff 40
        }
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.ASYMMETRIC_ARM_POSITION))
    }

    @Test
    fun briefSingleFrameAsymmetry_doesNotInvalidate() {
        establishStart()
        feed(155.0, elbowShoulderVertical = -0.30, leftElbow = 170.0, rightElbow = 120.0) // 1 noisy frame
        reachTop() // no per-side data on these frames -> the gate resets
        val end = returnToStart()

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertFalse(end.errors.contains(RepError.ASYMMETRIC_ARM_POSITION))
    }

    @Test
    fun mildNaturalAsymmetry_withinTolerance_staysCorrect() {
        establishStart()
        repeat(4) {
            feed(160.0, elbowShoulderVertical = -0.30, leftElbow = 158.0, rightElbow = 150.0) // diff 8
        }
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
    }

    @Test
    fun restingAtRackPosition_doesNotTriggerAsymmetry() {
        // Regression: symmetry is only meaningful once a real press is
        // underway, not while still racked at the bottom.
        establishStart()
        repeat(10) {
            feed(100.0, elbowShoulderVertical = 0.0, leftElbow = 100.0, rightElbow = 60.0) // diff 40
        }
        reachTop()
        val end = returnToStart()

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertFalse(end.errors.contains(RepError.ASYMMETRIC_ARM_POSITION))
    }

    // --- Insufficient elbow extension --------------------------------------

    @Test
    fun clearPressAttempt_neverReachingTop_countsOneIncorrect() {
        establishStart()
        feed(147.0, elbowShoulderVertical = -0.15) // clear press progress, short of the 150 top
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0) // stable start -> scores the attempt

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_ELBOW_EXTENSION))
        assertEquals("START", end.phaseName)
    }

    @Test
    fun tinyJitterNearStart_doesNotCountAsIncorrect() {
        establishStart()
        feed(138.0, elbowShoulderVertical = -0.05) // barely past START, well short of a real press
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)

        assertFalse(end.isRepCompleted)
        assertEquals("START", end.phaseName)
    }
}
