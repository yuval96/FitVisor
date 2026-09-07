package com.example.fitvisor__demo

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [WorkoutEntity::class, ExerciseSessionEntity::class, RepRecordEntity::class],
    version = 2,
    exportSchema = false
)
abstract class WorkoutHistoryDatabase : RoomDatabase() {
    abstract fun workoutHistoryDao(): WorkoutHistoryDao

    companion object {
        @Volatile private var instance: WorkoutHistoryDatabase? = null

        fun getInstance(context: Context): WorkoutHistoryDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    WorkoutHistoryDatabase::class.java,
                    "fitvisor_workout_history.db"
                )
                    // No migration path is defined yet; a bumped schema simply
                    // starts History fresh rather than crashing on open.
                    .fallbackToDestructiveMigration()
                    .build().also { instance = it }
            }
    }
}
