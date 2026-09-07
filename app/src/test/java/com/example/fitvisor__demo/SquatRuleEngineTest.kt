package com.example.fitvisor__demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic angle-sequence tests for [SquatRuleEngine].
 * Good torso = 10 deg (below the 45 deg limit); knee depth reached at <= 100.
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
