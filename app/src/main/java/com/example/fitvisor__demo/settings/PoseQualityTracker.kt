package com.example.fitvisor__demo.settings

import com.example.fitvisor__demo.exercises.PoseQualityFrameSample
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

data class PoseQualityMetricsSnapshot(
    val currentVisibility: Double?,
    val averageVisibility: Double?,
    val minimumVisibility: Double?,
    val belowThresholdFramesPercent: Double?,
    val meanLandmarkJitter: Double?,
    val angleStandardDeviation: Double?,
    val sampleCount: Long
)

/**
 * Constant-memory accumulator for the Debug Mode pose-quality window.
 * A change in the analyzer's landmark/side selection begins a new compatible
 * window automatically. Jitter keeps only the previous raw x/y positions;
 * each analyzer angle keeps only count, sum, and sum of squares.
 */
class PoseQualityTracker {
    private var selectionKey: String? = null
    private var currentVisibility: Double? = null
    private var visibilitySum = 0.0
    private var minimumVisibility = Double.POSITIVE_INFINITY
    private var belowThresholdFrameCount = 0L
    private var sampleCount = 0L
    private var previousRawPositions: FloatArray? = null
    private var jitterSum = 0.0
    private var jitterSampleCount = 0L
    private val angleStatistics = linkedMapOf<String, RunningStatistics>()

    @Synchronized
    fun add(sample: PoseQualityFrameSample) {
        if (selectionKey != null && selectionKey != sample.selectionKey) {
            resetAccumulators()
        }
        selectionKey = sample.selectionKey
        currentVisibility = sample.currentVisibility
        visibilitySum += sample.currentVisibility
        minimumVisibility = min(minimumVisibility, sample.minimumVisibility)
        if (sample.isBelowThreshold) belowThresholdFrameCount++
        sampleCount++

        if (!sample.coordinatesReliable || sample.landmarkCount <= 0 ||
            sample.rawPositions.size != sample.landmarkCount * COORDINATES_PER_LANDMARK ||
            sample.rawPositions.any { !it.isFinite() }
        ) {
            // Do not turn an unreliable gap into a multi-frame displacement.
            previousRawPositions = null
            return
        }

        previousRawPositions?.takeIf { it.size == sample.rawPositions.size }?.let { previous ->
            var displacementSum = 0.0
            var index = 0
            while (index < sample.rawPositions.size) {
                val dx = (sample.rawPositions[index] - previous[index]).toDouble()
                val dy = (sample.rawPositions[index + 1] - previous[index + 1]).toDouble()
                displacementSum += sqrt(dx * dx + dy * dy)
                index += COORDINATES_PER_LANDMARK
            }
            jitterSum += displacementSum / sample.landmarkCount
            jitterSampleCount++
        }
        previousRawPositions = sample.rawPositions

        for ((name, angle) in sample.angles) {
            if (angle.isFinite()) {
                angleStatistics.getOrPut(name, ::RunningStatistics).add(angle)
            }
        }
    }

    @Synchronized
    fun snapshot(): PoseQualityMetricsSnapshot = PoseQualityMetricsSnapshot(
        currentVisibility = currentVisibility,
        averageVisibility = if (sampleCount == 0L) null else visibilitySum / sampleCount,
        minimumVisibility = if (sampleCount == 0L) null else minimumVisibility,
        belowThresholdFramesPercent = if (sampleCount == 0L) {
            null
        } else {
            belowThresholdFrameCount.toDouble() / sampleCount * 100.0
        },
        meanLandmarkJitter = if (jitterSampleCount == 0L) {
            null
        } else {
            jitterSum / jitterSampleCount
        },
        angleStandardDeviation = angleStatistics.values
            .mapNotNull(RunningStatistics::standardDeviation)
            .takeIf { it.isNotEmpty() }
            ?.average(),
        sampleCount = sampleCount
    )

    @Synchronized
    fun reset() {
        resetAccumulators()
    }

    private fun resetAccumulators() {
        selectionKey = null
        currentVisibility = null
        visibilitySum = 0.0
        minimumVisibility = Double.POSITIVE_INFINITY
        belowThresholdFrameCount = 0L
        sampleCount = 0L
        previousRawPositions = null
        jitterSum = 0.0
        jitterSampleCount = 0L
        angleStatistics.clear()
    }

    /** Population SD accumulator; two samples are required before reporting. */
    private class RunningStatistics {
        private var count = 0L
        private var sum = 0.0
        private var sumSquares = 0.0

        fun add(value: Double) {
            count++
            sum += value
            sumSquares += value * value
        }

        fun standardDeviation(): Double? {
            if (count < 2L) return null
            val mean = sum / count
            val variance = sumSquares / count - mean * mean
            return sqrt(max(variance, 0.0))
        }
    }

    private companion object {
        const val COORDINATES_PER_LANDMARK = 2
    }
}
