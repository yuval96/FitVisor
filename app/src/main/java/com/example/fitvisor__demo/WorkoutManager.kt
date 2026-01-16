package com.example.fitvisor__demo

import android.os.SystemClock

/**
 * Workout Manager Module.
 * Tracks session totals: total reps, correct reps, incorrect reps, and elapsed time.
 */
class WorkoutManager {

    private var totalReps = 0
    private var correctReps = 0
    private var incorrectReps = 0
    private var startTimeMillis: Long = 0
    private var isSessionActive = false

    fun startSession() {
        totalReps = 0
        correctReps = 0
        incorrectReps = 0
        startTimeMillis = SystemClock.elapsedRealtime()
        isSessionActive = true
    }

    fun endSession(): SessionSummary {
        isSessionActive = false
        return getSummary()
    }

    fun addRep(isCorrect: Boolean) {
        if (!isSessionActive) return
        totalReps++
        if (isCorrect) {
            correctReps++
        } else {
            incorrectReps++
        }
    }

    fun getElapsedTimeSeconds(): Long {
        if (!isSessionActive) return 0
        return (SystemClock.elapsedRealtime() - startTimeMillis) / 1000
    }

    fun getSummary(): SessionSummary {
        val duration = if (isSessionActive) getElapsedTimeSeconds() else 0 // or store end time
        return SessionSummary(totalReps, correctReps, incorrectReps, duration)
    }

    fun getCorrectReps() = correctReps
    fun getIncorrectReps() = incorrectReps
    fun getTotalReps() = totalReps

    data class SessionSummary(
        val totalReps: Int,
        val correctReps: Int,
        val incorrectReps: Int,
        val durationSeconds: Long
    )
}
