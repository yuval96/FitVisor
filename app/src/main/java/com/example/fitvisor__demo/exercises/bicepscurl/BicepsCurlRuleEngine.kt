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
 * A repetition is counted when the user returns to a fully extended LOW
 * position ([LOW_ELBOW_MIN]) *after* having reached the top of the curl --
 * not when the top is reached. Reaching the top says nothing about whether
 * the user actually lowers back down with control, so that final return leg
 * has to be checked too.
 *
 * If the elbow reverses direction and drops back below [UP_EXIT_ELBOW] while
 * lowering -- i.e. the user starts curling again -- before ever reaching that
 * full extension, the in-progress repetition is closed out right there as
 * incorrect ([RepError.INCOMPLETE_LOWERING]) and the next repetition begins
 * immediately from that same frame. Without this, a user who never fully
 * extends between reps would leave the state machine stuck in LOWERING
 * forever -- unable to ever observe a fresh LOW -- so every subsequent
 * repetition, correct or not, would silently go uncounted.
 *
 * Torso and upper-arm-swing violations are latched for the duration of the
 * repetition.
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
 * Thresholds and behaviour are otherwise unchanged; this version additionally
 * reports the structured [RepError]s accumulated during the rep and per-rep
 * debug metrics.
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

    /**
     * Debounces a direction reversal *during* [State.LOWERING]: the elbow
     * dropping back below [UP_EXIT_ELBOW] -- the same boundary that admitted
     * LOWERING in the first place -- after having been extending. Reset the
     * instant LOWERING is entered (see [processFrame]), so it only ever
     * reflects motion within the current lowering attempt; a plain angle
     * threshold like [REP_START_ELBOW] would misfire here, since a real, if
     * slow, lowering can sit below it for several consecutive frames without
     * ever having reversed direction.
     */
    private val reCurlGate = ConsecutiveGate()

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
        var restartImmediately = false

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
        val stableReCurl = reCurlGate.update(!elbowAngle.isNaN() && elbowAngle < UP_EXIT_ELBOW)

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
                        // Reached the top; the repetition is only finalized
                        // once the user returns to full extension (see
                        // State.LOWERING below), not here.
                        reachedUp = true
                        topElbow = elbowAngle
                        currentState = State.UP
                    }

                    stableLow -> {
                        // Lowered again without curling high enough.
                        errors.add(RepError.INCOMPLETE_CURL)
                        isRepCompleted = true
                        isRepCorrect = false
                        resultWarning = WARNING_CURL_HIGHER
                    }
                }
            }

            State.UP -> {
                if (stableUpExit) {
                    currentState = State.LOWERING
                    // Reset so stableReCurl only reflects motion within this
                    // lowering attempt, not residual counting from the top.
                    reCurlGate.reset()
                }
            }

            State.LOWERING -> {
                when {
                    stableLow -> {
                        // Fully returned to the start position: finalize now.
                        isRepCompleted = true
                        isRepCorrect = techniqueValid
                        resultWarning =
                            if (!isRepCorrect) latchedWarning ?: WARNING_CURL_HIGHER else null
                    }

                    stableReCurl -> {
                        // The elbow reversed direction and is bending again
                        // before ever fully extending -- the lowering leg was
                        // abandoned. Close this repetition out as incorrect
                        // right here and begin the next one immediately from
                        // this same frame, so a user who never fully extends
                        // doesn't get stuck with every further rep going
                        // uncounted.
                        errors.add(RepError.INCOMPLETE_LOWERING)
                        isRepCompleted = true
                        isRepCorrect = false
                        resultWarning = WARNING_LOWER_FULLY
                        restartImmediately = true
                    }
                }
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
            if (restartImmediately) startRep() else finishRepToLow()
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
        reCurlGate.reset()
        // Torso and upper-arm are continuous posture measures; the gates are not
        // reset here so a violation spanning the low->curl transition latches
        // promptly. They are cleared when a repetition terminates.
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
        reCurlGate.reset()
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
        reCurlGate.reset()
        errors.clear()
        frameRecorder.reset()
        resetDebug()
    }
}
