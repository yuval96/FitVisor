package com.example.fitvisor__demo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic sequence tests for [BicepsCurlRuleEngine].
 * Low = elbow ~170 (extended), Up = elbow <= 60 (curled).
 * Good torso = 3 deg, upper arm pinned = 10 deg.
 */
class BicepsCurlRuleEngineTest {

    private val engine = BicepsCurlRuleEngine()

    private fun feed(elbow: Double, torso: Double = 3.0, upperArm: Double = 10.0) =
        engine.processFrame(elbow, torso, upperArm)

    @Test
    fun validLowToUp_countsOneCorrect() {
        feed(170.0) // low
        feed(130.0) // curling
        feed(90.0)
        val end = feed(50.0) // top reached

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
    }

    @Test
    fun incompleteCurl_countsOneIncorrect() {
        feed(170.0)
        feed(130.0)          // starts curling
        feed(100.0)          // not high enough
        val end = feed(165.0) // lowers again

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INCOMPLETE_CURL))
    }

    @Test
    fun upperArmViolation_countsOneIncorrect() {
        feed(170.0)
        feed(130.0, upperArm = 30.0) // elbow swings out (frame 1)
        feed(90.0, upperArm = 30.0)  // latches (2 consecutive)
        val end = feed(50.0, upperArm = 10.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_UPPER_ARM_MOVEMENT))
    }

    @Test
    fun torsoViolation_countsOneIncorrect() {
        feed(170.0)
        feed(130.0, torso = 15.0) // torso swing (frame 1)
        feed(90.0, torso = 15.0)  // latches
        val end = feed(50.0, torso = 3.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_MOVEMENT))
    }

    @Test
    fun stayingAtTop_doesNotDoubleCount() {
        feed(170.0)
        feed(130.0)
        feed(90.0)
        feed(50.0) // one completed rep

        var extraCompletions = 0
        repeat(4) {
            if (feed(50.0).isRepCompleted) extraCompletions++
        }
        assertEquals(0, extraCompletions)
    }
}
