package com.example.fitvisor__demo

import com.example.fitvisor__demo.exercises.bicepscurl.BicepsCurlRuleEngine
import com.example.fitvisor__demo.model.RepError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Synthetic sequence tests for [BicepsCurlRuleEngine].
 * Low = elbow ~170 (extended), Up = elbow <= 60 (curled).
 * Good torso = 3 deg, upper arm pinned = 10 deg.
 *
 * The four cycle-defining elbow-angle thresholds are each debounced (2
 * consecutive frames), so every transition below is driven by a pair of
 * identical frames via [curl] / [reachTop] / [lowerBelowLow] rather than a
 * single reading.
 */
class BicepsCurlRuleEngineTest {

    private val engine = BicepsCurlRuleEngine()

    private fun feed(elbow: Double, torso: Double = 3.0, upperArm: Double = 10.0) =
        engine.processFrame(elbow, torso, upperArm)

    /** Two identical frames -> leaves the extended position (LOW -> CURLING). */
    private fun curl(torso: Double = 3.0, upperArm: Double = 10.0) {
        feed(130.0, torso, upperArm)
        feed(130.0, torso, upperArm)
    }

    /** Two identical frames -> reaches the top of the curl (counts the repetition). */
    private fun reachTop(torso: Double = 3.0, upperArm: Double = 10.0) =
        feed(50.0, torso, upperArm).let { feed(50.0, torso, upperArm) }

    /** Two identical frames -> extends back past LOW_ELBOW_MIN (fails an incomplete curl, or exits LOWERING). */
    private fun lowerBelowLow(torso: Double = 3.0, upperArm: Double = 10.0) =
        feed(165.0, torso, upperArm).let { feed(165.0, torso, upperArm) }

    @Test
    fun validLowToUp_countsOneCorrect() {
        feed(170.0) // low
        curl()      // curling
        feed(90.0)  // still curling
        val end = reachTop() // top reached

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
    }

    @Test
    fun incompleteCurl_countsOneIncorrect() {
        feed(170.0)
        curl()                     // starts curling
        feed(100.0)                // not high enough
        val end = lowerBelowLow()  // lowers again

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INCOMPLETE_CURL))
    }

    @Test
    fun upperArmViolation_countsOneIncorrect() {
        feed(170.0)
        curl(upperArm = 40.0) // elbow swings out, both frames -> latches (2 consecutive)
        val end = reachTop(upperArm = 10.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_UPPER_ARM_MOVEMENT))
    }

    @Test
    fun torsoViolation_countsOneIncorrect() {
        feed(170.0)
        curl(torso = 25.0) // torso swing, both frames -> latches
        val end = reachTop(torso = 3.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_MOVEMENT))
    }

    @Test
    fun stayingAtTop_doesNotDoubleCount() {
        feed(170.0)
        curl()
        reachTop() // one completed rep

        var extraCompletions = 0
        repeat(4) {
            if (feed(50.0).isRepCompleted) extraCompletions++
        }
        assertEquals(0, extraCompletions)
    }
}
