package com.example.fitvisor__demo.workout

import android.os.SystemClock
import com.example.fitvisor__demo.model.ExerciseType
import com.example.fitvisor__demo.model.RepDebugMetrics
import com.example.fitvisor__demo.model.RepError
import com.example.fitvisor__demo.model.RepRecord
import java.util.UUID

/**
 * Main-thread session owner, independent of Activities. Reuses WorkoutManager
 * for each exercise's reps and pause-aware timer. No persistence is performed.
 * IDs reject callbacks/navigation left over from an earlier exercise or workout.
 */
class WorkoutSessionManager(
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() },
    private val wallClock: () -> Long = { System.currentTimeMillis() },
    private val newId: () -> String = { UUID.randomUUID().toString() }
) {
    private var workout: WorkoutSession? = null
    private var exercise: ExerciseSession? = null
    private var repManager: WorkoutManager? = null

    val currentExerciseId: String? get() = exercise?.id

    val currentWorkout: WorkoutSession?
        get() = workout?.let { it.copy(exercises = it.exercises + listOfNotNull(currentExercise)) }

    val currentExercise: ExerciseSession?
        get() = exercise?.copy(
            durationSeconds = repManager!!.getElapsedTimeSeconds(),
            reps = repManager!!.getRepRecords()
        )

    fun startWorkout(): WorkoutSession {
        check(workout == null) { "Finish the active workout before starting another." }
        return WorkoutSession(newId(), wallClock()).also { workout = it }
    }

    fun startExercise(workoutId: String, type: ExerciseType): ExerciseSession? {
        if (workout?.id != workoutId || exercise != null) return null
        repManager = WorkoutManager(elapsedClock).apply {
            startSession()
            // Camera Activity resumes this timer once visible.
            pauseSession()
        }
        return ExerciseSession(newId(), type, wallClock()).also { exercise = it }
    }

    fun resumeExercise(exerciseId: String) {
        if (exercise?.id == exerciseId) repManager?.resumeSession()
    }

    fun pauseExercise(exerciseId: String) {
        if (exercise?.id == exerciseId) repManager?.pauseSession()
    }

    fun recordRep(
        exerciseId: String,
        isCorrect: Boolean,
        errors: Set<RepError>,
        debugMetrics: RepDebugMetrics
    ): RepRecord? {
        val active = exercise?.takeIf { it.id == exerciseId } ?: return null
        // Copy diagnostic collections so an analyzer cannot mutate a completed rep.
        val metrics = debugMetrics.copy(
            values = debugMetrics.values.toMap(),
            frameTrace = debugMetrics.frameTrace.map { it.copy(metrics = it.metrics.toMap()) }
        )
        return repManager?.recordRep(active.exerciseType, isCorrect, errors, metrics)
    }

    fun finishExercise(exerciseId: String): ExerciseSession? {
        val active = exercise?.takeIf { it.id == exerciseId } ?: return null
        val summary = repManager!!.endSession()
        val finished = active.copy(
            endTimeMillis = wallClock().coerceAtLeast(active.startTimeMillis),
            durationSeconds = summary.durationSeconds,
            reps = repManager!!.getRepRecords()
        )
        workout = workout!!.let { it.copy(exercises = it.exercises + finished) }
        exercise = null
        repManager = null
        return finished
    }

    /** An exercise must be finished first; this is the only operation ending a workout. */
    fun finishWorkout(workoutId: String): WorkoutSession? {
        val active = workout?.takeIf { it.id == workoutId } ?: return null
        if (exercise != null) return null
        val latestEnd = active.exercises.lastOrNull()?.endTimeMillis ?: active.startTimeMillis
        val finished = active.copy(endTimeMillis = wallClock().coerceAtLeast(latestEnd))
        workout = null
        return finished
    }
}

/** Retained across Activity recreation, but intentionally lost on process death. */
object ActiveWorkoutStore {
    val manager = WorkoutSessionManager()
}
