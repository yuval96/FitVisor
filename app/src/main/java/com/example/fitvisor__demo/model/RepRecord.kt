package com.example.fitvisor__demo.model

/**
 * Immutable record of a single completed repetition.
 *
 * @param repNumber        1-based position of the rep within its exercise session.
 * @param exerciseType     the exercise this rep belongs to.
 * @param isCorrect        whether the rep passed all technique checks.
 * @param errors           ordered, de-duplicated list of faults for this rep;
 *                         empty when [isCorrect] is true.
 * @param elapsedSessionMs active exercise time at this rep completing, in ms.
 * @param debugMetrics     per-rep aggregate/debug angle data (see [RepDebugMetrics]).
 */
data class RepRecord(
    val repNumber: Int,
    val exerciseType: ExerciseType,
    val isCorrect: Boolean,
    val errors: List<RepError>,
    val elapsedSessionMs: Long,
    val debugMetrics: RepDebugMetrics
)
