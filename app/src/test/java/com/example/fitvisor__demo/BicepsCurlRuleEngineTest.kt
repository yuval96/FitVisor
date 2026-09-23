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
 * identical frames via [curl] / [reachTop] / [startLowering] / [lowerFully]
 * rather than a single reading.
 *
 * A repetition is only finalized once the user returns to a full extension
 * ([lowerFully]) after reaching the top -- reaching the top alone
 * ([reachTop]) never completes it. See [BicepsCurlRuleEngine]'s class doc.
 */
class BicepsCurlRuleEngineTest {

    private val engine = BicepsCurlRuleEngine()

    private fun feed(elbow: Double, torso: Double = 3.0, upperArm: Double = 10.0) =
        engine.processFrame(elbow, torso, upperArm)

    /** Two identical frames -> leaves the extended position (LOW -> CURLING). */
    private fun curl(torso: Double = 3.0, upperArm: Double = 10.0) {
        feed(120.0, torso, upperArm)
        feed(120.0, torso, upperArm)
    }

    /** Two identical frames -> reaches the top of the curl (CURLING -> UP). */
    private fun reachTop(torso: Double = 3.0, upperArm: Double = 10.0) =
        feed(50.0, torso, upperArm).let { feed(50.0, torso, upperArm) }

    /** Two identical frames -> elbow opens past the top (UP -> LOWERING). */
    private fun startLowering(torso: Double = 3.0, upperArm: Double = 10.0) =
        feed(90.0, torso, upperArm).let { feed(90.0, torso, upperArm) }

    /** Two identical frames -> extends back past LOW_ELBOW_MIN (completes the repetition). */
    private fun lowerFully(torso: Double = 3.0, upperArm: Double = 10.0) =
        feed(165.0, torso, upperArm).let { feed(165.0, torso, upperArm) }

    @Test
    fun reachingTop_aloneDoesNotCompleteRep() {
        feed(170.0)
        curl()
        val end = reachTop()

        assertFalse(end.isRepCompleted)
    }

    @Test
    fun validFullCycle_countsOneCorrect() {
        feed(170.0) // low
        curl()      // curling
        feed(90.0)  // still curling
        reachTop()  // top reached, not yet completed
        startLowering() // leaving the top
        val end = lowerFully() // fully extended again -> completes

        assertTrue(end.isRepCompleted)
        assertTrue(end.isRepCorrect)
    }

    @Test
    fun incompleteCurl_countsOneIncorrect() {
        feed(170.0)
        curl()                     // starts curling
        feed(100.0)                // not high enough
        val end = lowerFully()     // lowers again without reaching the top

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.INCOMPLETE_CURL))
    }

    @Test
    fun upperArmViolation_countsOneIncorrect() {
        feed(170.0)
        curl(upperArm = 40.0) // elbow swings out, both frames -> latches (2 consecutive)
        reachTop(upperArm = 10.0)
        startLowering(upperArm = 10.0)
        val end = lowerFully(upperArm = 10.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_UPPER_ARM_MOVEMENT))
    }

    @Test
    fun torsoViolation_countsOneIncorrect() {
        feed(170.0)
        curl(torso = 25.0) // torso swing, both frames -> latches
        reachTop(torso = 3.0)
        startLowering(torso = 3.0)
        val end = lowerFully(torso = 3.0)

        assertTrue(end.isRepCompleted)
        assertFalse(end.isRepCorrect)
        assertTrue(end.errors.contains(RepError.EXCESSIVE_TORSO_MOVEMENT))
    }

    @Test
    fun stayingAtTop_neverCompletes() {
        feed(170.0)
        curl()
        reachTop() // reached the top, still not completed

        var completions = 0
        repeat(4) {
            if (feed(50.0).isRepCompleted) completions++
        }
        assertEquals(0, completions)
    }

    @Test
    fun incompleteLowering_countsOneIncorrect_andDoesNotStallFollowingReps() {
        feed(170.0)
        curl()
        reachTop()          // top reached
        startLowering()     // leaving the top, elbow at 90

        // Never reaches full extension -- reverses and starts curling again
        // instead. The abandoned repetition must be closed out as incorrect
        // right here, not left stuck forever waiting for a LOW that never comes.
        val restart = feed(70.0).let { feed(70.0) }

        assertTrue(restart.isRepCompleted)
        assertFalse(restart.isRepCorrect)
        assertTrue(restart.errors.contains(RepError.INCOMPLETE_LOWERING))

        // The next repetition must still be trackable -- prove the state
        // machine picked back up cleanly from the restart above.
        feed(90.0) // still curling from the restart
        reachTop()
        startLowering()
        val nextRep = lowerFully()

        assertTrue(nextRep.isRepCompleted)
        assertTrue(nextRep.isRepCorrect)
    }
}
