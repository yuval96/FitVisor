package com.example.fitvisor__demo

import com.example.fitvisor__demo.exercises.squat.KneeOverToeMetrics
import com.example.fitvisor__demo.exercises.squat.SquatRuleEngine
import com.example.fitvisor__demo.model.RepError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic angle-sequence tests for [SquatRuleEngine].
 * Good torso = 10-20 deg (within the bottom-position range); knee depth reached at <= 110.
 * A torso value of 50 deg is above the 45 deg excessive-lean limit.
 *
 * The three cycle-defining knee-angle thresholds (leaving standing, reaching
 * depth, returning to standing) are each debounced (2 consecutive frames), so
 * every transition below is driven by a pair of identical frames via [arm] /
 * [reachDepth] / [returnToStanding] rather than a single reading.
 *
 * NOTE: [MIN_TORSO_INCLINATION] is currently 0.0 (a separate, pre-existing
 * change unrelated to this file), which makes `torsoAngle < 0.0` structurally
 * unreachable (torso angles are never negative) -- so INSUFFICIENT_TORSO_LEAN
 * can never actually trip right now. The two tests that exercise it
 * ([twoConsecutiveInsufficientTorsoLeanFrames_invalidateRep],
 * [fiveDegreesIsEnoughAndOnlyStandingIsExcluded]) are left in place, still
 * asserting the intended behavior, and will keep failing until that threshold
 * is revisited -- this is a known, separate issue, not caused by the
 * debouncing changes here.
 */
class SquatRuleEngineTest {

    private val engine = SquatRuleEngine()

    private fun feed(knee: Double, torso: Double) =
        engine.processFrame(knee, torso, false)

    /** Two identical frames at a bent-knee reading -> leaves standing (UP -> DESCENDING). */
    private fun arm(torso: Double = 10.0) {
        feed(140.0, torso)
        feed(140.0, torso)
    }

    /** Two identical frames at a depth reading -> reaches required depth (-> DOWN). Call while DESCENDING. */
    private fun reachDepth(torso: Double = 10.0) {
        feed(90.0, torso)
        feed(90.0, torso)
    }

    /** Two identical standing frames -> completes the repetition (correct, or as insufficient depth). */
    private fun returnToStanding(torso: Double = 10.0): com.example.fitvisor__demo.model.ExerciseAnalysisResult {
        feed(170.0, torso)
        return feed(170.0, torso)
    }

    @Test
    fun validFullRepetition_countsOneCorrect() {
        feed(170.0, 10.0) // standing
        arm()             // descending starts
        reachDepth()       // required depth
        val end = returnToStanding() // back to standing

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertTrue(end.errors.isEmpty())
    }

    @Test
    fun shallowRepetition_countsOneIncorrect() {
        feed(170.0, 10.0)
        arm()                         // descends but never reaches depth
        val end = returnToStanding()  // returns to standing

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_DEPTH))
    }

    @Test
    fun singleTorsoSpike_doesNotInvalidateRep() {
        feed(170.0, 10.0)
        feed(140.0, 50.0) // ONE noisy frame above 45 deg (arm frame 1)
        feed(140.0, 10.0) // torso good again (arm frame 2) -> DESCENDING
        reachDepth()
        val end = returnToStanding()

        assertTrue(end.isRepCompleted)
        // A single frame above the limit must not fail the rep.
        assertTrue(end.isRepCorrect)
        assertFalse(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun twoConsecutiveTorsoViolations_invalidateRep() {
        feed(170.0, 10.0)
        arm() // cleanly enters DESCENDING first (beginRep resets the torso gate on its own trip frame)
        feed(130.0, 50.0) // violation frame 1
        feed(130.0, 50.0) // violation frame 2 -> trips
        reachDepth()
        val end = returnToStanding()

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun singleInsufficientTorsoLeanFrame_doesNotInvalidateRep() {
        feed(170.0, 20.0)
        arm(torso = 20.0)
        feed(130.0, 0.0)  // one violating descending frame
        feed(90.0, 20.0)  // valid torso resets the debounce; depth frame 1
        feed(90.0, 20.0)  // depth frame 2 -> DOWN
        val end = returnToStanding(torso = 20.0)

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertFalse(end.errors.contains(RepError.INSUFFICIENT_TORSO_LEAN))
    }

    @Test
    fun twoConsecutiveInsufficientTorsoLeanFrames_invalidateRep() {
        feed(170.0, 20.0)
        arm(torso = 20.0)
        feed(130.0, 0.0) // descending violation frame 1
        feed(130.0, 0.0) // descending violation frame 2 -> trips
        feed(90.0, 20.0)  // depth frame 1
        feed(90.0, 20.0)  // depth frame 2 -> DOWN
        val end = returnToStanding(torso = 20.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_TORSO_LEAN))
    }

    @Test
    fun minimumAndMaximumTorsoLeanUseIndependentDebounceGates() {
        feed(170.0, 20.0)
        arm(torso = 20.0)
        feed(130.0, 50.0) // excessive frame 1
        feed(130.0, 0.0)  // insufficient frame 1; neither may trip yet
        feed(130.0, 20.0) // valid torso resets both gates
        reachDepth(torso = 20.0)
        val end = returnToStanding(torso = 20.0)

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertTrue(end.errors.isEmpty())
    }

    @Test
    fun fiveDegreesIsEnoughAndOnlyStandingIsExcluded() {
        val standing = feed(170.0, 0.0)    // standing upright: ignored
        feed(140.0, 0.0)
        val descending = feed(140.0, 0.0)  // descending: minimum lean applies
        feed(120.0, 5.0)                   // exactly 5 deg is valid and resets the gate
        feed(100.0, 5.0)
        feed(100.0, 5.0)                   // exactly 5 deg is also valid while down; depth reached
        feed(120.0, 0.0)                   // one violating DOWN/rising frame
        val end = returnToStanding(torso = 0.0) // standing completion frames are excluded

        assertEquals(null, standing.warning)
        assertEquals("Lean slightly forward", descending.warning)
        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertFalse(end.errors.contains(RepError.INSUFFICIENT_TORSO_LEAN))
    }

    @Test
    fun boundaryValuesAreApplied_155LeavesStanding_105ReachesDepth() {
        feed(170.0, 20.0)
        feed(155.0, 20.0) // below the 160 start threshold
        feed(155.0, 20.0)
        feed(105.0, 20.0) // reaches the 110 depth threshold
        feed(105.0, 20.0)
        val end = returnToStanding(torso = 20.0)

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
    }

    @Test
    fun torsoViolation_remainsLatched() {
        feed(170.0, 10.0)
        arm() // cleanly enters DESCENDING first
        feed(130.0, 50.0) // violation frame 1
        feed(130.0, 50.0) // violation frame 2 -> trips
        reachDepth(torso = 10.0) // torso good again, still down
        val end = returnToStanding()

        assertTrue(end.isRepCompleted)
        // A later correct frame must not erase the earlier (tripped) violation.
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun multipleErrors_accumulateInOneRep() {
        feed(170.0, 10.0)
        arm() // cleanly enters DESCENDING first
        feed(130.0, 50.0) // torso violation frame 1
        feed(130.0, 50.0) // torso violation frame 2 -> trips
        val end = returnToStanding() // returns without depth -> shallow

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_DEPTH))
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun repeatedTorsoViolation_isNotDuplicated() {
        feed(170.0, 10.0)
        arm() // cleanly enters DESCENDING first
        feed(130.0, 50.0) // violation frame 1
        feed(130.0, 50.0) // violation frame 2 -> trips
        feed(85.0, 50.0)  // still violating
        reachDepth(torso = 50.0)
        val end = returnToStanding()

        assertTrue(end.isRepCompleted)
        // Exactly one torso error despite many violating frames.
        assertEquals(1, end.errors.size)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun errorsResetBetweenReps() {
        // Rep 1: invalid (torso).
        feed(170.0, 10.0)
        arm(torso = 50.0)
        reachDepth(torso = 50.0)
        val first = returnToStanding()
        assertFalse(first.isRepCorrect)
        assertTrue(first.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))

        // Rep 2: clean.
        arm()
        reachDepth()
        val second = returnToStanding()
        assertTrue(second.isRepCorrect)
        assertTrue(second.errors.isEmpty())
    }

    @Test
    fun debugMetrics_captureMinMaxAndBottom() {
        feed(170.0, 10.0)  // standing (not yet in rep)
        arm(torso = 25.0)  // descending
        feed(90.0, 40.0)   // deepest point: knee 90, torso 40
        feed(90.0, 40.0)   // depth frame 2 -> DOWN, same deepest reading
        feed(120.0, 15.0)  // still down
        val end = returnToStanding()

        val metrics = end.debugMetrics
        requireNotNull(metrics)
        assertEquals(90.0, metrics.values["minKneeAngle"]!!, 0.001)
        assertEquals(40.0, metrics.values["maxTorsoAngle"]!!, 0.001)
        assertEquals(90.0, metrics.values["bottomKneeAngle"]!!, 0.001)
        assertEquals(40.0, metrics.values["bottomTorsoAngle"]!!, 0.001)
    }

    @Test
    fun debugMetrics_withoutKneeOverToe_isUnavailableButDoesNotBreakOtherMetrics() {
        engine.processFrame(170.0, 10.0, false)
        engine.processFrame(140.0, 25.0, false)
        engine.processFrame(140.0, 25.0, false) // arm frame 2 -> DESCENDING
        engine.processFrame(90.0, 40.0, false)  // no kneeOverToe passed (defaults to null)
        engine.processFrame(90.0, 40.0, false)  // depth frame 2 -> DOWN
        engine.processFrame(170.0, 10.0, false)
        val end = engine.processFrame(170.0, 10.0, false)

        val metrics = requireNotNull(end.debugMetrics)
        assertEquals(90.0, metrics.values["minKneeAngle"]!!, 0.001) // unaffected
        assertFalse(metrics.flags["kneeOverToeAvailable"]!!)
        assertTrue(metrics.values["ankleAngle"]!!.isNaN())
        assertTrue(metrics.ratios["normalizedKneeToeOffset"]!!.isNaN())
    }

    @Test
    fun debugMetrics_captureKneeOverToeAtTheDeepestKneeAngleFrame() {
        engine.processFrame(170.0, 10.0, false)
        engine.processFrame(140.0, 10.0, false,
            KneeOverToeMetrics(legIsLeft = true, confidence = 0.9f, ankleAngle = 80.0, normalizedKneeToeOffset = 0.2))
        engine.processFrame(140.0, 10.0, false,
            KneeOverToeMetrics(legIsLeft = true, confidence = 0.9f, ankleAngle = 80.0, normalizedKneeToeOffset = 0.2))
        // Deepest frame: this reading must be the one captured.
        val deepest = KneeOverToeMetrics(legIsLeft = false, confidence = 0.75f, ankleAngle = 70.0, normalizedKneeToeOffset = 0.9)
        engine.processFrame(90.0, 10.0, false, deepest)
        engine.processFrame(90.0, 10.0, false, deepest) // depth frame 2 -> DOWN
        engine.processFrame(120.0, 10.0, false,
            KneeOverToeMetrics(legIsLeft = true, confidence = 0.95f, ankleAngle = 85.0, normalizedKneeToeOffset = 0.1))
        engine.processFrame(170.0, 10.0, false)
        val end = engine.processFrame(170.0, 10.0, false)

        val metrics = requireNotNull(end.debugMetrics)
        assertTrue(metrics.flags["kneeOverToeAvailable"]!!)
        assertFalse(metrics.flags["kneeOverToeLegIsLeft"]!!) // the deepest-frame reading was the right leg
        assertEquals(70.0, metrics.values["ankleAngle"]!!, 0.001)
        assertEquals(0.9, metrics.ratios["normalizedKneeToeOffset"]!!, 0.001)
        assertEquals(0.75, metrics.ratios["kneeOverToeConfidence"]!!, 0.001)
    }

    @Test
    fun stayingStanding_doesNotDoubleCount() {
        // One valid rep.
        feed(170.0, 10.0)
        arm()
        reachDepth()
        returnToStanding()

        var extraCompletions = 0
        repeat(4) {
            if (feed(170.0, 10.0).isRepCompleted) extraCompletions++
        }
        assertEquals(0, extraCompletions)
    }
}
