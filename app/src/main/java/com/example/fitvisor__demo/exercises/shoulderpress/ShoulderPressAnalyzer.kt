package com.example.fitvisor__demo.exercises.shoulderpress

import android.util.Log
import com.example.fitvisor__demo.exercises.ExerciseAnalyzer
import com.example.fitvisor__demo.exercises.ExerciseFrameOutput
import com.example.fitvisor__demo.exercises.PoseQualityMeasurement
import com.example.fitvisor__demo.exercises.WARNING_BODY_NOT_VISIBLE
import com.example.fitvisor__demo.kinematics.KinematicCalculator
import com.example.fitvisor__demo.model.ExerciseAnalysisResult
import com.example.fitvisor__demo.model.OverlayMetrics
import com.example.fitvisor__demo.model.maxKeepNaN
import com.example.fitvisor__demo.model.minKeepNaN
import com.example.fitvisor__demo.pose.LandmarkConfidence
import com.example.fitvisor__demo.pose.PoseLandmarkIndices
import com.example.fitvisor__demo.pose.SideSelector
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark

/**
 * Front-view shoulder-press analyzer. Prefers both arms when both are reliable
 * (metrics are averaged for the state machine), otherwise falls back to the
 * single reliable arm. The rule engine consumes the averaged elbow angle
 * (shoulder-elbow-wrist), torso inclination, the normalized elbow->shoulder
 * vertical difference (for "elbows at shoulder height") and the torso length
 * as a body-relative scale for that height tolerance -- these drive the
 * START/TOP state machine only.
 *
 * The *per-side* elbow angle and shoulder->wrist vertical angle are also
 * forwarded to the engine (not averaged) so it can run the arm-verticality
 * and left/right symmetry technique checks independently of state detection.
 * Upper-arm-to-torso angle is still computed for the overlay/debug only and
 * is deliberately not a correctness criterion (see the engine's doc).
 *
 * When [debugEnabled] is on, this analyzer also emits
 * temporary Logcat debug (per-side angles, phase, failing rule and per-rep
 * min/max ranges) for on-device threshold tuning. See that flag's doc.
 */
class ShoulderPressAnalyzer(
    private val debugEnabled: () -> Boolean = { false },
    private val poseQualityEnabled: () -> Boolean = debugEnabled
) : ExerciseAnalyzer {

    private val engine = ShoulderPressRuleEngine()

    private var missingFrames = 0
    private var lastPhase: String? = null

    // Temporary debug: per-side min/max angle ranges accumulated since the last
    // completed/attempted rep. Only touched when debug tools are enabled.
    private val leftRange = SideRange()
    private val rightRange = SideRange()

    override fun analyze(
        landmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int,
        rawLandmarks: List<NormalizedLandmark>
    ): ExerciseFrameOutput {

        if (landmarks.size < LANDMARK_COUNT) {
            return notVisible()
        }

        val leftLandmarks = armLandmarks(landmarks, useLeft = true)
        val rightLandmarks = armLandmarks(landmarks, useLeft = false)
        val leftConfidence = LandmarkConfidence.minOfAll(leftLandmarks)
        val rightConfidence = LandmarkConfidence.minOfAll(rightLandmarks)

        val leftReliable = leftConfidence >= SideSelector.MIN_SIDE_CONFIDENCE
        val rightReliable = rightConfidence >= SideSelector.MIN_SIDE_CONFIDENCE

        if (!leftReliable && !rightReliable) {
            missingFrames++
            if (missingFrames > MAX_MISSING_FRAMES) {
                engine.reset()
                resetDebugRanges()
            }
            return notVisible()
        }
        missingFrames = 0

        val diagnosticsEnabled = debugEnabled()
        val poseQuality = if (poseQualityEnabled()) {
            val rawSource = rawLandmarks.takeIf { it.size >= LANDMARK_COUNT } ?: landmarks
            val rawLeftLandmarks = armLandmarks(rawSource, useLeft = true)
            val rawRightLandmarks = armLandmarks(rawSource, useLeft = false)
            val requiredLandmarks = when {
                leftReliable && rightReliable -> rawLeftLandmarks + rawRightLandmarks
                leftReliable -> rawLeftLandmarks
                else -> rawRightLandmarks
            }
            val selectionKey = when {
                leftReliable && rightReliable -> "BOTH"
                leftReliable -> "LEFT"
                else -> "RIGHT"
            }
            PoseQualityMeasurement.measure(
                selectionKey = "${javaClass.simpleName}:$selectionKey",
                requiredLandmarks = requiredLandmarks
            )
        } else {
            null
        }

        // Per-arm metrics for whichever arm(s) are reliable; kept separate for
        // debug, then averaged into the single values the rule engine consumes.
        val leftMetrics =
            if (leftReliable) armMetrics(leftLandmarks, imageWidth, imageHeight) else null
        val rightMetrics =
            if (rightReliable) armMetrics(rightLandmarks, imageWidth, imageHeight) else null

        val elbow = Averager()
        val upperArm = Averager()
        val torso = Averager()
        val elbowShoulderVertical = Averager()
        val bodyScale = Averager()

        for (m in listOfNotNull(leftMetrics, rightMetrics)) {
            elbow.add(m.elbow)
            upperArm.add(m.upperArm)
            torso.add(m.torso)
            elbowShoulderVertical.add(m.elbowShoulderVertical)
            bodyScale.add(m.bodyScale)
        }

        val elbowAngle = elbow.average()
        val upperArmAngle = upperArm.average()
        val torsoAngle = torso.average()
        val elbowShoulderVerticalValue = elbowShoulderVertical.average()
        val bodyScaleValue = bodyScale.average()

        val result = engine.processFrame(
            elbowAngle = elbowAngle,
            torsoVerticalAngle = torsoAngle,
            elbowShoulderVertical = elbowShoulderVerticalValue,
            bodyScale = bodyScaleValue,
            leftElbowAngle = leftMetrics?.elbow ?: Double.NaN,
            rightElbowAngle = rightMetrics?.elbow ?: Double.NaN,
            leftArmVertical = leftMetrics?.shoulderWristVertical ?: Double.NaN,
            rightArmVertical = rightMetrics?.shoulderWristVertical ?: Double.NaN
        )
        lastPhase = result.phaseName

        if (diagnosticsEnabled) {
            logDebug(leftMetrics, rightMetrics, elbowAngle, result)
        }

        val metrics = OverlayMetrics(
            values = linkedMapOf(
                "Elbow" to elbowAngle,
                "Arm" to upperArmAngle,
                "Torso" to torsoAngle
            ),
            warning = result.warning,
            phase = result.phaseName
        )
        return ExerciseFrameOutput(result, metrics, poseQuality?.withAngles(metrics.values))
    }

    private fun armLandmarks(
        landmarks: List<NormalizedLandmark>,
        useLeft: Boolean
    ): List<NormalizedLandmark> = listOf(
        landmarks[if (useLeft) PoseLandmarkIndices.L_SH else PoseLandmarkIndices.R_SH],
        landmarks[if (useLeft) PoseLandmarkIndices.L_ELBOW else PoseLandmarkIndices.R_ELBOW],
        landmarks[if (useLeft) PoseLandmarkIndices.L_WRIST else PoseLandmarkIndices.R_WRIST],
        landmarks[if (useLeft) PoseLandmarkIndices.L_HIP else PoseLandmarkIndices.R_HIP]
    )

    private fun armMetrics(
        requiredLandmarks: List<NormalizedLandmark>,
        imageWidth: Int,
        imageHeight: Int
    ): ArmMetrics {
        val sh = requiredLandmarks[0]
        val el = requiredLandmarks[1]
        val wr = requiredLandmarks[2]
        val hip = requiredLandmarks[3]

        return ArmMetrics(
            elbow = KinematicCalculator.calculateAngle(sh, el, wr, imageWidth, imageHeight),
            upperArm = KinematicCalculator.upperArmToTorsoAngle(sh, el, hip, imageWidth, imageHeight),
            shoulderWristVertical = KinematicCalculator.angleFromVertical(sh, wr, imageWidth, imageHeight),
            torso = KinematicCalculator.angleFromVertical(sh, hip, imageWidth, imageHeight),
            elbowShoulderVertical = KinematicCalculator.normalizedVerticalDistance(el, sh),
            // Body-relative scale: normalized torso length (shoulder->hip). Used
            // by the engine to size the "elbow at shoulder height" tolerance.
            bodyScale = kotlin.math.abs(sh.y() - hip.y()).toDouble()
        )
    }

    /** Logcat diagnostics controlled by the runtime debug switch. */
    private fun logDebug(
        leftMetrics: ArmMetrics?,
        rightMetrics: ArmMetrics?,
        avgElbowAngle: Double,
        result: ExerciseAnalysisResult
    ) {
        leftMetrics?.let { leftRange.add(it) }
        rightMetrics?.let { rightRange.add(it) }

        val asymmetry = if (leftMetrics != null && rightMetrics != null) {
            fmtDbg(kotlin.math.abs(leftMetrics.elbow - rightMetrics.elbow))
        } else "-"

        Log.d(
            DEBUG_TAG,
            "phase=${result.phaseName} " +
                "elbow L/R/avg=${fmtDbg(leftMetrics?.elbow)}/${fmtDbg(rightMetrics?.elbow)}/${fmtDbg(avgElbowAngle)} " +
                "armVert L/R=${fmtDbg(leftMetrics?.shoulderWristVertical)}/${fmtDbg(rightMetrics?.shoulderWristVertical)} " +
                "torso=${fmtDbg(leftMetrics?.torso ?: rightMetrics?.torso)} " +
                "asymmetry=$asymmetry " +
                "elbShldrVert L/R=${fmtDbg3(leftMetrics?.elbowShoulderVertical)}/${fmtDbg3(rightMetrics?.elbowShoulderVertical)} " +
                "activeErrors=${engine.activeErrors.map { it.name }} " +
                "fail=[${engine.lastFailingRule}]"
        )

        if (result.isRepCompleted) {
            val reason =
                if (result.isRepCorrect) "-"
                else result.errors.joinToString(",") { it.name }.ifEmpty { "unknown" }
            Log.d(
                DEBUG_TAG,
                "REP completed correct=${result.isRepCorrect} " +
                    "reason=[$reason] " +
                    "startElbShldrVert=${fmtDbg3(engine.lastRepStartElbowShoulder)} " +
                    "errors=${result.errors.map { it.name }} " +
                    "range left[$leftRange] right[$rightRange] " +
                    "aggregate=${result.debugMetrics?.values}"
            )
            resetDebugRanges()
        }
    }

    private fun resetDebugRanges() {
        leftRange.reset()
        rightRange.reset()
    }

    private fun notVisible(): ExerciseFrameOutput {
        val result = ExerciseAnalysisResult(
            isRepCompleted = false,
            isRepCorrect = false,
            warning = WARNING_BODY_NOT_VISIBLE,
            phaseName = lastPhase ?: "-"
        )
        return ExerciseFrameOutput(
            result,
            OverlayMetrics(emptyMap(), WARNING_BODY_NOT_VISIBLE, lastPhase)
        )
    }

    override fun reset() {
        missingFrames = 0
        lastPhase = null
        engine.reset()
        resetDebugRanges()
    }

    /** Per-side metric bundle for one analyzed frame. */
    private data class ArmMetrics(
        val elbow: Double,
        val upperArm: Double,
        val shoulderWristVertical: Double,
        val torso: Double,
        val elbowShoulderVertical: Double,
        val bodyScale: Double
    )

    /** Accumulates finite values only, ignoring NaN, and averages them. */
    private class Averager {
        private var sum = 0.0
        private var count = 0
        fun add(value: Double) {
            if (!value.isNaN()) {
                sum += value
                count++
            }
        }
        fun average(): Double = if (count == 0) Double.NaN else sum / count
    }

    /** Debug-only running [min, max] for one metric, NaN-tolerant. */
    private class Range {
        private var min = Double.NaN
        private var max = Double.NaN
        fun add(value: Double) {
            min = minKeepNaN(min, value)
            max = maxKeepNaN(max, value)
        }
        fun reset() {
            min = Double.NaN
            max = Double.NaN
        }
        override fun toString(): String = "[${fmtDbg(min)}, ${fmtDbg(max)}]"
    }

    /** Debug-only per-side min/max ranges for the metrics we care about. */
    private class SideRange {
        val elbow = Range()
        val upperArm = Range()
        val shoulderWristVertical = Range()
        val torso = Range()
        val elbowShoulderVertical = Range()
        fun add(m: ArmMetrics) {
            elbow.add(m.elbow)
            upperArm.add(m.upperArm)
            shoulderWristVertical.add(m.shoulderWristVertical)
            torso.add(m.torso)
            elbowShoulderVertical.add(m.elbowShoulderVertical)
        }
        fun reset() {
            elbow.reset()
            upperArm.reset()
            shoulderWristVertical.reset()
            torso.reset()
            elbowShoulderVertical.reset()
        }
        override fun toString(): String =
            "elbow=$elbow upperArm=$upperArm shWristVert=$shoulderWristVertical " +
                "torso=$torso elbShldrVert=$elbowShoulderVertical"
    }

    companion object {
        private const val LANDMARK_COUNT = 33
        private const val MAX_MISSING_FRAMES = 5
        private const val DEBUG_TAG = "ShoulderPressDebug"
    }
}

/** Formats a possibly-null/NaN Shoulder-Press debug value to one decimal, or "-". */
private fun fmtDbg(value: Double?): String =
    if (value == null || value.isNaN()) "-" else "%.1f".format(value)

/**
 * Formats a possibly-null/NaN normalized (0..1) Shoulder-Press debug value to
 * three decimals, or "-". Used for the small elbow->shoulder vertical distances.
 */
private fun fmtDbg3(value: Double?): String =
    if (value == null || value.isNaN()) "-" else "%.3f".format(value)
