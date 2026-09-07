package com.example.fitvisor__demo

import android.os.SystemClock

/**
 * Workout Manager Module.
 *
 * Owns the ordered list of [RepRecord]s for the current session and derives the
 * session totals (total / correct / incorrect reps) from it. Duration is tracked
 * from a monotonic clock.
 *
 * The clock is injectable ([elapsedClock]) purely so this class can be unit
 * tested on the JVM without Android's [SystemClock]; production code uses the
 * default.
 */
class WorkoutManager(
    private val elapsedClock: () -> Long = { SystemClock.elapsedRealtime() }
) {

    private val reps = mutableListOf<RepRecord>()

    private var startTimeMillis: Long = 0
    private var isSessionActive = false
    private var pausedAt: Long? = null
    private var pausedMillis = 0L
    private var endedDurationMillis = 0L

    fun startSession() {
        reps.clear()
        startTimeMillis = elapsedClock()
        isSessionActive = true
        pausedAt = null
        pausedMillis = 0L
        endedDurationMillis = 0L
    }

    fun endSession(): SessionSummary {
        endedDurationMillis = elapsedMillis()
        isSessionActive = false
        return getSummary()
    }

    fun pauseSession() {
        if (isSessionActive && pausedAt == null) pausedAt = elapsedClock()
    }

    fun resumeSession() {
        pausedAt?.let { pausedMillis += elapsedClock() - it }
        pausedAt = null
    }

    private fun elapsedMillis(): Long = if (isSessionActive) {
        ((pausedAt ?: elapsedClock()) - startTimeMillis - pausedMillis).coerceAtLeast(0)
    } else endedDurationMillis

    /**
     * Records one completed repetition and returns the created [RepRecord].
     * [repNumber] is assigned as the next 1-based index. No-op (returns null)
     * when no session is active.
     */
    fun recordRep(
        exerciseType: ExerciseType,
        isCorrect: Boolean,
        errors: Set<RepError>,
        debugMetrics: RepDebugMetrics
    ): RepRecord? {
        if (!isSessionActive || pausedAt != null) return null

        val record = RepRecord(
            repNumber = reps.size + 1,
            exerciseType = exerciseType,
            isCorrect = isCorrect,
            errors = errors.toList(),
            elapsedSessionMs = elapsedMillis(),
            debugMetrics = debugMetrics
        )
        reps.add(record)
        return record
    }

    /** Ordered (completion-order) view of every rep recorded this session. */
    fun getRepRecords(): List<RepRecord> = reps.toList()

    fun getElapsedTimeSeconds(): Long {
        return elapsedMillis() / 1000
    }

    fun getSummary(): SessionSummary {
        val duration = getElapsedTimeSeconds()
        return SessionSummary(getTotalReps(), getCorrectReps(), getIncorrectReps(), duration)
    }

    fun getCorrectReps() = reps.count { it.isCorrect }
    fun getIncorrectReps() = reps.count { !it.isCorrect }
    fun getTotalReps() = reps.size

    data class SessionSummary(
        val totalReps: Int,
        val correctReps: Int,
        val incorrectReps: Int,
        val durationSeconds: Long
    )
}
