package com.example.fitvisor__demo.workout

import android.os.SystemClock
import kotlin.math.ceil

/**
 * Countdown before a workout starts scoring repetitions, giving the user time
 * to step into frame after pressing "start" (or resuming the app) -- without
 * it, movement while getting into position (walking away from the phone,
 * bending down to set it up) could be read as the start of an exercise and
 * counted as an incorrect repetition before the user has actually begun.
 *
 * Unlike [com.example.fitvisor__demo.debug.DebugSessionManager] (which this
 * mirrors for the debug-only measurement flow), there is no fixed
 * "measuring" duration here: once the countdown elapses, [isPreparing] simply
 * stays false until [start] is called again.
 */
class PreparationCountdown(
    private val clockMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val durationMillis: Long = DEFAULT_DURATION_MILLIS
) {
    private var startedAtMillis = 0L
    private var preparing = false

    init {
        require(durationMillis >= 0L)
    }

    /** True while the countdown is still running. */
    val isPreparing: Boolean get() = preparing

    /** Seconds remaining (ceil'd, at least 1), or null once not preparing. */
    var secondsRemaining: Int? = null
        private set

    /** Begins (or restarts) the countdown. */
    fun start() {
        startedAtMillis = clockMillis()
        preparing = durationMillis > 0L
        secondsRemaining = if (preparing) ceilSeconds(durationMillis) else null
    }

    /**
     * Advances the countdown; call once per tick (e.g. once per analyzed
     * frame). Returns true exactly on the call where the countdown elapses,
     * so the caller can react once (e.g. reset the exercise engine for a
     * clean start).
     */
    fun update(): Boolean {
        if (!preparing) return false
        val remaining = (durationMillis - (clockMillis() - startedAtMillis)).coerceAtLeast(0L)
        return if (remaining == 0L) {
            preparing = false
            secondsRemaining = null
            true
        } else {
            secondsRemaining = ceilSeconds(remaining)
            false
        }
    }

    private fun ceilSeconds(millis: Long): Int =
        ceil(millis / MILLIS_PER_SECOND.toDouble()).toInt().coerceAtLeast(1)

    companion object {
        const val DEFAULT_DURATION_MILLIS = 5_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
