package com.example.fitvisor__demo

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Room row for one completed [WorkoutSession]. Only completed workouts are ever inserted. */
@Entity(tableName = "workout_sessions")
data class WorkoutEntity(
    @PrimaryKey val id: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long
)

/** Room row for one [ExerciseSession] within a workout, ordered by [orderIndex]. */
@Entity(tableName = "exercise_sessions")
data class ExerciseSessionEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(index = true) val workoutId: String,
    val orderIndex: Int,
    val exerciseType: String,
    val startTimeMillis: Long,
    val endTimeMillis: Long,
    val durationSeconds: Long
)

/**
 * Room row for one [RepRecord]. [errors], [debugMetrics] and [debugFlags] are
 * encoded strings (see [WorkoutHistoryCodec]) rather than a normalized
 * table/TypeConverter, since they're small, order-sensitive, and only ever
 * read back as a whole.
 */
@Entity(tableName = "rep_records")
data class RepRecordEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(index = true) val exerciseSessionId: String,
    val repNumber: Int,
    val exerciseType: String,
    val isCorrect: Boolean,
    val errors: String,
    val elapsedSessionMs: Long,
    val debugMetrics: String,
    val debugFlags: String = ""
)
