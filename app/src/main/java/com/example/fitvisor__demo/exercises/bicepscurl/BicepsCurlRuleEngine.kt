package com.example.fitvisor__demo.exercises.bicepscurl

import com.example.fitvisor__demo.model.ExerciseAnalysisResult
import com.example.fitvisor__demo.model.RepDebugMetrics
import com.example.fitvisor__demo.model.RepError
import com.example.fitvisor__demo.model.RepFrameRecorder
import com.example.fitvisor__demo.model.maxKeepNaN
import com.example.fitvisor__demo.model.minKeepNaN
import com.example.fitvisor__demo.utils.ConsecutiveGate

/**
 * Biceps-curl analysis (single working arm).
 *
 * Phase cycle: LOW -> CURLING -> UP -> LOWERING -> LOW.
 * One repetition is counted on the LOW -> UP transition (when the arm reaches
 * the top of the curl). After counting, the user must return to LOW before
 * another repetition can be counted. Torso and upper-arm-swing violations are
 * latched for the duration of the repetition.
 *
 * The four cycle-defining elbow-angle thresholds are each debounced
 * ([ConsecutiveGate], 2 consecutive frames): a single noisy reading right at
 * a boundary can otherwise arm a phantom repetition and immediately fail it,
 * or flip phases spuriously -- read together as a burst of unearned
 * "incorrect rep" events with no real movement behind them.
 *
 * Inputs per frame (all in degrees):
 *  - [elbowAngle]         shoulder-elbow-wrist.
 *  - [torsoVerticalAngle] torso deviation from vertical (0..90).
 *  - [upperArmTorsoAngle] upper arm vs torso (small when the elbow stays pinned).
 *
 * Thresholds and behaviour are unchanged; this version additionally reports the
 * structured [RepError]s accumulated during the rep and per-rep debug metrics.
 * Note: incomplete lowering is a live cue only and does not invalidate a rep
 * (the rep is counted on the way up), so it is not reported as a [RepError].
 */
class BicepsCurlRuleEngine {

    companion object {
        // Arm extended (bottom) window. A real curl often doesn't fully lock
        // the elbow out at the bottom (people keep a slight bend), so this is
        // set a few degrees short of full extension -- still a clear 5 deg
        // hysteresis gap above REP_START_ELBOW to avoid boundary flicker.
        private const val LOW_ELBOW_MIN = 130.0
        private const val LOW_ELBOW_MAX = 180.0

        // Top-of-curl: elbow flexed to at most this angle.
        private const val UP_ELBOW_MAX = 60.0

        // Elbow bends below this from the bottom -> curling has started.
        private const val REP_START_ELBOW = 125.0

        // Elbow opens past this from the top -> lowering.
        private const val UP_EXIT_ELBOW = 80.0

        // Torso must stay within this many degrees of vertical. Matches
        // Shoulder Press's relaxed torso limit -- 10 deg was too tight once
        // torso angle started coming from 3D world landmarks (noisier,
        // particularly on depth/Z, than the 2D projection it replaced; see
        // WorldLandmarkSmoother). A wide-range check like squat's 45 deg
        // excessive-lean limit barely notices that noise; this mostly-static
        // check did.
        private const val TORSO_VERTICAL_LIMIT = 15.0

        // Upper-arm-to-torso limit; the elbow should stay reasonably close to
        // the body. Loosened from 20 for the same reason as the torso limit
        // above, plus real curls naturally let the elbow drift a bit. Tune
        // further on-device.
        private const val UPPER_ARM_LIMIT = 30.0

        private const val WARNING_TORSO = "Keep your torso upright"
        private const val WARNING_UPPER_ARM = "Keep your upper arm close to your body"
        private const val WARNING_CURL_HIGHER = "Curl higher"
        private const val WARNING_LOWER_FULLY = "Lower your arm fully"
    }

    enum class State { LOW, CURLING, UP, LOWERING }

    private var currentState = State.LOW
    private var repInProgress = false
    private var reachedUp = false
    private var techniqueValid = true
    private var latchedWarning: String? = null

    private val torsoGate = ConsecutiveGate()
    private val upperArmGate = ConsecutiveGate()

    /** Debounces the four cycle-defining elbow-angle thresholds; see class doc. */
    private val repStartGate = ConsecutiveGate()
    private val upGate = ConsecutiveGate()
    private val lowGate = ConsecutiveGate()
    private val upExitGate = ConsecutiveGate()

    private val errors = linkedSetOf<RepError>()
    private val frameRecorder = RepFrameRecorder()

    // Per-rep debug accumulators.
    private var minElbow = Double.NaN
    private var maxElbow = Double.NaN
    private var maxTorso = Double.NaN
    private var maxUpperArm = Double.NaN
    private var topElbow = Double.NaN

    fun processFrame(
        elbowAngle: Double,
        torsoVerticalAngle: Double,
        upperArmTorsoAngle: Double
    ): ExerciseAnalysisResult {

        var isRepCompleted = false
        var isRepCorrect = false
        var resultWarning: String? = null
        var completedToLow = false

        val torsoIssue =
            !torsoVerticalAngle.isNaN() && torsoVerticalAngle > TORSO_VERTICAL_LIMIT
        val upperArmIssue =
            !upperArmTorsoAngle.isNaN() && upperArmTorsoAngle > UPPER_ARM_LIMIT

        val torsoTripped = torsoGate.update(torsoIssue)
        val upperArmTripped = upperArmGate.update(upperArmIssue)

        val stableRepStart = repStartGate.update(!elbowAngle.isNaN() && elbowAngle < REP_START_ELBOW)
        val stableUp = upGate.update(!elbowAngle.isNaN() && elbowAngle <= UP_ELBOW_MAX)
        val stableLow = lowGate.update(!elbowAngle.isNaN() && elbowAngle >= LOW_ELBOW_MIN)
        val stableUpExit = upExitGate.update(!elbowAngle.isNaN() && elbowAngle > UP_EXIT_ELBOW)

        // Rep start: leaving the extended position.
        if (currentState == State.LOW && stableRepStart) {
            startRep()
        }

        // Latch violations for the duration of the repetition.
        if (repInProgress) {
            accumulateDebug(elbowAngle, torsoVerticalAngle, upperArmTorsoAngle)
            frameRecorder.record(currentState.name) {
                linkedMapOf(
                    "elbow" to elbowAngle,
                    "torso" to torsoVerticalAngle,
                    "upperArm" to upperArmTorsoAngle
                )
            }

            if (torsoTripped) {
                techniqueValid = false
                errors.add(RepError.EXCESSIVE_TORSO_MOVEMENT)
                if (latchedWarning == null) latchedWarning = WARNING_TORSO
            }
            if (upperArmTripped) {
                techniqueValid = false
                errors.add(RepError.EXCESSIVE_UPPER_ARM_MOVEMENT)
                if (latchedWarning == null) latchedWarning = WARNING_UPPER_ARM
            }
        }

        when (currentState) {
            State.LOW -> Unit

            State.CURLING -> {
                when {
                    stableUp -> {
                        // Reached the top: count the LOW -> UP repetition.
                        reachedUp = true
                        topElbow = elbowAngle
                        isRepCompleted = true
                        isRepCorrect = techniqueValid
                        resultWarning =
                            if (!isRepCorrect) latchedWarning ?: WARNING_CURL_HIGHER else null
                    }

                    stableLow -> {
                        // Lowered again without curling high enough.
                        errors.add(RepError.INCOMPLETE_CURL)
                        isRepCompleted = true
                        isRepCorrect = false
                        resultWarning = WARNING_CURL_HIGHER
                        completedToLow = true
                    }
                }
            }

            State.UP -> {
                if (stableUpExit) currentState = State.LOWERING
            }

            State.LOWERING -> {
                if (stableLow) currentState = State.LOW
            }
        }

        if (!isRepCompleted) {
            resultWarning = when {
                latchedWarning != null -> latchedWarning
                torsoTripped -> WARNING_TORSO
                upperArmTripped -> WARNING_UPPER_ARM
                currentState == State.LOWERING -> WARNING_LOWER_FULLY
                else -> null
            }
        }

        val completedErrors: Set<RepError>
        val completedMetrics: RepDebugMetrics?
        if (isRepCompleted) {
            completedErrors = errors.toSet()
            completedMetrics = buildDebugMetrics()
            if (completedToLow) finishRepToLow() else reachTop()
        } else {
            completedErrors = emptySet()
            completedMetrics = null
        }

        return ExerciseAnalysisResult(
            isRepCompleted = isRepCompleted,
            isRepCorrect = isRepCorrect,
            warning = resultWarning,
            phaseName = currentState.name,
            errors = completedErrors,
            debugMetrics = completedMetrics
        )
    }

    private fun accumulateDebug(
        elbowAngle: Double,
        torsoVerticalAngle: Double,
        upperArmTorsoAngle: Double
    ) {
        minElbow = minKeepNaN(minElbow, elbowAngle)
        maxElbow = maxKeepNaN(maxElbow, elbowAngle)
        maxTorso = maxKeepNaN(maxTorso, torsoVerticalAngle)
        maxUpperArm = maxKeepNaN(maxUpperArm, upperArmTorsoAngle)
    }

    private fun buildDebugMetrics(): RepDebugMetrics = RepDebugMetrics(
        values = linkedMapOf(
            "minElbowAngle" to minElbow,
            "maxElbowAngle" to maxElbow,
            "maxTorsoAngle" to maxTorso,
            "maxUpperArmAngle" to maxUpperArm,
            "topElbowAngle" to topElbow
        ),
        frameTrace = frameRecorder.snapshot()
    )

    private fun startRep() {
        currentState = State.CURLING
        repInProgress = true
        reachedUp = false
        techniqueValid = true
        latchedWarning = null
        errors.clear()
        frameRecorder.reset()
        resetDebug()
        upGate.reset()
        lowGate.reset()
        upExitGate.reset()
        // Torso and upper-arm are continuous posture measures; the gates are not
        // reset here so a violation spanning the low->curl transition latches
        // promptly. They are cleared when a repetition terminates.
    }

    /** Reached the top: stop latching but stay in UP until the user returns. */
    private fun reachTop() {
        currentState = State.UP
        repInProgress = false
        techniqueValid = true
        latchedWarning = null
        torsoGate.reset()
        upperArmGate.reset()
        repStartGate.reset()
        upGate.reset()
        lowGate.reset()
        upExitGate.reset()
    }

    private fun finishRepToLow() {
        currentState = State.LOW
        repInProgress = false
        reachedUp = false
        techniqueValid = true
        latchedWarning = null
        torsoGate.reset()
        upperArmGate.reset()
        repStartGate.reset()
        upGate.reset()
        lowGate.reset()
        upExitGate.reset()
    }

    private fun resetDebug() {
        minElbow = Double.NaN
        maxElbow = Double.NaN
        maxTorso = Double.NaN
        maxUpperArm = Double.NaN
        topElbow = Double.NaN
    }

    fun reset() {
        currentState = State.LOW
        repInProgress = false
        reachedUp = false
        techniqueValid = true
        latchedWarning = null
        torsoGate.reset()
        upperArmGate.reset()
        repStartGate.reset()
        upGate.reset()
        lowGate.reset()
        upExitGate.reset()
        errors.clear()
        frameRecorder.reset()
        resetDebug()
    }
}
