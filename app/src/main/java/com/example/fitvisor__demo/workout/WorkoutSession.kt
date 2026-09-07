package com.example.fitvisor__demo.workout

import com.example.fitvisor__demo.model.ExerciseType
import com.example.fitvisor__demo.model.RepRecord

/** Snapshot of one workout. Times are wall-clock milliseconds; duration is active exercise time. */
data class WorkoutSession(
    val id: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val exercises: List<ExerciseSession> = emptyList()
) {
    val totalReps: Int get() = exercises.sumOf { it.reps.size }
    val correctReps: Int get() = exercises.sumOf { it.correctReps }
    val incorrectReps: Int get() = totalReps - correctReps
    val durationSeconds: Long get() = exercises.sumOf { it.durationSeconds }
}

/** Each selection gets its own ID, even when the same exercise is selected again. */
data class ExerciseSession(
    val id: String,
    val exerciseType: ExerciseType,
    val startTimeMillis: Long,
    val endTimeMillis: Long? = null,
    val durationSeconds: Long = 0,
    val reps: List<RepRecord> = emptyList()
) {
    val correctReps: Int get() = reps.count { it.isCorrect }
    val incorrectReps: Int get() = reps.size - correctReps
}
