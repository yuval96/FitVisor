package com.example.fitvisor__demo

import com.example.fitvisor__demo.model.ExerciseType
import com.example.fitvisor__demo.model.RepDebugMetrics
import com.example.fitvisor__demo.model.RepError
import com.example.fitvisor__demo.model.RepFrameSample
import com.example.fitvisor__demo.workout.ExerciseSession
import com.example.fitvisor__demo.workout.WorkoutSession
import com.example.fitvisor__demo.workout.WorkoutSessionManager
import org.junit.Assert.*
import org.junit.Test

class WorkoutSessionManagerTest {
    private var elapsed = 0L
    private var wall = 1_700_000_000_000L
    private var nextId = 0
    private val manager = WorkoutSessionManager({ elapsed }, { wall }, { "id-${++nextId}" })

    private fun advance(ms: Long) { elapsed += ms; wall += ms }
    private fun record(id: String, correct: Boolean = true) = manager.recordRep(
        id, correct, if (correct) emptySet() else setOf(RepError.INSUFFICIENT_DEPTH),
        RepDebugMetrics(mapOf("minKneeAngle" to 92.0))
    )
    private fun startExercise(workout: WorkoutSession, type: ExerciseType): ExerciseSession =
        manager.startExercise(workout.id, type)!!.also { manager.resumeExercise(it.id) }

    @Test fun finishExerciseRetainsWorkoutAndAllowsAnotherExercise() {
        val workout = manager.startWorkout()
        assertTrue(workout.exercises.isEmpty())
        assertNull(workout.endTimeMillis)
        val squat = startExercise(workout, ExerciseType.SQUAT)
        advance(5_000)
        record(squat.id)
        val finishedSquat = manager.finishExercise(squat.id)!!
        assertEquals(wall, finishedSquat.endTimeMillis)
        assertEquals(workout.id, manager.currentWorkout!!.id)
        assertNull(manager.currentWorkout!!.endTimeMillis)
        assertNull(manager.currentExercise)
        val press = startExercise(workout, ExerciseType.SHOULDER_PRESS)
        record(press.id, false)
        manager.finishExercise(press.id)
        val finished = manager.finishWorkout(workout.id)!!
        assertEquals(listOf(squat.id, press.id), finished.exercises.map { it.id })
        assertEquals(2, finished.totalReps)
        assertEquals(1, finished.correctReps)
        assertEquals(1, finished.incorrectReps)
        assertEquals(workout.startTimeMillis, finished.startTimeMillis)
        assertEquals(wall, finished.endTimeMillis)
        assertNull(manager.currentWorkout)
    }

    @Test fun repeatedExerciseTypesHaveSeparateIdsAndRepNumbering() {
        val workout = manager.startWorkout()
        val first = startExercise(workout, ExerciseType.SQUAT)
        record(first.id)
        record(first.id, false)
        manager.finishExercise(first.id)
        val second = startExercise(workout, ExerciseType.SQUAT)
        record(second.id)
        manager.finishExercise(second.id)
        val finished = manager.finishWorkout(workout.id)!!
        assertNotEquals(first.id, second.id)
        assertEquals(listOf(1, 2), finished.exercises[0].reps.map { it.repNumber })
        assertEquals(listOf(1), finished.exercises[1].reps.map { it.repNumber })
        assertEquals(3, finished.totalReps)
    }

    @Test fun completedRepErrorsAndMetricsSurviveAnalyzerMutationAndNextExercise() {
        val workout = manager.startWorkout()
        val first = startExercise(workout, ExerciseType.SQUAT)
        val errors = linkedSetOf(RepError.INSUFFICIENT_DEPTH, RepError.EXCESSIVE_TORSO_LEAN)
        val values = linkedMapOf("minKneeAngle" to 120.0)
        val frameValues = linkedMapOf("knee" to 120.0)
        manager.recordRep(first.id, false, errors,
            RepDebugMetrics(values, listOf(RepFrameSample(0, "DESCENDING", frameValues))))
        errors.clear()
        values.clear()
        frameValues.clear()
        manager.finishExercise(first.id)
        startExercise(workout, ExerciseType.BICEPS_CURL)
        val rep = manager.currentWorkout!!.exercises.first().reps.single()
        assertFalse(rep.isCorrect)
        assertEquals(2, rep.errors.size)
        assertEquals(120.0, rep.debugMetrics.values["minKneeAngle"]!!, 0.0)
        assertEquals(120.0, rep.debugMetrics.frameTrace.single().metrics["knee"]!!, 0.0)
    }

    @Test fun pausesAndExerciseSelectionTimeAreExcludedFromActiveDuration() {
        val workout = manager.startWorkout()
        advance(10_000)
        val first = manager.startExercise(workout.id, ExerciseType.SQUAT)!!
        advance(10_000) // launch/camera wait
        assertNull(record(first.id))
        manager.resumeExercise(first.id)
        advance(5_000)
        manager.pauseExercise(first.id)
        advance(20_000)
        assertNull(record(first.id))
        manager.resumeExercise(first.id)
        advance(3_000)
        assertEquals(8_000L, record(first.id)!!.elapsedSessionMs)
        manager.finishExercise(first.id)
        advance(30_000)
        val second = startExercise(workout, ExerciseType.PUSH_UP)
        advance(4_000)
        manager.finishExercise(second.id)
        val finished = manager.finishWorkout(workout.id)!!
        assertEquals(12L, finished.durationSeconds)
        assertEquals(82_000L, finished.endTimeMillis!! - finished.startTimeMillis)
    }

    @Test fun staleOrDuplicateActionsCannotAlterAnotherExercise() {
        val workout = manager.startWorkout()
        val first = startExercise(workout, ExerciseType.SQUAT)
        manager.finishExercise(first.id)
        assertNull(manager.finishExercise(first.id))
        val second = startExercise(workout, ExerciseType.SQUAT)
        assertNull(record(first.id))
        manager.pauseExercise(first.id)
        assertNotNull(record(second.id))
        assertNull(manager.finishExercise(first.id))
        assertEquals(second.id, manager.currentExercise!!.id)
        assertEquals(2, manager.currentWorkout!!.exercises.size)
    }

    @Test fun workoutCannotFinishUntilItsExerciseFinishes() {
        val workout = manager.startWorkout()
        val exercise = startExercise(workout, ExerciseType.SQUAT)
        assertNull(manager.finishWorkout(workout.id))
        assertNull(manager.startExercise(workout.id, ExerciseType.PUSH_UP))
        assertThrows(IllegalStateException::class.java) { manager.startWorkout() }
        assertEquals(exercise.id, manager.currentExercise!!.id)
        manager.finishExercise(exercise.id)
        assertNotNull(manager.finishWorkout(workout.id))
        assertNull(manager.finishWorkout(workout.id))
    }

    @Test fun newWorkoutCannotBeChangedByOldWorkoutActions() {
        val old = manager.startWorkout()
        manager.finishWorkout(old.id)
        val fresh = manager.startWorkout()
        assertNotEquals(old.id, fresh.id)
        assertTrue(fresh.exercises.isEmpty())
        assertNull(manager.startExercise(old.id, ExerciseType.SQUAT))
        assertNull(manager.finishWorkout(old.id))
        assertEquals(fresh.id, manager.currentWorkout!!.id)
    }

    @Test fun zeroRepExercisesAndEmptyWorkoutsHaveValidSummaries() {
        val empty = manager.startWorkout()
        assertEquals(0, manager.finishWorkout(empty.id)!!.totalReps)
        val workout = manager.startWorkout()
        val exercise = startExercise(workout, ExerciseType.SQUAT)
        manager.finishExercise(exercise.id)
        val finished = manager.finishWorkout(workout.id)!!
        assertEquals(1, finished.exercises.size)
        assertTrue(finished.exercises.single().reps.isEmpty())
        assertEquals(0, finished.totalReps)
        assertEquals(0L, finished.durationSeconds)
    }

    @Test fun snapshotsRemainStableAndNewOwnerDoesNotInventRecoveredWorkout() {
        val workout = manager.startWorkout()
        val exercise = startExercise(workout, ExerciseType.SQUAT)
        record(exercise.id)
        val snapshot = manager.currentWorkout!!
        record(exercise.id)
        assertEquals(1, snapshot.totalReps)
        assertEquals(2, manager.currentWorkout!!.totalReps)
        val newProcessOwner = WorkoutSessionManager({ elapsed }, { wall })
        assertNull(newProcessOwner.currentWorkout)
        assertNull(newProcessOwner.currentExercise)
        assertNull(newProcessOwner.finishWorkout(workout.id))
    }
}
