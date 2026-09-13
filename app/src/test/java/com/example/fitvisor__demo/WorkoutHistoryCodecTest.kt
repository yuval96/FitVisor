package com.example.fitvisor__demo

import com.example.fitvisor__demo.model.RepError
import com.example.fitvisor__demo.workout.WorkoutHistoryCodec
import org.junit.Assert.*
import org.junit.Test

class WorkoutHistoryCodecTest {

    @Test fun errorsRoundTripInOrder() {
        val errors = listOf(RepError.INSUFFICIENT_DEPTH, RepError.EXCESSIVE_TORSO_LEAN)
        val encoded = WorkoutHistoryCodec.encodeErrors(errors)
        assertEquals(errors, WorkoutHistoryCodec.decodeErrors(encoded))
    }

    @Test fun emptyErrorsRoundTripToEmptyList() {
        assertEquals(emptyList<RepError>(), WorkoutHistoryCodec.decodeErrors(WorkoutHistoryCodec.encodeErrors(emptyList())))
    }

    @Test fun temporaryTorsoErrorNamesRemainReadable() {
        assertEquals(
            listOf(RepError.EXCESSIVE_TORSO_LEAN, RepError.INSUFFICIENT_TORSO_LEAN),
            WorkoutHistoryCodec.decodeErrors("EXCESSIVE_TORSO_LEAN_FWD,EXCESSIVE_TORSO_LEAN_BWD")
        )
    }

    @Test fun metricsRoundTripInOrderWithFullPrecision() {
        val values = linkedMapOf("minKneeAngle" to 92.5, "maxTorsoAngle" to 12.333333)
        val decoded = WorkoutHistoryCodec.decodeMetrics(WorkoutHistoryCodec.encodeMetrics(values))
        assertEquals(values.keys.toList(), decoded.keys.toList())
        values.forEach { (key, value) -> assertEquals(value, decoded[key]!!, 0.0) }
    }

    @Test fun nanMetricValueRoundTrips() {
        val values = mapOf("startDetected" to Double.NaN)
        val decoded = WorkoutHistoryCodec.decodeMetrics(WorkoutHistoryCodec.encodeMetrics(values))
        assertTrue(decoded["startDetected"]!!.isNaN())
    }

    @Test fun emptyMetricsRoundTripToEmptyMap() {
        assertEquals(emptyMap<String, Double>(), WorkoutHistoryCodec.decodeMetrics(WorkoutHistoryCodec.encodeMetrics(emptyMap())))
    }

    @Test fun flagsRoundTripInOrder() {
        val flags = linkedMapOf("startDetected" to true, "topReached" to false)
        val decoded = WorkoutHistoryCodec.decodeFlags(WorkoutHistoryCodec.encodeFlags(flags))
        assertEquals(flags, decoded)
        assertEquals(flags.keys.toList(), decoded.keys.toList())
    }

    @Test fun emptyFlagsRoundTripToEmptyMap() {
        assertEquals(emptyMap<String, Boolean>(), WorkoutHistoryCodec.decodeFlags(WorkoutHistoryCodec.encodeFlags(emptyMap())))
    }
}
