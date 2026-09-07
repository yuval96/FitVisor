package com.example.fitvisor__demo

import com.example.fitvisor__demo.model.ExerciseType
import com.example.fitvisor__demo.model.RepDebugMetrics
import com.example.fitvisor__demo.model.RepError
import com.example.fitvisor__demo.model.RepRecord
import com.example.fitvisor__demo.workout.WorkoutManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [WorkoutManager]'s per-rep history and derived totals. A fake clock
 * is injected so no Android [android.os.SystemClock] dependency is exercised.
 */
class WorkoutManagerTest {

    private var now = 0L
    private val manager = WorkoutManager(elapsedClock = { now })

    @Test fun pauseExcludesSummaryAndBackgroundTime() {
        manager.startSession()
        now = 5_000
        manager.pauseSession()
        now = 20_000
        manager.pauseSession()
        assertEquals(5L, manager.getElapsedTimeSeconds())
        assertNull(record(true))
        manager.resumeSession()
        now = 23_000
        assertEquals(8L, manager.getElapsedTimeSeconds())
        assertEquals(8_000L, record(true)!!.elapsedSessionMs)
        assertEquals(8L, manager.endSession().durationSeconds)
        now = 30_000
        assertEquals(8L, manager.getSummary().durationSeconds)
    }

    private fun record(
        isCorrect: Boolean,
        errors: Set<RepError> = emptySet(),
        exercise: ExerciseType = ExerciseType.SQUAT
    ): RepRecord? = manager.recordRep(exercise, isCorrect, errors, RepDebugMetrics.EMPTY)

    @Test
    fun firstCompletedRep_isRepOne() {
        manager.startSession()
        val rep = record(isCorrect = true)
        assertEquals(1, rep!!.repNumber)
    }

    @Test
    fun repNumbering_incrementsInOrder() {
        manager.startSession()
        record(isCorrect = true)
        record(isCorrect = false)
        record(isCorrect = true)

        val numbers = manager.getRepRecords().map { it.repNumber }
        assertEquals(listOf(1, 2, 3), numbers)
    }

    @Test
    fun correctAndIncorrectStatus_isStored() {
        manager.startSession()
        record(isCorrect = true)
        record(isCorrect = false)

        val records = manager.getRepRecords()
        assertTrue(records[0].isCorrect)
        assertFalse(records[1].isCorrect)
    }

    @Test
    fun multipleErrors_areStored() {
        manager.startSession()
        val rep = record(
            isCorrect = false,
            errors = setOf(RepError.INSUFFICIENT_DEPTH, RepError.EXCESSIVE_TORSO_LEAN)
        )

        assertEquals(2, rep!!.errors.size)
        assertTrue(rep.errors.contains(RepError.INSUFFICIENT_DEPTH))
        assertTrue(rep.errors.contains(RepError.EXCESSIVE_TORSO_LEAN))
    }

    @Test
    fun storedErrors_haveNoDuplicates() {
        manager.startSession()
        val rep = record(
            isCorrect = false,
            errors = setOf(RepError.INCOMPLETE_CURL)
        )
        assertEquals(rep!!.errors.toSet().size, rep.errors.size)
    }

    @Test
    fun totals_matchTheRecordList() {
        manager.startSession()
        record(isCorrect = true)
        record(isCorrect = false)
        record(isCorrect = true)
        record(isCorrect = false)

        assertEquals(4, manager.getTotalReps())
        assertEquals(2, manager.getCorrectReps())
        assertEquals(2, manager.getIncorrectReps())

        val summary = manager.getSummary()
        assertEquals(manager.getRepRecords().size, summary.totalReps)
        assertEquals(manager.getRepRecords().count { it.isCorrect }, summary.correctReps)
    }

    @Test
    fun elapsedSessionMs_isRecordedFromSessionStart() {
        now = 1_000L
        manager.startSession()
        now = 4_500L
        val rep = record(isCorrect = true)
        assertEquals(3_500L, rep!!.elapsedSessionMs)
    }

    @Test
    fun recordRep_withoutActiveSession_isIgnored() {
        // No startSession() called yet.
        assertNull(record(isCorrect = true))
        assertEquals(0, manager.getTotalReps())
    }

    @Test
    fun startSession_clearsPreviousReps() {
        manager.startSession()
        record(isCorrect = true)
        record(isCorrect = false)
        assertEquals(2, manager.getTotalReps())

        manager.startSession()
        assertEquals(0, manager.getTotalReps())
        assertTrue(manager.getRepRecords().isEmpty())
    }
}
