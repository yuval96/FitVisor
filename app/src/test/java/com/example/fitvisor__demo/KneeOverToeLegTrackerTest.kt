package com.example.fitvisor__demo

import com.example.fitvisor__demo.exercises.squat.KneeOverToeLegTracker
import com.example.fitvisor__demo.pose.SideSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KneeOverToeLegTrackerTest {

    private val tracker = KneeOverToeLegTracker(minConfidence = 0.5f)

    @Test fun picksTheHigherConfidenceLegWhileStanding() {
        assertEquals(SideSelector.Side.RIGHT, tracker.update(leftConfidence = 0.6f, rightConfidence = 0.9f, isStanding = true))
        assertEquals(SideSelector.Side.LEFT, tracker.update(leftConfidence = 0.9f, rightConfidence = 0.6f, isStanding = true))
    }

    @Test fun belowThresholdLegIsNeverPicked() {
        // Right is "higher" but still below the confidence floor.
        assertEquals(SideSelector.Side.LEFT, tracker.update(leftConfidence = 0.55f, rightConfidence = 0.49f, isStanding = true))
    }

    @Test fun returnsNullWhenNeitherLegQualifies() {
        assertNull(tracker.update(leftConfidence = 0.2f, rightConfidence = 0.3f, isStanding = true))
    }

    @Test fun locksTheLegForTheWholeRepetitionOnceNotStanding() {
        // Right chosen while standing.
        tracker.update(leftConfidence = 0.3f, rightConfidence = 0.9f, isStanding = true)
        // Mid-rep: left briefly looks better, but the lock must not move.
        val duringRep = tracker.update(leftConfidence = 0.95f, rightConfidence = 0.6f, isStanding = false)
        assertEquals(SideSelector.Side.RIGHT, duringRep)
    }

    @Test fun switchesEarlyOnlyIfTheLockedLegBecomesUnusable() {
        tracker.update(leftConfidence = 0.3f, rightConfidence = 0.9f, isStanding = true) // locks RIGHT
        // Right drops below threshold mid-rep, left is fine: allowed to switch.
        val switched = tracker.update(leftConfidence = 0.8f, rightConfidence = 0.2f, isStanding = false)
        assertEquals(SideSelector.Side.LEFT, switched)
        // And it stays switched afterward even if right recovers.
        val staysSwitched = tracker.update(leftConfidence = 0.8f, rightConfidence = 0.9f, isStanding = false)
        assertEquals(SideSelector.Side.LEFT, staysSwitched)
    }

    @Test fun goesNullMidRepIfNeitherLegQualifiesAfterLockedLegFails() {
        tracker.update(leftConfidence = 0.3f, rightConfidence = 0.9f, isStanding = true) // locks RIGHT
        val result = tracker.update(leftConfidence = 0.1f, rightConfidence = 0.1f, isStanding = false)
        assertNull(result)
    }

    @Test fun unlocksAgainOnceStandingResumes() {
        tracker.update(leftConfidence = 0.3f, rightConfidence = 0.9f, isStanding = true) // locks RIGHT
        tracker.update(leftConfidence = 0.9f, rightConfidence = 0.9f, isStanding = false) // still locked RIGHT
        // Back to standing: free to re-pick, left now wins.
        val afterRep = tracker.update(leftConfidence = 0.95f, rightConfidence = 0.4f, isStanding = true)
        assertEquals(SideSelector.Side.LEFT, afterRep)
    }

    @Test fun resetClearsTheLock() {
        tracker.update(leftConfidence = 0.3f, rightConfidence = 0.9f, isStanding = true)
        tracker.reset()
        // Immediately mid-rep with no prior lock: falls back to best available.
        val result = tracker.update(leftConfidence = 0.8f, rightConfidence = 0.1f, isStanding = false)
        assertEquals(SideSelector.Side.LEFT, result)
    }
}
