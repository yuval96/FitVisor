package com.example.fitvisor__demo.debug

import android.os.SystemClock
import kotlin.math.ceil

enum class DebugSessionState {
    IDLE,
    PREPARING,
    MEASURING,
    COMPLETED
}

enum class DebugSessionTransition {
    NONE,
    MEASUREMENT_STARTED,
    MEASUREMENT_COMPLETED
}

data class DebugSessionUpdate(
    val state: DebugSessionState,
    val transition: DebugSessionTransition = DebugSessionTransition.NONE,
    val preparationSecondsRemaining: Int? = null,
    val measurementElapsedMillis: Long = 0L,
    val completedRepetitions: Int = 0
)

/**
 * Owns the timing for one automated Debug Mode measurement. Every measurement
 * runs for the full configured duration. Exercise analyzers remain the source
 * of completed-repetition events; repetitions are counted for reporting only
 * and never end or extend the measurement.
 */
class DebugSessionManager(
    private val clockMillis: () -> Long = { SystemClock.elapsedRealtime() },
    private val preparationDurationMillis: Long = DEFAULT_PREPARATION_DURATION_MILLIS,
    private val measurementDurationMillis: Long = DEFAULT_MEASUREMENT_DURATION_MILLIS
) {
    var state: DebugSessionState = DebugSessionState.IDLE
        private set

    private var preparationStartedAtMillis = 0L
    private var measurementStartedAtMillis = 0L
    private var measurementCompletedAtMillis = 0L
    private var completedRepetitions = 0

    init {
        require(preparationDurationMillis >= 0L)
        require(measurementDurationMillis >= 0L)
    }

    @Synchronized
    fun start(): DebugSessionUpdate {
        preparationStartedAtMillis = clockMillis()
        measurementStartedAtMillis = 0L
        measurementCompletedAtMillis = 0L
        completedRepetitions = 0
        state = DebugSessionState.PREPARING
        return snapshot(preparationStartedAtMillis)
    }

    /** Advances time-based transitions and returns a one-shot transition value. */
    @Synchronized
    fun update(): DebugSessionUpdate {
        val now = clockMillis()
        return when (state) {
            DebugSessionState.PREPARING -> {
                if (now - preparationStartedAtMillis >= preparationDurationMillis) {
                    measurementStartedAtMillis = now
                    state = DebugSessionState.MEASURING
                    snapshot(now, DebugSessionTransition.MEASUREMENT_STARTED)
                } else {
                    snapshot(now)
                }
            }

            DebugSessionState.MEASURING -> completeIfReady(now)
            DebugSessionState.IDLE,
            DebugSessionState.COMPLETED -> snapshot(now)
        }
    }

    /**
     * Counts one correct or incorrect completed repetition. Calls made before
     * measurement starts or after it completes are deliberately ignored.
     */
    @Synchronized
    fun recordCompletedRepetition(): DebugSessionUpdate {
        val now = clockMillis()
        if (state != DebugSessionState.MEASURING) return snapshot(now)
        completedRepetitions++
        return completeIfReady(now)
    }

    @Synchronized
    fun reset(): DebugSessionUpdate {
        state = DebugSessionState.IDLE
        preparationStartedAtMillis = 0L
        measurementStartedAtMillis = 0L
        measurementCompletedAtMillis = 0L
        completedRepetitions = 0
        return snapshot(clockMillis())
    }

    private fun completeIfReady(now: Long): DebugSessionUpdate {
        val elapsed = (now - measurementStartedAtMillis).coerceAtLeast(0L)
        return if (elapsed >= measurementDurationMillis) {
            measurementCompletedAtMillis = now
            state = DebugSessionState.COMPLETED
            snapshot(now, DebugSessionTransition.MEASUREMENT_COMPLETED)
        } else {
            snapshot(now)
        }
    }

    private fun snapshot(
        now: Long,
        transition: DebugSessionTransition = DebugSessionTransition.NONE
    ): DebugSessionUpdate {
        val preparationSecondsRemaining = if (state == DebugSessionState.PREPARING) {
            val remainingMillis =
                (preparationDurationMillis - (now - preparationStartedAtMillis)).coerceAtLeast(0L)
            ceil(remainingMillis / MILLIS_PER_SECOND.toDouble()).toInt().coerceAtLeast(1)
        } else {
            null
        }
        val measurementElapsedMillis = when (state) {
            DebugSessionState.MEASURING ->
                (now - measurementStartedAtMillis).coerceAtLeast(0L)
            DebugSessionState.COMPLETED ->
                (measurementCompletedAtMillis - measurementStartedAtMillis).coerceAtLeast(0L)
            DebugSessionState.IDLE,
            DebugSessionState.PREPARING -> 0L
        }
        return DebugSessionUpdate(
            state = state,
            transition = transition,
            preparationSecondsRemaining = preparationSecondsRemaining,
            measurementElapsedMillis = measurementElapsedMillis,
            completedRepetitions = completedRepetitions
        )
    }

    companion object {
        const val DEFAULT_PREPARATION_DURATION_MILLIS = 5_000L
        const val DEFAULT_MEASUREMENT_DURATION_MILLIS = 30_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
