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
 * Good torso = 5 deg (within the 5..45 deg bottom-position range); knee depth reached at <= 110.
 * A torso value of 50 deg is above the 45 deg limit.
 */
class SquatRuleEngineTest {

    private val engine = SquatRuleEngine()

    private fun feed(knee: Double, torso: Double) =
        engine.processFrame(knee, torso, false)

    @Test
    fun validFullRepetition_countsOneCorrect() {
        feed(170.0, 10.0) // standing
        feed(140.0, 10.0) // descending starts
        feed(90.0, 10.0)  // required depth
        val end = feed(170.0, 10.0) // back to standing

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertTrue(end.errors.isEmpty())
    }

    @Test
    fun shallowRepetition_countsOneIncorrect() {
        feed(170.0, 10.0)
        feed(140.0, 10.0)          // descends but never reaches depth
        val end = feed(170.0, 10.0) // returns to standing

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_DEPTH))
    }

    @Test
    fun singleTorsoSpike_doesNotInvalidateRep() {
        feed(170.0, 10.0)
        feed(140.0, 50.0) // ONE noisy frame above 45 deg
        feed(90.0, 10.0)  // torso good again, depth reached
        val end = feed(170.0, 10.0)

        assertTrue(end.isRepCompleted)
        // A single frame above the limit must not fail the rep.
        assertTrue(end.isRepCorrect)
        assertFalse(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun twoConsecutiveTorsoViolations_invalidateRep() {
        feed(170.0, 10.0)
        feed(140.0, 50.0) // violation frame 1
        feed(90.0, 50.0)  // violation frame 2 -> trips, depth also reached
        val end = feed(170.0, 10.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun singleInsufficientTorsoLeanFrame_doesNotInvalidateRep() {
        feed(170.0, 20.0)
        feed(140.0, 0.0)  // one violating descending frame
        feed(90.0, 20.0)  // valid down frame resets the debounce
        val end = feed(170.0, 20.0)

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertFalse(end.errors.contains(RepError.INSUFFICIENT_TORSO_LEAN))
    }

    @Test
    fun twoConsecutiveInsufficientTorsoLeanFrames_invalidateRep() {
        feed(170.0, 20.0)
        feed(140.0, 0.0) // descending violation frame 1
        feed(90.0, 0.0)  // down violation frame 2 -> trips
        val end = feed(170.0, 20.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_TORSO_LEAN))
    }

    @Test
    fun minimumAndMaximumTorsoLeanUseIndependentDebounceGates() {
        feed(170.0, 20.0)
        feed(140.0, 50.0) // excessive frame 1
        feed(90.0, 0.0)   // insufficient frame 1; neither may trip yet
        feed(95.0, 20.0)  // valid torso resets both gates
        val end = feed(170.0, 20.0)

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertTrue(end.errors.isEmpty())
    }

    @Test
    fun fiveDegreesIsEnoughAndOnlyStandingIsExcluded() {
        val standing = feed(170.0, 0.0)    // standing upright: ignored
        val descending = feed(140.0, 0.0)  // descending: minimum lean applies
        feed(120.0, 5.0)                   // exactly 5 deg is valid and resets the gate
        feed(100.0, 5.0)                   // exactly 5 deg is also valid while down
        feed(120.0, 0.0)                   // one violating DOWN/rising frame
        val end = feed(170.0, 0.0)         // standing completion frame is excluded

        assertEquals(null, standing.warning)
        assertEquals("Lean slightly forward", descending.warning)
        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
        assertFalse(end.errors.contains(RepError.INSUFFICIENT_TORSO_LEAN))
    }

    @Test
    fun tweakedStartAndDepthThresholdsAreApplied() {
        feed(170.0, 20.0)
        feed(155.0, 20.0) // below new 160 start threshold
        feed(105.0, 20.0) // reaches new 110 depth threshold
        val end = feed(160.0, 20.0)

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
    }

    @Test
    fun torsoViolation_remainsLatched() {
        feed(170.0, 10.0)
        feed(140.0, 50.0) // violation frame 1
        feed(90.0, 50.0)  // violation frame 2 -> trips
        feed(95.0, 10.0)  // torso good again, still down
        val end = feed(170.0, 10.0)

        assertTrue(end.isRepCompleted)
        // A later correct frame must not erase the earlier (tripped) violation.
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun multipleErrors_accumulateInOneRep() {
        feed(170.0, 10.0)
        feed(140.0, 50.0) // descending, torso violation frame 1
        feed(145.0, 50.0) // still descending (no depth), torso violation frame 2 -> trips
        val end = feed(170.0, 10.0) // returns without depth -> shallow

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INSUFFICIENT_DEPTH))
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun repeatedTorsoViolation_isNotDuplicated() {
        feed(170.0, 10.0)
        feed(140.0, 50.0) // v1
        feed(90.0, 50.0)  // v2 -> trips (depth reached)
        feed(85.0, 50.0)  // still violating
        val end = feed(170.0, 10.0)

        assertTrue(end.isRepCompleted)
        // Exactly one torso error despite many violating frames.
        assertEquals(1, end.errors.size)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun errorsResetBetweenReps() {
        // Rep 1: invalid (torso).
        feed(170.0, 10.0)
        feed(140.0, 50.0)
        feed(90.0, 50.0)
        val first = feed(170.0, 10.0)
        assertFalse(first.isRepCorrect)
        assertTrue(first.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))

        // Rep 2: clean.
        feed(140.0, 10.0)
        feed(90.0, 10.0)
        val second = feed(170.0, 10.0)
        assertTrue(second.isRepCorrect)
        assertTrue(second.errors.isEmpty())
    }

    @Test
    fun debugMetrics_captureMinMaxAndBottom() {
        feed(170.0, 10.0)  // standing (not yet in rep)
        feed(140.0, 25.0)  // descending
        feed(90.0, 40.0)   // deepest point: knee 90, torso 40
        feed(120.0, 15.0)  // still down
        val end = feed(170.0, 10.0) // completes

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
        engine.processFrame(90.0, 40.0, false) // no kneeOverToe passed (defaults to null)
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
        // Deepest frame: this reading must be the one captured.
        engine.processFrame(90.0, 10.0, false,
            KneeOverToeMetrics(legIsLeft = false, confidence = 0.75f, ankleAngle = 70.0, normalizedKneeToeOffset = 0.9))
        engine.processFrame(120.0, 10.0, false,
            KneeOverToeMetrics(legIsLeft = true, confidence = 0.95f, ankleAngle = 85.0, normalizedKneeToeOffset = 0.1))
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
        feed(140.0, 10.0)
        feed(90.0, 10.0)
        feed(170.0, 10.0)

        var extraCompletions = 0
        repeat(4) {
            if (feed(170.0, 10.0).isRepCompleted) extraCompletions++
        }
        assertEquals(0, extraCompletions)
    }
}
