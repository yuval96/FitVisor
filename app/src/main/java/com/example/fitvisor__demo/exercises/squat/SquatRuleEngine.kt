package com.example.fitvisor__demo.exercises.squat

import com.example.fitvisor__demo.model.ExerciseAnalysisResult
import com.example.fitvisor__demo.model.RepDebugMetrics
import com.example.fitvisor__demo.model.RepError
import com.example.fitvisor__demo.model.RepFrameRecorder
import com.example.fitvisor__demo.model.maxKeepNaN
import com.example.fitvisor__demo.model.minKeepNaN
import com.example.fitvisor__demo.utils.ConsecutiveGate

/**
 * Evaluates squat phases, repetition completion, and technique quality.
 *
 * Behaviour is preserved from the original implementation:
 *  - A repetition begins when the knee leaves the standing position.
 *  - Required depth is a knee angle at or below [SQUAT_DOWN_THRESHOLD].
 *  - Standing is a knee angle at or above [SQUAT_UP_THRESHOLD].
 *  - A technique violation during a rep is latched until the rep ends; a later
 *    correct frame never erases an earlier error.
 *
 * Excessive and insufficient torso lean are checked throughout both active
 * repetition phases: [State.DESCENDING] and [State.DOWN]. A violation in either
 * direction must persist for [ConsecutiveGate.requiredFrames] consecutive
 * frames before it invalidates the repetition, so one noisy frame does not fail
 * the rep. Standing frames do not participate in the minimum-lean check.
 */
class SquatRuleEngine {

    companion object {
        private const val REP_START_THRESHOLD = 160.0
        private const val SQUAT_DOWN_THRESHOLD = 110.0
        private const val SQUAT_UP_THRESHOLD = 160.0
        private const val MIN_TORSO_INCLINATION = 5.0
        private const val MAX_TORSO_INCLINATION = 45.0

        private const val KNEE_TOE_OFFSET_LIMIT = 0.2
    }

    enum class State {
        UP,
        DESCENDING,
        DOWN
    }

    private var currentState = State.UP
    private var repInProgress = false
    private var reachedRequiredDepth = false
    private var techniqueValid = true

    /**
     * Phase as of the start of the current frame (before it's processed) —
     * lets the analyzer peek at "are we still standing?" to drive
     * [KneeOverToeLegTracker] *before* calling [processFrame], since its
     * result (including any state transition) is only known afterward.
     */
    val phaseName: String get() = currentState.name

    /** Each torso boundary has an independent two-frame debounce. */
    private val excessiveTorsoLeanGate = ConsecutiveGate()
    private val insufficientTorsoLeanGate = ConsecutiveGate()

    /**
     * Knee-past-toe must persist for 2 consecutive frames before it invalidates,
     * using the same debounce duration as the torso checks. Named distinctly
     * from the [processFrame] `kneeOverToe` parameter so the two are never
     * confused.
     */
    private val kneeOverToeGate = ConsecutiveGate()

    private val errors = linkedSetOf<RepError>()
    private val frameRecorder = RepFrameRecorder()

    // Per-rep debug accumulators.
    private var minKnee = Double.NaN
    private var maxTorso = Double.NaN
    private var bottomKnee = Double.NaN
    private var bottomTorso = Double.NaN

    // Knee-over-toe accumulators, sampled at the same "deepest knee angle"
    // moment as bottomKnee/bottomTorso above (see accumulateDebug).
    private var bottomAnkleAngle = Double.NaN
    private var bottomKneeToeOffset = Double.NaN
    private var kneeOverToeConfidence = Double.NaN
    private var kneeOverToeLegIsLeft = false
    private var kneeOverToeAvailable = false

    fun processFrame(
        kneeAngle: Double,
        torsoAngle: Double,
        kneeMisaligned: Boolean,
        kneeOverToe: KneeOverToeMetrics? = null
    ): ExerciseAnalysisResult {

        var isRepCompleted = false
        var isRepCorrect = false

        val excessiveTorsoLean =
            !torsoAngle.isNaN() && torsoAngle > MAX_TORSO_INCLINATION
        val insufficientTorsoLean =
            !torsoAngle.isNaN() && torsoAngle < MIN_TORSO_INCLINATION
        val kneeOverToeIssue =
            kneeOverToe != null &&
                !kneeOverToe.normalizedKneeToeOffset.isNaN() &&
                kneeOverToe.normalizedKneeToeOffset > KNEE_TOE_OFFSET_LIMIT

        /*
         * A repetition starts when the user clearly leaves the standing
         * position, rather than only when the bottom position is reached.
         */
        if (
            currentState == State.UP &&
            kneeAngle < REP_START_THRESHOLD
        ) {
            beginRep()
            currentState = State.DESCENDING
        }

        val minimumLeanCheckActive =
            repInProgress &&
                !kneeAngle.isNaN() &&
                kneeAngle < SQUAT_UP_THRESHOLD &&
                (currentState == State.DESCENDING || currentState == State.DOWN)

        // Live corrective cues show immediately, but minimum lean is irrelevant
        // while the user is standing outside an active repetition.
        var warning: String? = when {
            excessiveTorsoLean -> "Keep your back straighter"
            minimumLeanCheckActive && insufficientTorsoLean -> "Lean slightly forward"
            kneeMisaligned -> "Check knee alignment"
            kneeOverToeIssue -> "Knees are past toes"
            else -> null
        }

        /*
         * Once an error appears during a repetition, it remains recorded until
         * that repetition ends. The torso lean is debounced so a single noisy
         * frame does not invalidate the rep; knee misalignment is currently
         * disabled upstream (kneeMisaligned is always false).
         */
        if (repInProgress) {
            accumulateDebug(kneeAngle, torsoAngle, kneeOverToe)
            frameRecorder.record(currentState.name) {
                linkedMapOf("knee" to kneeAngle, "torso" to torsoAngle)
            }

            if (excessiveTorsoLeanGate.update(excessiveTorsoLean)) {
                techniqueValid = false
                errors.add(RepError.EXCESSIVE_TORSO_LEAN)
            }

            if (insufficientTorsoLeanGate.update(
                    minimumLeanCheckActive && insufficientTorsoLean
                )
            ) {
                techniqueValid = false
                errors.add(RepError.INSUFFICIENT_TORSO_LEAN)
            }

            if (kneeOverToeGate.update(kneeOverToeIssue)) {
                techniqueValid = false
                errors.add(RepError.KNEES_PASS_TOES)
            }

            if (!reachedRequiredDepth && warning == null) {
                warning = "Go lower"
            }
        }

        when (currentState) {
            State.UP -> Unit

            State.DESCENDING -> {
                if (kneeAngle <= SQUAT_DOWN_THRESHOLD) {
                    reachedRequiredDepth = true
                    currentState = State.DOWN
                } else if (kneeAngle >= SQUAT_UP_THRESHOLD) {
                    /*
                     * The user started descending but returned to standing
                     * without reaching the required depth.
                     */
                    errors.add(RepError.INSUFFICIENT_DEPTH)
                    isRepCompleted = true
                    isRepCorrect = false
                    warning = "Go lower"
                }
            }

            State.DOWN -> {
                if (kneeAngle >= SQUAT_UP_THRESHOLD) {
                    isRepCompleted = true
                    isRepCorrect =
                        reachedRequiredDepth && techniqueValid
                }
            }
        }

        // Snapshot the per-rep outputs before clearing state for the next rep.
        val completedErrors: Set<RepError>
        val completedMetrics: RepDebugMetrics?
        if (isRepCompleted) {
            completedErrors = errors.toSet()
            completedMetrics = buildDebugMetrics()
            finishRep()
        } else {
            completedErrors = emptySet()
            completedMetrics = null
        }

        return ExerciseAnalysisResult(
            isRepCompleted = isRepCompleted,
            isRepCorrect = isRepCorrect,
            warning = warning,
            phaseName = currentState.name,
            errors = completedErrors,
            debugMetrics = completedMetrics
        )
    }

    private fun accumulateDebug(kneeAngle: Double, torsoAngle: Double, kneeOverToe: KneeOverToeMetrics?) {
        minKnee = minKeepNaN(minKnee, kneeAngle)
        maxTorso = maxKeepNaN(maxTorso, torsoAngle)
        // Deepest point = smallest knee angle; capture the torso (and,
        // when available, the knee-over-toe reading) there too.
        if (!kneeAngle.isNaN() && (bottomKnee.isNaN() || kneeAngle < bottomKnee)) {
            bottomKnee = kneeAngle
            bottomTorso = torsoAngle
            if (kneeOverToe != null) {
                bottomAnkleAngle = kneeOverToe.ankleAngle
                bottomKneeToeOffset = kneeOverToe.normalizedKneeToeOffset
                kneeOverToeConfidence = kneeOverToe.confidence.toDouble()
                kneeOverToeLegIsLeft = kneeOverToe.legIsLeft
                kneeOverToeAvailable = true
            }
        }
    }

    private fun buildDebugMetrics(): RepDebugMetrics = RepDebugMetrics(
        values = linkedMapOf(
            "minKneeAngle" to minKnee,
            "maxTorsoAngle" to maxTorso,
            "bottomKneeAngle" to bottomKnee,
            "bottomTorsoAngle" to bottomTorso,
            "ankleAngle" to bottomAnkleAngle
        ),
        frameTrace = frameRecorder.snapshot(),
        flags = linkedMapOf(
            "kneeOverToeAvailable" to kneeOverToeAvailable,
            "kneeOverToeLegIsLeft" to kneeOverToeLegIsLeft
        ),
        ratios = linkedMapOf(
            "normalizedKneeToeOffset" to bottomKneeToeOffset,
            "kneeOverToeConfidence" to kneeOverToeConfidence
        )
    )

    /** Prepares accumulators/gate/errors for a fresh repetition. */
    private fun beginRep() {
        repInProgress = true
        reachedRequiredDepth = false
        techniqueValid = true
        excessiveTorsoLeanGate.reset()
        insufficientTorsoLeanGate.reset()
        kneeOverToeGate.reset()
        errors.clear()
        frameRecorder.reset()
        minKnee = Double.NaN
        maxTorso = Double.NaN
        bottomKnee = Double.NaN
        bottomTorso = Double.NaN
        bottomAnkleAngle = Double.NaN
        bottomKneeToeOffset = Double.NaN
        kneeOverToeConfidence = Double.NaN
        kneeOverToeLegIsLeft = false
        kneeOverToeAvailable = false
    }

    private fun finishRep() {
        currentState = State.UP
        repInProgress = false
        reachedRequiredDepth = false
        techniqueValid = true
        excessiveTorsoLeanGate.reset()
        insufficientTorsoLeanGate.reset()
        kneeOverToeGate.reset()
    }

    fun reset() {
        currentState = State.UP
        repInProgress = false
        reachedRequiredDepth = false
        techniqueValid = true
        excessiveTorsoLeanGate.reset()
        insufficientTorsoLeanGate.reset()
        kneeOverToeGate.reset()
        errors.clear()
        frameRecorder.reset()
        minKnee = Double.NaN
        maxTorso = Double.NaN
        bottomKnee = Double.NaN
        bottomTorso = Double.NaN
        bottomAnkleAngle = Double.NaN
        bottomKneeToeOffset = Double.NaN
        kneeOverToeConfidence = Double.NaN
        kneeOverToeLegIsLeft = false
        kneeOverToeAvailable = false
    }
}
