package com.example.fitvisor__demo

import com.example.fitvisor__demo.exercises.PoseQualityFrameSample
import com.example.fitvisor__demo.settings.PoseQualityTracker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PoseQualityTrackerTest {
    @Test
    fun accumulatesFrameAverageMinimumAndBelowThresholdPercentage() {
        val tracker = PoseQualityTracker()
        tracker.add(sample("RIGHT", current = 0.8, minimum = 0.5, below = false))
        tracker.add(sample("RIGHT", current = 0.6, minimum = 0.2, below = true))

        val snapshot = tracker.snapshot()

        assertEquals(0.6, snapshot.currentVisibility!!, 0.0001)
        assertEquals(0.7, snapshot.averageVisibility!!, 0.0001)
        assertEquals(0.2, snapshot.minimumVisibility!!, 0.0001)
        assertEquals(50.0, snapshot.belowThresholdFramesPercent!!, 0.0001)
        assertEquals(2L, snapshot.sampleCount)
    }

    @Test
    fun calculatesMeanRawNormalizedLandmarkDisplacement() {
        val tracker = PoseQualityTracker()
        tracker.add(sample("RIGHT", positions = floatArrayOf(0f, 0f, 0.5f, 0.5f)))
        tracker.add(sample("RIGHT", positions = floatArrayOf(0.003f, 0.004f, 0.506f, 0.508f)))

        // Landmark displacements are 0.005 and 0.010, whose frame mean is 0.0075.
        assertEquals(0.0075, tracker.snapshot().meanLandmarkJitter!!, 0.000001)
    }

    @Test
    fun averagesPopulationStandardDeviationOfEachAnalyzerAngle() {
        val tracker = PoseQualityTracker()
        tracker.add(sample("RIGHT", angles = mapOf("Knee" to 10.0, "Torso" to 20.0)))
        tracker.add(sample("RIGHT", angles = mapOf("Knee" to 14.0, "Torso" to 26.0)))

        // Per-angle population SDs are 2 and 3 degrees; the displayed mean is 2.5.
        assertEquals(2.5, tracker.snapshot().angleStandardDeviation!!, 0.0001)
    }

    @Test
    fun unreliableFrameIsExcludedAndBreaksTheJitterSequence() {
        val tracker = PoseQualityTracker()
        tracker.add(sample("RIGHT", positions = floatArrayOf(0f, 0f),
            angles = mapOf("Knee" to 10.0)))
        tracker.add(sample("RIGHT", positions = floatArrayOf(0.5f, 0.5f),
            angles = mapOf("Knee" to 100.0), reliable = false, below = true))
        tracker.add(sample("RIGHT", positions = floatArrayOf(0.01f, 0.01f),
            angles = mapOf("Knee" to 14.0)))

        val snapshot = tracker.snapshot()
        assertNull(snapshot.meanLandmarkJitter)
        assertEquals(2.0, snapshot.angleStandardDeviation!!, 0.0001)
        assertEquals(3L, snapshot.sampleCount)
    }

    @Test
    fun oneFrameDoesNotReportFakeJitterOrAngleDeviation() {
        val tracker = PoseQualityTracker()
        tracker.add(sample("RIGHT", angles = mapOf("Knee" to 90.0)))

        assertNull(tracker.snapshot().meanLandmarkJitter)
        assertNull(tracker.snapshot().angleStandardDeviation)
    }

    @Test
    fun sideChangeStartsANewCompatibleWindow() {
        val tracker = PoseQualityTracker()
        tracker.add(sample("SquatAnalyzer:RIGHT", current = 0.9, minimum = 0.8,
            positions = floatArrayOf(0f, 0f), angles = mapOf("Knee" to 10.0)))
        tracker.add(sample("SquatAnalyzer:RIGHT", current = 0.8, minimum = 0.7,
            positions = floatArrayOf(0.1f, 0.1f), angles = mapOf("Knee" to 14.0)))
        tracker.add(sample("SquatAnalyzer:LEFT", current = 0.4, minimum = 0.3, below = true,
            positions = floatArrayOf(0.2f, 0.2f), angles = mapOf("Knee" to 100.0)))

        val snapshot = tracker.snapshot()

        assertEquals(1L, snapshot.sampleCount)
        assertEquals(0.4, snapshot.currentVisibility!!, 0.0001)
        assertEquals(0.4, snapshot.averageVisibility!!, 0.0001)
        assertEquals(0.3, snapshot.minimumVisibility!!, 0.0001)
        assertEquals(100.0, snapshot.belowThresholdFramesPercent!!, 0.0001)
        assertNull(snapshot.meanLandmarkJitter)
        assertNull(snapshot.angleStandardDeviation)
    }

    @Test
    fun resetClearsOnlyThePoseQualityAccumulator() {
        val tracker = PoseQualityTracker()
        tracker.add(sample("BOTH", current = 0.9, minimum = 0.8, below = false))

        tracker.reset()
        val snapshot = tracker.snapshot()

        assertNull(snapshot.currentVisibility)
        assertNull(snapshot.averageVisibility)
        assertNull(snapshot.minimumVisibility)
        assertNull(snapshot.belowThresholdFramesPercent)
        assertNull(snapshot.meanLandmarkJitter)
        assertNull(snapshot.angleStandardDeviation)
        assertEquals(0L, snapshot.sampleCount)
    }

    private fun sample(
        selection: String,
        current: Double = 0.9,
        minimum: Double = 0.8,
        below: Boolean = false,
        positions: FloatArray = floatArrayOf(0f, 0f),
        angles: Map<String, Double> = emptyMap(),
        reliable: Boolean = true
    ) = PoseQualityFrameSample(
        selectionKey = selection,
        currentVisibility = current,
        minimumVisibility = minimum,
        isBelowThreshold = below,
        landmarkCount = positions.size / 2,
        rawPositions = positions,
        coordinatesReliable = reliable,
        angles = angles
    )
}
