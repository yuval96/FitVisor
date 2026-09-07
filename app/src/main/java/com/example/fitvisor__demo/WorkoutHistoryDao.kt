package com.example.fitvisor__demo

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction

@Dao
interface WorkoutHistoryDao {

    @Transaction
    suspend fun insertWorkout(
        workout: WorkoutEntity,
        exercises: List<ExerciseSessionEntity>,
        reps: List<RepRecordEntity>
    ) {
        insertWorkoutRow(workout)
        insertExercises(exercises)
        insertReps(reps)
    }

    @Insert
    suspend fun insertWorkoutRow(entity: WorkoutEntity)

    @Insert
    suspend fun insertExercises(entities: List<ExerciseSessionEntity>)

    @Insert
    suspend fun insertReps(entities: List<RepRecordEntity>)

    @Query("SELECT * FROM workout_sessions ORDER BY startTimeMillis DESC")
    suspend fun getAllWorkouts(): List<WorkoutEntity>

    @Query("SELECT * FROM workout_sessions WHERE id = :workoutId")
    suspend fun getWorkout(workoutId: String): WorkoutEntity?

    @Query("SELECT * FROM exercise_sessions WHERE workoutId = :workoutId ORDER BY orderIndex ASC")
    suspend fun getExercisesForWorkout(workoutId: String): List<ExerciseSessionEntity>

    @Query("SELECT * FROM rep_records WHERE exerciseSessionId = :exerciseSessionId ORDER BY repNumber ASC")
    suspend fun getRepsForExercise(exerciseSessionId: String): List<RepRecordEntity>

    @Transaction
    suspend fun deleteWorkout(workoutId: String) {
        deleteRepsForWorkout(workoutId)
        deleteExercisesForWorkout(workoutId)
        deleteWorkoutRow(workoutId)
    }

    @Query(
        "DELETE FROM rep_records WHERE exerciseSessionId IN " +
            "(SELECT id FROM exercise_sessions WHERE workoutId = :workoutId)"
    )
    suspend fun deleteRepsForWorkout(workoutId: String)

    @Query("DELETE FROM exercise_sessions WHERE workoutId = :workoutId")
    suspend fun deleteExercisesForWorkout(workoutId: String)

    @Query("DELETE FROM workout_sessions WHERE id = :workoutId")
    suspend fun deleteWorkoutRow(workoutId: String)
}
