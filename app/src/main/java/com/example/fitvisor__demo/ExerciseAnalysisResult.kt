package com.example.fitvisor__demo

/**
 * Common result produced by every exercise rule engine for a single frame.
 *
 * @param isRepCompleted true exactly on the frame a repetition finishes.
 * @param isRepCorrect   whether the completed repetition was technically valid.
 *                       Only meaningful when [isRepCompleted] is true.
 * @param warning        the highest-priority corrective message, or null.
 * @param phaseName      the current phase/state, used for the on-screen overlay.
 * @param errors         the complete, de-duplicated set of faults accumulated
 *                       during the just-completed repetition. Populated only on
 *                       the frame where [isRepCompleted] is true (empty otherwise).
 * @param debugMetrics   per-rep aggregate/debug angle data for the completed
 *                       repetition, or null on non-completing frames.
 */
data class ExerciseAnalysisResult(
    val isRepCompleted: Boolean,
    val isRepCorrect: Boolean,
    val warning: String?,
    val phaseName: String,
    val errors: Set<RepError> = emptySet(),
    val debugMetrics: RepDebugMetrics? = null
)
