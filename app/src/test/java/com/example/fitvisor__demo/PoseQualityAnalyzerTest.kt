package com.example.fitvisor__demo

import com.example.fitvisor__demo.exercises.bicepscurl.BicepsCurlAnalyzer
import com.example.fitvisor__demo.exercises.pushup.PushUpAnalyzer
import com.example.fitvisor__demo.exercises.shoulderpress.ShoulderPressAnalyzer
import com.example.fitvisor__demo.exercises.squat.SquatAnalyzer
import com.example.fitvisor__demo.pose.PoseLandmarkIndices
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import java.util.Optional
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PoseQualityAnalyzerTest {
    @Test
    fun noPoseResultDoesNotCreateAPoseQualitySample() {
        val output = SquatAnalyzer { true }.analyze(emptyList(), 640, 480)

        assertNull(output.poseQuality)
    }

    @Test
    fun squatUsesOnlyTheSideSelectedByItsAnalyzer() {
        val values = mutableMapOf<Int, Float>()
        set(values, LEFT_SQUAT, 0.1f)
        set(values, RIGHT_SQUAT, 0.8f)
        val output = SquatAnalyzer { true }.analyze(landmarks(values), 640, 480)

        val sample = requireNotNull(output.poseQuality)
        assertEquals("SquatAnalyzer:RIGHT", sample.selectionKey)
        assertEquals(4, sample.landmarkCount)
        assertEquals(0.8, sample.currentVisibility, 0.0001)
        assertEquals(0.8, sample.minimumVisibility, 0.0001)
        assertEquals(setOf("Knee", "Torso"), sample.angles.keys)
    }

    @Test
    fun squatCanSelectLeftWithoutOppositeSideAffectingVisibility() {
        val values = mutableMapOf<Int, Float>()
        set(values, LEFT_SQUAT, 0.7f)
        set(values, RIGHT_SQUAT, 0.05f)
        val output = SquatAnalyzer { true }.analyze(landmarks(values), 640, 480)

        val sample = requireNotNull(output.poseQuality)
        assertEquals("SquatAnalyzer:LEFT", sample.selectionKey)
        assertEquals(0.7, sample.currentVisibility, 0.0001)
    }

    @Test
    fun belowThresholdIsOneFrameFlagEvenWhenAnalysisIsGated() {
        val values = mutableMapOf<Int, Float>()
        set(values, LEFT_SQUAT, 0.1f)
        set(values, RIGHT_SQUAT, 0.8f)
        values[PoseLandmarkIndices.R_ANKLE] = 0.4f
        val output = SquatAnalyzer { true }.analyze(landmarks(values), 640, 480)

        val sample = requireNotNull(output.poseQuality)
        assertEquals("SquatAnalyzer:RIGHT", sample.selectionKey)
        assertEquals(0.7, sample.currentVisibility, 0.0001)
        assertEquals(0.4, sample.minimumVisibility, 0.0001)
        assertTrue(sample.isBelowThreshold)
        assertTrue(sample.angles.isEmpty())
    }

    @Test
    fun pushUpAndBicepsUseTheirExactRequiredSelectedSideSets() {
        val pushValues = mutableMapOf<Int, Float>()
        set(pushValues, LEFT_PUSH_UP, 0.1f)
        set(pushValues, RIGHT_PUSH_UP, 0.75f)
        val pushSample = requireNotNull(
            PushUpAnalyzer { true }.analyze(landmarks(pushValues), 640, 480).poseQuality
        )
        assertEquals("PushUpAnalyzer:RIGHT", pushSample.selectionKey)
        assertEquals(5, pushSample.landmarkCount)
        assertEquals(0.75, pushSample.currentVisibility, 0.0001)
        assertEquals(setOf("Elbow", "Body line", "Horizontal"), pushSample.angles.keys)

        val curlValues = mutableMapOf<Int, Float>()
        set(curlValues, LEFT_BICEPS, 0.85f)
        set(curlValues, RIGHT_BICEPS, 0.1f)
        val curlSample = requireNotNull(
            BicepsCurlAnalyzer { true }.analyze(landmarks(curlValues), 640, 480).poseQuality
        )
        assertEquals("BicepsCurlAnalyzer:LEFT", curlSample.selectionKey)
        assertEquals(4, curlSample.landmarkCount)
        assertEquals(0.85, curlSample.currentVisibility, 0.0001)
        assertEquals(setOf("Elbow", "Upper arm", "Torso"), curlSample.angles.keys)
    }

    @Test
    fun shoulderPressMeasuresBothReliableArmsOrItsSingleReliableFallback() {
        val bothValues = mutableMapOf<Int, Float>()
        set(bothValues, LEFT_BICEPS, 0.8f)
        set(bothValues, RIGHT_BICEPS, 0.6f)
        val both = requireNotNull(
            ShoulderPressAnalyzer(
                debugEnabled = { false },
                poseQualityEnabled = { true }
            ).analyze(landmarks(bothValues), 640, 480).poseQuality
        )
        assertEquals("ShoulderPressAnalyzer:BOTH", both.selectionKey)
        assertEquals(8, both.landmarkCount)
        assertEquals(0.7, both.currentVisibility, 0.0001)
        assertEquals(setOf("Elbow", "Arm", "Torso"), both.angles.keys)

        val rightOnlyValues = mutableMapOf<Int, Float>()
        set(rightOnlyValues, LEFT_BICEPS, 0.3f)
        set(rightOnlyValues, RIGHT_BICEPS, 0.9f)
        val rightOnly = requireNotNull(
            ShoulderPressAnalyzer(
                debugEnabled = { false },
                poseQualityEnabled = { true }
            ).analyze(landmarks(rightOnlyValues), 640, 480).poseQuality
        )
        assertEquals("ShoulderPressAnalyzer:RIGHT", rightOnly.selectionKey)
        assertEquals(4, rightOnly.landmarkCount)
        assertEquals(0.9, rightOnly.currentVisibility, 0.0001)
    }

    @Test
    fun poseQualityCoordinatesComeFromRawLandmarksWhileAnglesUseAnalyzerInput() {
        val values = mutableMapOf<Int, Float>()
        set(values, LEFT_SQUAT, 0.1f)
        set(values, RIGHT_SQUAT, 0.8f)
        val smoothed = landmarks(values)
        val raw = landmarks(values, coordinateOffset = 0.2f)

        val output = SquatAnalyzer { true }.analyze(smoothed, 640, 480, raw)
        val sample = requireNotNull(output.poseQuality)
        val expectedPositions = RIGHT_SQUAT.flatMap { index ->
            listOf(raw[index].x(), raw[index].y())
        }.toFloatArray()

        assertArrayEquals(expectedPositions, sample.rawPositions, 0f)
        assertEquals(output.metrics.values, sample.angles)
    }

    private fun landmarks(
        visibilities: Map<Int, Float>,
        coordinateOffset: Float = 0f
    ): List<NormalizedLandmark> =
        List(33) { index ->
            val visibility = visibilities[index] ?: 0.01f
            NormalizedLandmark.create(
                (index % 6) / 6f + coordinateOffset,
                (index / 6) / 6f + coordinateOffset,
                0f,
                Optional.of(visibility),
                Optional.of(1f)
            )
        }

    private fun set(target: MutableMap<Int, Float>, indices: IntArray, value: Float) {
        for (index in indices) target[index] = value
    }

    private companion object {
        val LEFT_SQUAT = intArrayOf(
            PoseLandmarkIndices.L_SH,
            PoseLandmarkIndices.L_HIP,
            PoseLandmarkIndices.L_KNEE,
            PoseLandmarkIndices.L_ANKLE
        )
        val RIGHT_SQUAT = intArrayOf(
            PoseLandmarkIndices.R_SH,
            PoseLandmarkIndices.R_HIP,
            PoseLandmarkIndices.R_KNEE,
            PoseLandmarkIndices.R_ANKLE
        )
        val LEFT_PUSH_UP = intArrayOf(
            PoseLandmarkIndices.L_SH,
            PoseLandmarkIndices.L_ELBOW,
            PoseLandmarkIndices.L_WRIST,
            PoseLandmarkIndices.L_HIP,
            PoseLandmarkIndices.L_ANKLE
        )
        val RIGHT_PUSH_UP = intArrayOf(
            PoseLandmarkIndices.R_SH,
            PoseLandmarkIndices.R_ELBOW,
            PoseLandmarkIndices.R_WRIST,
            PoseLandmarkIndices.R_HIP,
            PoseLandmarkIndices.R_ANKLE
        )
        val LEFT_BICEPS = intArrayOf(
            PoseLandmarkIndices.L_SH,
            PoseLandmarkIndices.L_ELBOW,
            PoseLandmarkIndices.L_WRIST,
            PoseLandmarkIndices.L_HIP
        )
        val RIGHT_BICEPS = intArrayOf(
            PoseLandmarkIndices.R_SH,
            PoseLandmarkIndices.R_ELBOW,
            PoseLandmarkIndices.R_WRIST,
            PoseLandmarkIndices.R_HIP
        )
    }
}
