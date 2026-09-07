package com.example.fitvisor__demo

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
 * Torso-lean stability change: an excessive torso lean must persist for
 * [ConsecutiveGate.requiredFrames] consecutive analyzed frames before it
 * invalidates the repetition, so a single noisy frame above the limit no longer
 * fails the rep. The live "Keep your back straighter" cue still shows on any
 * frame above the limit. The torso-inclination threshold itself is applied
 * unchanged by this debounce (currently 45 degrees).
 */
class SquatRuleEngine {

    companion object {
        private const val REP_START_THRESHOLD = 150.0
        private const val SQUAT_DOWN_THRESHOLD = 100.0
        private const val SQUAT_UP_THRESHOLD = 160.0
        // Maximum allowed torso inclination from vertical (raised 35 -> 45).
        private const val TORSO_INCLINATION_LIMIT = 45.0
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

    /** Torso lean must persist for 2 consecutive frames before it invalidates. */
    private val torsoGate = ConsecutiveGate()

    private val errors = linkedSetOf<RepError>()
    private val frameRecorder = RepFrameRecorder()

    // Per-rep debug accumulators.
    private var minKnee = Double.NaN
    private var maxTorso = Double.NaN
    private var bottomKnee = Double.NaN
    private var bottomTorso = Double.NaN

    fun processFrame(
        kneeAngle: Double,
        torsoAngle: Double,
        kneeMisaligned: Boolean
    ): ExerciseAnalysisResult {

        var isRepCompleted = false
        var isRepCorrect = false

        val torsoIssue = torsoAngle > TORSO_INCLINATION_LIMIT

        // Live corrective cue (preserved): shows immediately on a single frame.
        var warning: String? = when {
            torsoIssue -> "Keep your back straighter"
            kneeMisaligned -> "Check knee alignment"
            else -> null
        }

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

        /*
         * Once an error appears during a repetition, it remains recorded until
         * that repetition ends. The torso lean is debounced so a single noisy
         * frame does not invalidate the rep; knee misalignment is currently
         * disabled upstream (kneeMisaligned is always false).
         */
        if (repInProgress) {
            accumulateDebug(kneeAngle, torsoAngle)
            frameRecorder.record(currentState.name) {
                linkedMapOf("knee" to kneeAngle, "torso" to torsoAngle)
            }

            if (torsoGate.update(torsoIssue)) {
                techniqueValid = false
                errors.add(RepError.EXCESSIVE_TORSO_LEAN)
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

    private fun accumulateDebug(kneeAngle: Double, torsoAngle: Double) {
        minKnee = minKeepNaN(minKnee, kneeAngle)
        maxTorso = maxKeepNaN(maxTorso, torsoAngle)
        // Deepest point = smallest knee angle; capture the torso there too.
        if (!kneeAngle.isNaN() && (bottomKnee.isNaN() || kneeAngle < bottomKnee)) {
            bottomKnee = kneeAngle
            bottomTorso = torsoAngle
        }
    }

    private fun buildDebugMetrics(): RepDebugMetrics = RepDebugMetrics(
        values = linkedMapOf(
            "minKneeAngle" to minKnee,
            "maxTorsoAngle" to maxTorso,
            "bottomKneeAngle" to bottomKnee,
            "bottomTorsoAngle" to bottomTorso
        ),
        frameTrace = frameRecorder.snapshot()
    )

    /** Prepares accumulators/gate/errors for a fresh repetition. */
    private fun beginRep() {
        repInProgress = true
        reachedRequiredDepth = false
        techniqueValid = true
        torsoGate.reset()
        errors.clear()
        frameRecorder.reset()
        minKnee = Double.NaN
        maxTorso = Double.NaN
        bottomKnee = Double.NaN
        bottomTorso = Double.NaN
    }

    private fun finishRep() {
        currentState = State.UP
        repInProgress = false
        reachedRequiredDepth = false
        techniqueValid = true
        torsoGate.reset()
    }

    fun reset() {
        currentState = State.UP
        repInProgress = false
        reachedRequiredDepth = false
        techniqueValid = true
        torsoGate.reset()
        errors.clear()
        frameRecorder.reset()
        minKnee = Double.NaN
        maxTorso = Double.NaN
        bottomKnee = Double.NaN
        bottomTorso = Double.NaN
    }
}
