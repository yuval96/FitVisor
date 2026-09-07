package com.example.fitvisor__demo

import org.junit.Assert.assertTrue
import org.junit.Test

class LatencyTrackerTest {
    @Test fun stagesUseMatchedFrameAndMonotonicDurations() {
        val tracker = LatencyTracker()
        tracker.submit(42, 1_000_000, 3_000_000)
        tracker.complete(42, 13_000_000, 16_000_000)
        val report = tracker.report()
        assertTrue(report.contains("Image preparation: avg 2.0"))
        assertTrue(report.contains("Model callback: avg 10.0"))
        assertTrue(report.contains("UI queue + analysis: avg 3.0"))
        assertTrue(report.contains("Total to UI update: avg 15.0"))
    }

    @Test fun droppedAndDuplicateResultsAreNotCounted() {
        val tracker = LatencyTracker()
        tracker.submit(1, 0, 0)
        tracker.submit(2, 0, 0)
        tracker.complete(2, 1_000_000, 2_000_000)
        tracker.complete(1, 1_000_000, 2_000_000)
        tracker.complete(2, 1_000_000, 2_000_000)
        assertTrue(tracker.report().startsWith("1 completed frames"))
    }

    @Test fun rollingWindowEvictsOldSamplesAndComputesP95() {
        val tracker = LatencyTracker(capacity = 2)
        for (id in 1L..3L) {
            tracker.submit(id, 0, 0)
            tracker.complete(id, id * 1_000_000, id * 1_000_000)
        }
        assertTrue(tracker.report().contains("Total to UI update: avg 2.5 · P95 3.0 · max 3.0"))
    }

    @Test fun pauseDiscardsInFlightFrames() {
        val tracker = LatencyTracker()
        tracker.submit(1, 0, 0)
        tracker.clearPending()
        tracker.complete(1, 1_000_000, 2_000_000)
        assertTrue(tracker.report().startsWith("No completed"))
    }
}
