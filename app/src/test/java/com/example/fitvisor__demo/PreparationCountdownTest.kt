package com.example.fitvisor__demo

import com.example.fitvisor__demo.workout.PreparationCountdown
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationCountdownTest {

    private var now = 0L
    private val countdown = PreparationCountdown(clockMillis = { now }, durationMillis = 5_000L)

    @Test
    fun beforeStart_isNotPreparing() {
        assertFalse(countdown.isPreparing)
        assertNull(countdown.secondsRemaining)
        assertFalse(countdown.update())
    }

    @Test
    fun start_beginsPreparingWithCeiledSeconds() {
        countdown.start()
        assertTrue(countdown.isPreparing)
        assertEquals(5, countdown.secondsRemaining)
    }

    @Test
    fun update_ticksSecondsDownAndStaysPreparingUntilElapsed() {
        countdown.start()

        now = 1_200L
        assertFalse(countdown.update()) // 3.8s left -> ceil to 4
        assertEquals(4, countdown.secondsRemaining)
        assertTrue(countdown.isPreparing)

        now = 4_999L
        assertFalse(countdown.update()) // 1ms left -> ceil to 1
        assertEquals(1, countdown.secondsRemaining)
        assertTrue(countdown.isPreparing)
    }

    @Test
    fun update_returnsTrueExactlyOnTheElapsingCall() {
        countdown.start()

        now = 4_000L
        assertFalse(countdown.update())

        now = 5_000L
        assertTrue("must return true exactly once, on the elapsing call", countdown.update())
        assertFalse(countdown.isPreparing)
        assertNull(countdown.secondsRemaining)

        now = 5_500L
        assertFalse("must not fire again on later calls", countdown.update())
    }

    @Test
    fun restart_beginsANewCountdown() {
        countdown.start()
        now = 5_000L
        countdown.update()
        assertFalse(countdown.isPreparing)

        now = 10_000L
        countdown.start()
        assertTrue(countdown.isPreparing)
        assertEquals(5, countdown.secondsRemaining)
    }

    @Test
    fun zeroDuration_isNeverPreparing() {
        val instant = PreparationCountdown(clockMillis = { now }, durationMillis = 0L)
        instant.start()
        assertFalse(instant.isPreparing)
        assertNull(instant.secondsRemaining)
    }
}
