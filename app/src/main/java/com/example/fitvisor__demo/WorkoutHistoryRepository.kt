package com.example.fitvisor__demo

import android.content.Context

/**
 * Persistence for completed workouts. Only [WorkoutSession]s that already have
 * an [WorkoutSession.endTimeMillis] can be saved, so History can never surface
 * an in-progress workout.
 */
class WorkoutHistoryRepository(private val dao: WorkoutHistoryDao) {

    suspend fun saveCompletedWorkout(workout: WorkoutSession) {
        val endTime = checkNotNull(workout.endTimeMillis) { "Only completed workouts can be saved." }
        val workoutEntity = WorkoutEntity(workout.id, workout.startTimeMillis, endTime)
        val exerciseEntities = mutableListOf<ExerciseSessionEntity>()
        val repEntities = mutableListOf<RepRecordEntity>()
        workout.exercises.forEachIndexed { index, exercise ->
            val exerciseEnd = checkNotNull(exercise.endTimeMillis) { "Only completed exercises can be saved." }
            exerciseEntities.add(
                ExerciseSessionEntity(
                    id = exercise.id,
                    workoutId = workout.id,
                    orderIndex = index,
                    exerciseType = exercise.exerciseType.name,
                    startTimeMillis = exercise.startTimeMillis,
                    endTimeMillis = exerciseEnd,
                    durationSeconds = exercise.durationSeconds
                )
            )
            exercise.reps.forEach { rep ->
                repEntities.add(
                    RepRecordEntity(
                        exerciseSessionId = exercise.id,
                        repNumber = rep.repNumber,
                        exerciseType = rep.exerciseType.name,
                        isCorrect = rep.isCorrect,
                        errors = WorkoutHistoryCodec.encodeErrors(rep.errors),
                        elapsedSessionMs = rep.elapsedSessionMs,
                        debugMetrics = WorkoutHistoryCodec.encodeMetrics(rep.debugMetrics.values),
                        debugFlags = WorkoutHistoryCodec.encodeFlags(rep.debugMetrics.flags),
                        // Same Map<String, Double> shape as debugMetrics, just a
                        // separate column so non-angle ratios never get reloaded
                        // into the angle-labelled `values` map.
                        debugRatios = WorkoutHistoryCodec.encodeMetrics(rep.debugMetrics.ratios)
                    )
                )
            }
        }
        dao.insertWorkout(workoutEntity, exerciseEntities, repEntities)
    }

    /** Every completed workout, most recent first, fully hydrated with exercises and reps. */
    suspend fun getAllWorkouts(): List<WorkoutSession> =
        dao.getAllWorkouts().map { hydrate(it) }

    suspend fun getWorkout(workoutId: String): WorkoutSession? =
        dao.getWorkout(workoutId)?.let { hydrate(it) }

    suspend fun deleteWorkout(workoutId: String) {
        dao.deleteWorkout(workoutId)
    }

    private suspend fun hydrate(workoutEntity: WorkoutEntity): WorkoutSession {
        val exercises = dao.getExercisesForWorkout(workoutEntity.id).map { exerciseEntity ->
            val reps = dao.getRepsForExercise(exerciseEntity.id).map { it.toDomain() }
            ExerciseSession(
                id = exerciseEntity.id,
                exerciseType = ExerciseType.fromNameOrDefault(exerciseEntity.exerciseType),
                startTimeMillis = exerciseEntity.startTimeMillis,
                endTimeMillis = exerciseEntity.endTimeMillis,
                durationSeconds = exerciseEntity.durationSeconds,
                reps = reps
            )
        }
        return WorkoutSession(
            id = workoutEntity.id,
            startTimeMillis = workoutEntity.startTimeMillis,
            endTimeMillis = workoutEntity.endTimeMillis,
            exercises = exercises
        )
    }

    private fun RepRecordEntity.toDomain(): RepRecord = RepRecord(
        repNumber = repNumber,
        exerciseType = ExerciseType.fromNameOrDefault(exerciseType),
        isCorrect = isCorrect,
        errors = WorkoutHistoryCodec.decodeErrors(errors),
        elapsedSessionMs = elapsedSessionMs,
        debugMetrics = RepDebugMetrics(
            values = WorkoutHistoryCodec.decodeMetrics(debugMetrics),
            flags = WorkoutHistoryCodec.decodeFlags(debugFlags),
            ratios = WorkoutHistoryCodec.decodeMetrics(debugRatios)
        )
    )

    companion object {
        @Volatile private var instance: WorkoutHistoryRepository? = null

        /** Shared, process-wide repository backed by the on-disk Room database. */
        fun getInstance(context: Context): WorkoutHistoryRepository =
            instance ?: synchronized(this) {
                instance ?: WorkoutHistoryRepository(
                    WorkoutHistoryDatabase.getInstance(context).workoutHistoryDao()
                ).also { instance = it }
            }
    }
}
