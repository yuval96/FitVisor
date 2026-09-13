package com.example.fitvisor__demo

import com.example.fitvisor__demo.settings.LatencyTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun performanceSnapshotSeparatesCameraFramesFromCompletedResults() {
        val tracker = LatencyTracker()
        for (index in 0..90) {
            tracker.cameraFrame(index * 33_333_333L, 1280, 720)
        }
        for (index in 0..72) {
            tracker.analysisResult(index * 41_666_667L)
        }

        val metrics = tracker.snapshot("Full", "GPU", true, 3_000_000_000L)

        assertEquals(30.0, metrics.cameraFps!!, 0.1)
        assertEquals(24.0, metrics.analysisFps!!, 0.1)
        assertEquals(1280, metrics.inputWidth)
        assertEquals(720, metrics.inputHeight)
    }

    @Test fun fpsUsesPlaceholderUntilTheWindowHasEnoughHistory() {
        val tracker = LatencyTracker()
        tracker.cameraFrame(0L, 640, 480)
        tracker.cameraFrame(1_000_000_000L, 640, 480)
        tracker.analysisResult(0L)
        tracker.analysisResult(1_000_000_000L)

        val metrics = tracker.snapshot("Lite", "CPU", false, 1_000_000_000L)

        assertNull(metrics.cameraFps)
        assertNull(metrics.analysisFps)
    }

    @Test fun currentAndAverageLatencyReuseMatchedLatencySamples() {
        val tracker = LatencyTracker()
        tracker.submit(1, 0, 0)
        tracker.complete(1, 10_000_000, 10_000_000)
        tracker.submit(2, 0, 0)
        tracker.complete(2, 30_000_000, 30_000_000)

        val metrics = tracker.snapshot("Heavy", "CPU", false, 30_000_000L)

        assertEquals(30.0, metrics.currentLatencyMs!!, 0.01)
        assertEquals(20.0, metrics.averageLatencyMs!!, 0.01)
    }
}
