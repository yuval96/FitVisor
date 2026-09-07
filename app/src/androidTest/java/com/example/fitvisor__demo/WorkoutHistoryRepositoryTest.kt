package com.example.fitvisor__demo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.fitvisor__demo.model.ExerciseType
import com.example.fitvisor__demo.model.RepDebugMetrics
import com.example.fitvisor__demo.model.RepError
import com.example.fitvisor__demo.model.RepRecord
import com.example.fitvisor__demo.workout.ExerciseSession
import com.example.fitvisor__demo.workout.WorkoutHistoryDatabase
import com.example.fitvisor__demo.workout.WorkoutHistoryRepository
import com.example.fitvisor__demo.workout.WorkoutSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutHistoryRepositoryTest {

    private lateinit var database: WorkoutHistoryDatabase
    private lateinit var repository: WorkoutHistoryRepository

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            WorkoutHistoryDatabase::class.java
        ).allowMainThreadQueries().build()
        repository = WorkoutHistoryRepository(database.workoutHistoryDao())
    }

    @After fun tearDown() {
        database.close()
    }

    private fun completedWorkout(id: String, start: Long = 1_000L): WorkoutSession {
        val exercise1 = ExerciseSession(
            id = "$id-ex1",
            exerciseType = ExerciseType.SQUAT,
            startTimeMillis = start,
            endTimeMillis = start + 10_000,
            durationSeconds = 10,
            reps = listOf(
                RepRecord(1, ExerciseType.SQUAT, true, emptyList(), 4_000,
                    RepDebugMetrics(mapOf("minKneeAngle" to 90.0))),
                RepRecord(2, ExerciseType.SQUAT, false, listOf(RepError.INSUFFICIENT_DEPTH), 9_000,
                    RepDebugMetrics(mapOf("minKneeAngle" to 140.0)))
            )
        )
        val exercise2 = ExerciseSession(
            id = "$id-ex2",
            exerciseType = ExerciseType.PUSH_UP,
            startTimeMillis = start + 20_000,
            endTimeMillis = start + 25_000,
            durationSeconds = 5,
            reps = listOf(RepRecord(1, ExerciseType.PUSH_UP, true, emptyList(), 3_000,
                RepDebugMetrics(mapOf("minElbowAngle" to 70.0), flags = mapOf("startDetected" to true))))
        )
        return WorkoutSession(id, start, start + 25_000, listOf(exercise1, exercise2))
    }

    @Test fun savedWorkoutRoundTripsWithExercisesRepsErrorsAndMetrics() = runBlocking {
        val workout = completedWorkout("w1")
        repository.saveCompletedWorkout(workout)

        val loaded = repository.getWorkout("w1")!!
        assertEquals(workout.id, loaded.id)
        assertEquals(workout.startTimeMillis, loaded.startTimeMillis)
        assertEquals(workout.endTimeMillis, loaded.endTimeMillis)
        assertEquals(listOf("w1-ex1", "w1-ex2"), loaded.exercises.map { it.id })
        assertEquals(listOf(ExerciseType.SQUAT, ExerciseType.PUSH_UP), loaded.exercises.map { it.exerciseType })

        val squatReps = loaded.exercises[0].reps
        assertEquals(listOf(1, 2), squatReps.map { it.repNumber })
        assertFalse(squatReps[1].isCorrect)
        assertEquals(listOf(RepError.INSUFFICIENT_DEPTH), squatReps[1].errors)
        assertEquals(90.0, squatReps[0].debugMetrics.values["minKneeAngle"]!!, 0.0)
        assertEquals(140.0, squatReps[1].debugMetrics.values["minKneeAngle"]!!, 0.0)

        val pushUpRep = loaded.exercises[1].reps.single()
        assertEquals(70.0, pushUpRep.debugMetrics.values["minElbowAngle"]!!, 0.0)
        assertEquals(mapOf("startDetected" to true), pushUpRep.debugMetrics.flags)
    }

    // A plain (non-runBlocking) body: assertThrows's own return value would
    // otherwise become this function's inferred return type, which the
    // on-device JUnit4 runner rejects for @Test methods (must be void) even
    // though the JVM unit-test runner silently accepts it.
    @Test fun savingAnUnfinishedWorkoutThrows() {
        val workout = WorkoutSession("unfinished", 1_000L, endTimeMillis = null)
        assertThrows(IllegalStateException::class.java) {
            runBlocking { repository.saveCompletedWorkout(workout) }
        }
    }

    @Test fun onlyCompletedWorkoutsAreEverPersisted() = runBlocking {
        repository.saveCompletedWorkout(completedWorkout("w1"))
        val all = repository.getAllWorkouts()
        assertEquals(1, all.size)
        assertNotNull(all.single().endTimeMillis)
    }

    @Test fun workoutsAreListedMostRecentFirst() = runBlocking {
        repository.saveCompletedWorkout(completedWorkout("older", start = 1_000L))
        repository.saveCompletedWorkout(completedWorkout("newer", start = 50_000L))
        val ids = repository.getAllWorkouts().map { it.id }
        assertEquals(listOf("newer", "older"), ids)
    }

    @Test fun deletingWorkoutRemovesItAndAllChildRowsTransactionally() = runBlocking {
        repository.saveCompletedWorkout(completedWorkout("w1"))
        repository.saveCompletedWorkout(completedWorkout("w2", start = 90_000L))

        repository.deleteWorkout("w1")

        assertNull(repository.getWorkout("w1"))
        assertEquals(listOf("w2"), repository.getAllWorkouts().map { it.id })
        assertTrue(database.workoutHistoryDao().getExercisesForWorkout("w1").isEmpty())
        assertTrue(database.workoutHistoryDao().getRepsForExercise("w1-ex1").isEmpty())
        // The untouched workout's data must survive the deletion of the other one.
        assertNotNull(repository.getWorkout("w2"))
        assertEquals(2, repository.getWorkout("w2")!!.exercises.size)
    }

    @Test fun deletingUnknownWorkoutIsANoop() = runBlocking {
        repository.saveCompletedWorkout(completedWorkout("w1"))
        repository.deleteWorkout("does-not-exist")
        assertEquals(1, repository.getAllWorkouts().size)
    }
}
