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
    fun singleFramePress_neverReachingTop_doesNotCount() {
        // A held partial press *is* scored (see the insufficient-extension
        // tests below); a single frame past the rack is not.
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
        feed(147.0, elbowShoulderVertical = -0.15) // held for a frame -> sustains PRESSING past the bare minimum
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0) // stable start -> scores the attempt

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_ELBOW_EXTENSION))
        assertEquals("START", end.phaseName)
    }

    @Test
    fun tinyJitterNearStart_doesNotCountAsIncorrect() {
        // The fastest possible PRESSING->START round trip: a single frame
        // crossing the START boundary, immediately followed by the 2
        // consecutive frames rackReturnGate itself needs to call it a stable
        // return. This bare minimum must never be scored as a fault -- only
        // an attempt held for at least one frame longer should be (see
        // clearPressAttempt_neverReachingTop_countsOneIncorrect).
        establishStart()
        feed(138.0, elbowShoulderVertical = -0.05) // barely past START, well short of a real press
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)

        assertFalse(end.isRepCompleted)
        assertEquals("START", end.phaseName)
    }

    @Test
    fun halfPress_belowOldBoundary_countsInsufficientExtension() {
        // Regression: "press started" used to be a fixed elbow > 135, so a
        // half press to ~125 -- elbows still inside the shoulder-height
        // tolerance -- looked exactly like being racked: neither counted
        // nor faulted. It's now relative to the rack angle (100 -> 120).
        establishStart()
        repeat(4) { feed(125.0, elbowShoulderVertical = -0.05) }
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_ELBOW_EXTENSION))
    }

    @Test
    fun slowLoweringAfterFullRep_doesNotScorePhantomPartialPress() {
        // A full rep completes on the way down (~130, still above a fixed
        // ~115 press-start line). A slow lowering then crosses 125..116
        // while already back in START; with an absolute threshold that read
        // as a new press and faulted on reaching the rack. The learned rack
        // angle follows the lowering down instead.
        establishStart()
        reachTop()
        feed(145.0, elbowShoulderVertical = -0.10) // lowering
        feed(130.0, elbowShoulderVertical = 0.0)
        val rep = feed(128.0, elbowShoulderVertical = 0.0)
        assertTrue(rep.isRepCompleted)
        assertTrue(rep.isRepCorrect)

        var extra = 0
        for (elbow in listOf(126.0, 124.0, 122.0, 120.0, 118.0, 116.0, 112.0, 108.0, 104.0, 100.0, 100.0, 100.0)) {
            if (feed(elbow, elbowShoulderVertical = 0.0).isRepCompleted) extra++
        }
        assertEquals(0, extra)
    }

    @Test
    fun armsLoweredBelowShoulders_halfBent_isNotAPress() {
        // Dropping the arms toward the sides can open the elbow just like a
        // press does, but the elbows move down, not up.
        establishStart()
        repeat(4) { feed(125.0, elbowShoulderVertical = 0.25) }
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)

        assertFalse(end.isRepCompleted)
    }

    @Test
    fun twoFrameBlipAboveRack_isIgnored() {
        establishStart()
        feed(125.0, elbowShoulderVertical = -0.05)
        feed(125.0, elbowShoulderVertical = -0.05) // enters PRESSING on this (debounced) frame
        feed(100.0, elbowShoulderVertical = 0.0)
        val end = feed(100.0, elbowShoulderVertical = 0.0)

        assertFalse(end.isRepCompleted)
        assertEquals("START", end.phaseName)
    }

    @Test
    fun openRack_smallWobble_isNotAPress() {
        // The press-start boundary is personal: from a wide-grip rack at 118
        // it sits at the old 135 cap, so a wobble to 125 is not a press.
        feed(118.0)
        feed(118.0)
        repeat(4) { feed(125.0, elbowShoulderVertical = -0.05) }
        feed(118.0)
        val end = feed(118.0)

        assertFalse(end.isRepCompleted)
        assertEquals("START", end.phaseName)
    }
}
