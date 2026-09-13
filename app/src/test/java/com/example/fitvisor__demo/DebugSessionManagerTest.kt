package com.example.fitvisor__demo

import com.example.fitvisor__demo.debug.DebugSessionManager
import com.example.fitvisor__demo.debug.DebugSessionState
import com.example.fitvisor__demo.debug.DebugSessionTransition
import org.junit.Assert.assertEquals
import org.junit.Test

class DebugSessionManagerTest {
    private var nowMillis = 0L
    private val manager = DebugSessionManager(clockMillis = { nowMillis })

    @Test
    fun preparationLastsFiveSecondsAndDoesNotCountAsMeasurementTime() {
        assertEquals(DebugSessionState.PREPARING, manager.start().state)
        assertEquals(5, manager.update().preparationSecondsRemaining)

        nowMillis = 4_999L
        assertEquals(DebugSessionState.PREPARING, manager.update().state)
        assertEquals(1, manager.update().preparationSecondsRemaining)

        nowMillis = 5_000L
        val started = manager.update()
        assertEquals(DebugSessionTransition.MEASUREMENT_STARTED, started.transition)
        assertEquals(DebugSessionState.MEASURING, started.state)
        assertEquals(0L, started.measurementElapsedMillis)
    }

    @Test
    fun preparationRepetitionsAreIgnored() {
        manager.start()
        repeat(10) { manager.recordCompletedRepetition() }

        nowMillis = 5_000L
        val started = manager.update()
        assertEquals(0, started.completedRepetitions)
    }

    @Test
    fun twentySecondsKeepsMeasuringRegardlessOfRepetitionCount() {
        startMeasurement()
        repeat(9) { manager.recordCompletedRepetition() }
        nowMillis = 25_000L

        val update = manager.update()

        assertEquals(DebugSessionState.MEASURING, update.state)
        assertEquals(9, update.completedRepetitions)
    }

    @Test
    fun thirtySecondsWithoutRepetitionsCompletesExactlyOnce() {
        startMeasurement()
        nowMillis = 35_000L

        val completed = manager.update()
        assertEquals(DebugSessionState.COMPLETED, completed.state)
        assertEquals(DebugSessionTransition.MEASUREMENT_COMPLETED, completed.transition)
        assertEquals(0, completed.completedRepetitions)
        assertEquals(30_000L, completed.measurementElapsedMillis)

        assertEquals(DebugSessionTransition.NONE, manager.update().transition)
        assertEquals(0, manager.recordCompletedRepetition().completedRepetitions)
    }

    @Test
    fun repetitionsDoNotEndMeasurementBeforeThirtySeconds() {
        startMeasurement()
        nowMillis = 34_999L
        repeat(10) { manager.recordCompletedRepetition() }
        assertEquals(DebugSessionState.MEASURING, manager.state)
        assertEquals(10, manager.update().completedRepetitions)

        nowMillis = 35_000L
        assertEquals(DebugSessionTransition.MEASUREMENT_COMPLETED, manager.update().transition)
    }

    @Test
    fun resetClearsTimersAndRepetitionCount() {
        startMeasurement()
        repeat(4) { manager.recordCompletedRepetition() }

        val reset = manager.reset()

        assertEquals(DebugSessionState.IDLE, reset.state)
        assertEquals(0L, reset.measurementElapsedMillis)
        assertEquals(0, reset.completedRepetitions)
    }

    private fun startMeasurement() {
        manager.start()
        nowMillis = 5_000L
        manager.update()
    }
}
