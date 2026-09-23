package com.example.fitvisor__demo.exercises.pushup

import com.example.fitvisor__demo.model.ExerciseAnalysisResult
import com.example.fitvisor__demo.model.RepDebugMetrics
import com.example.fitvisor__demo.model.RepError
import com.example.fitvisor__demo.model.RepFrameRecorder
import com.example.fitvisor__demo.model.maxKeepNaN
import com.example.fitvisor__demo.model.minKeepNaN
import com.example.fitvisor__demo.utils.ConsecutiveGate
import kotlin.math.abs

/**
 * Side-view push-up analysis.
 *
 * Phase cycle: TOP -> DESCENDING -> BOTTOM -> ASCENDING -> TOP.
 * A repetition is counted when the user returns to a full lockout at the top
 * ([TOP_ELBOW_THRESHOLD]) *after* having reached the bottom -- reaching the
 * bottom alone never completes it. A cycle that never reaches the required
 * depth still completes once it gets back to the top, but as an incorrect
 * repetition. Body-linearity violations are latched for the whole repetition.
 *
 * If the elbow reverses direction and drops back below [BOTTOM_EXIT_THRESHOLD]
 * while ascending -- i.e. the user starts descending again -- before ever
 * locking out at the top, the in-progress repetition is closed out right there
 * as incorrect ([RepError.INSUFFICIENT_ELBOW_EXTENSION]) and the next
 * repetition begins immediately from that same frame. Without this, a user who
 * never locks out their elbows between reps (a deliberate technique choice for
 * some, to keep continuous muscle tension) would leave the state machine
 * cycling BOTTOM <-> ASCENDING forever -- unable to ever observe a fresh TOP --
 * so every repetition in the set, correct or not, would silently go uncounted.
 *
 * The five cycle-defining elbow-angle thresholds are each debounced
 * ([ConsecutiveGate], 2 consecutive frames): a single noisy reading right at
 * a boundary can otherwise arm a phantom repetition and immediately fail it,
 * or flip phases spuriously -- read together as a burst of unearned
 * "incorrect rep" events with no real movement behind them.
 *
 * Inputs per frame (all in degrees):
 *  - [elbowAngle]      wrist-elbow-shoulder.
 *  - [bodyLineAngle]   shoulder-hip-ankle (~180 when the body is straight).
 *  - [horizontalAngle] signed tilt of hip->ankle relative to horizontal.
 *
 * Thresholds are otherwise unchanged; this version additionally reports the
 * structured [RepError]s accumulated during the rep and per-rep debug metrics.
 */
class PushUpRuleEngine {

    companion object {
        // Elbow angle drops below this from the top -> the descent has
        // started. Deliberately below TOP_ELBOW_THRESHOLD (a 10 deg
        // hysteresis gap) -- with the same value for both, ordinary pose
        // noise while just holding the top position can dip below 150 for
        // two frames, arm a phantom repetition, then bounce back above 150
        // for two more and immediately complete it as a spurious
        // INSUFFICIENT_DEPTH rep right after a real one, without the user
        // having moved. See SquatRuleEngine's REP_START_THRESHOLD for the
        // same pattern.
        private const val REP_START_THRESHOLD = 140.0

        // Required bottom depth: elbow bent to about a right angle.
        private const val BOTTOM_ELBOW_THRESHOLD = 95.0

        // Elbow rising back above this while at the bottom -> ascending. Also
        // reused as the reversal boundary for an abandoned ascent -- see the
        // class doc and [descendAgainGate].
        private const val BOTTOM_EXIT_THRESHOLD = 110.0

        // Arms considered extended (top reached) at/above this elbow angle.
        private const val TOP_ELBOW_THRESHOLD = 150.0

        // Straight-body window for shoulder-hip-ankle. The three-point angle is
        // capped at 180, so in practice this means "at least 170".
        private const val BODY_LINE_MIN = 170.0
        private const val BODY_LINE_MAX = 190.0

        // Allowed |hip-to-ankle vs horizontal| at the bottom of the push-up.
        private const val BOTTOM_HORIZONTAL_LIMIT = 10.0

        private const val WARNING_BODY_STRAIGHT = "Keep your body straight"
        private const val WARNING_GO_LOWER = "Go lower"
        private const val WARNING_LOCK_OUT = "Extend your arms fully at the top"
    }

    enum class State { TOP, DESCENDING, BOTTOM, ASCENDING }

    private var currentState = State.TOP
    private var repInProgress = false
    private var reachedBottom = false
    private var techniqueValid = true
    private var latchedWarning: String? = null

    private val bodyLineGate = ConsecutiveGate()
    private val horizontalGate = ConsecutiveGate()

    /** Debounces the four cycle-defining elbow-angle thresholds; see class doc. */
    private val repStartGate = ConsecutiveGate()
    private val bottomGate = ConsecutiveGate()
    private val topGate = ConsecutiveGate()
    private val bottomExitGate = ConsecutiveGate()

    /**
     * Debounces a direction reversal *during* [State.ASCENDING]: the elbow
     * dropping back below [BOTTOM_EXIT_THRESHOLD] -- the same boundary that
     * admitted ASCENDING in the first place -- after having been extending.
     * Reset the instant ASCENDING is entered (see [processFrame]), so it only
     * ever reflects motion within the current ascent attempt; see
     * BicepsCurlRuleEngine's reCurlGate for the same pattern.
     */
    private val descendAgainGate = ConsecutiveGate()

    private val errors = linkedSetOf<RepError>()
    private val frameRecorder = RepFrameRecorder()

    // Per-rep debug accumulators.
    private var minElbow = Double.NaN
    private var minBodyLine = Double.NaN
    private var maxBodyLine = Double.NaN
    private var maxAbsHorizontal = Double.NaN
    private var bottomElbow = Double.NaN
    private var bottomHorizontal = Double.NaN

    fun processFrame(
        elbowAngle: Double,
        bodyLineAngle: Double,
        horizontalAngle: Double
    ): ExerciseAnalysisResult {

        var isRepCompleted = false
        var isRepCorrect = false
        var resultWarning: String? = null
        var restartImmediately = false

        val bodyLineIssue =
            bodyLineAngle < BODY_LINE_MIN || bodyLineAngle > BODY_LINE_MAX

        val stableRepStart = repStartGate.update(!elbowAngle.isNaN() && elbowAngle < REP_START_THRESHOLD)
        val stableBottom = bottomGate.update(!elbowAngle.isNaN() && elbowAngle <= BOTTOM_ELBOW_THRESHOLD)
        val stableTop = topGate.update(!elbowAngle.isNaN() && elbowAngle >= TOP_ELBOW_THRESHOLD)
        val stableBottomExit = bottomExitGate.update(!elbowAngle.isNaN() && elbowAngle >= BOTTOM_EXIT_THRESHOLD)
        val stableDescendAgain = descendAgainGate.update(!elbowAngle.isNaN() && elbowAngle < BOTTOM_EXIT_THRESHOLD)

        // Rep start: leaving the top position.
        if (currentState == State.TOP && stableRepStart) {
            startRep()
        }

        // Latch technique violations for the duration of the repetition.
        if (repInProgress) {
            accumulateDebug(elbowAngle, bodyLineAngle, horizontalAngle)
            frameRecorder.record(currentState.name) {
                linkedMapOf(
                    "elbow" to elbowAngle,
                    "bodyLine" to bodyLineAngle,
                    "horizontal" to horizontalAngle
                )
            }

            if (bodyLineGate.update(bodyLineIssue)) {
                techniqueValid = false
                errors.add(RepError.BODY_NOT_STRAIGHT)
                if (latchedWarning == null) latchedWarning = WARNING_BODY_STRAIGHT
            }

            val horizontalIssue =
                currentState == State.BOTTOM &&
                    !horizontalAngle.isNaN() &&
                    abs(horizontalAngle) > BOTTOM_HORIZONTAL_LIMIT
            if (horizontalGate.update(horizontalIssue)) {
                techniqueValid = false
                errors.add(RepError.INVALID_BOTTOM_ORIENTATION)
                if (latchedWarning == null) latchedWarning = WARNING_BODY_STRAIGHT
            }
        }

        when (currentState) {
            State.TOP -> Unit

            State.DESCENDING -> {
                if (stableBottom) {
                    reachedBottom = true
                    currentState = State.BOTTOM
                } else if (stableTop) {
                    // Returned to the top without ever reaching depth.
                    errors.add(RepError.INSUFFICIENT_DEPTH)
                    isRepCompleted = true
                    isRepCorrect = false
                    resultWarning = WARNING_GO_LOWER
                }
            }

            State.BOTTOM -> {
                if (stableBottomExit) {
                    currentState = State.ASCENDING
                    // Reset so stableDescendAgain only reflects motion within
                    // this ascent attempt, not residual counting from BOTTOM.
                    descendAgainGate.reset()
                }
            }

            State.ASCENDING -> {
                when {
                    stableTop -> {
                        isRepCompleted = true
                        isRepCorrect = reachedBottom && techniqueValid
                        resultWarning =
                            if (!isRepCorrect) latchedWarning ?: WARNING_GO_LOWER else null
                    }

                    stableDescendAgain -> {
                        // The elbow reversed direction and is bending again
                        // before ever locking out at the top -- the ascent
                        // was abandoned. Close this repetition out as
                        // incorrect right here and begin the next one
                        // immediately from this same frame, so a user who
                        // never locks out doesn't get stuck with every
                        // further rep going uncounted.
                        errors.add(RepError.INSUFFICIENT_ELBOW_EXTENSION)
                        isRepCompleted = true
                        isRepCorrect = false
                        resultWarning = WARNING_LOCK_OUT
                        restartImmediately = true
                    }
                }
            }
        }

        if (!isRepCompleted) {
            resultWarning = when {
                !repInProgress -> null
                latchedWarning != null -> latchedWarning
                !reachedBottom -> WARNING_GO_LOWER
                else -> null
            }
        }

        val completedErrors: Set<RepError>
        val completedMetrics: RepDebugMetrics?
        if (isRepCompleted) {
            completedErrors = errors.toSet()
            completedMetrics = buildDebugMetrics()
            if (restartImmediately) startRep() else finishRep()
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
        bodyLineAngle: Double,
        horizontalAngle: Double
    ) {
        minElbow = minKeepNaN(minElbow, elbowAngle)
        minBodyLine = minKeepNaN(minBodyLine, bodyLineAngle)
        maxBodyLine = maxKeepNaN(maxBodyLine, bodyLineAngle)
        if (!horizontalAngle.isNaN()) {
            maxAbsHorizontal = maxKeepNaN(maxAbsHorizontal, abs(horizontalAngle))
        }
        // Detected bottom = smallest elbow angle; capture horizontal there too.
        if (!elbowAngle.isNaN() && (bottomElbow.isNaN() || elbowAngle < bottomElbow)) {
            bottomElbow = elbowAngle
            bottomHorizontal = horizontalAngle
        }
    }

    private fun buildDebugMetrics(): RepDebugMetrics = RepDebugMetrics(
        values = linkedMapOf(
            "minElbowAngle" to minElbow,
            "minBodyLineAngle" to minBodyLine,
            "maxBodyLineAngle" to maxBodyLine,
            "maxAbsHorizontal" to maxAbsHorizontal,
            "bottomElbowAngle" to bottomElbow,
            "bottomHorizontalAngle" to bottomHorizontal
        ),
        frameTrace = frameRecorder.snapshot()
    )

    private fun startRep() {
        currentState = State.DESCENDING
        repInProgress = true
        reachedBottom = false
        techniqueValid = true
        latchedWarning = null
        bodyLineGate.reset()
        horizontalGate.reset()
        bottomGate.reset()
        topGate.reset()
        bottomExitGate.reset()
        descendAgainGate.reset()
        errors.clear()
        frameRecorder.reset()
        resetDebug()
    }

    private fun finishRep() {
        currentState = State.TOP
        repInProgress = false
        reachedBottom = false
        techniqueValid = true
        latchedWarning = null
        bodyLineGate.reset()
        horizontalGate.reset()
        repStartGate.reset()
        bottomGate.reset()
        topGate.reset()
        bottomExitGate.reset()
        descendAgainGate.reset()
    }

    private fun resetDebug() {
        minElbow = Double.NaN
        minBodyLine = Double.NaN
        maxBodyLine = Double.NaN
        maxAbsHorizontal = Double.NaN
        bottomElbow = Double.NaN
        bottomHorizontal = Double.NaN
    }

    fun reset() {
        finishRep()
        errors.clear()
        frameRecorder.reset()
        resetDebug()
    }
}
