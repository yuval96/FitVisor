package com.example.fitvisor__demo

/**
 * One sampled frame of a repetition, captured only when
 * [AnalysisConfig.ENABLE_REP_FRAME_TRACE] is on. Debug-only.
 *
 * [frameIndex] is a per-repetition, 0-based counter used as the temporal axis.
 * A frame index (rather than a wall-clock millisecond value) is used on purpose:
 * the rule engines are intentionally free of Android/`SystemClock` dependencies
 * so they stay deterministic and unit-testable. A real elapsed-time stamp can be
 * layered on at the activity level later if needed.
 */
data class RepFrameSample(
    val frameIndex: Int,
    val phase: String,
    val metrics: Map<String, Double>
)

/**
 * Useful per-repetition measurements, recorded for every rep regardless of
 * [AppSettings.debugEnabled] so they're available in the workout summary and
 * persisted history — not just for on-device threshold tuning.
 *
 * [values] is an ordered label -> angle (degrees) map of aggregate measurements
 * for the repetition (e.g. "minKneeAngle", "maxTorsoAngle"). [flags] holds any
 * non-angle boolean state (e.g. "startDetected") kept separate so display code
 * never has to guess a label's unit from its name. [frameTrace] is the
 * optional, capped frame-by-frame trace; it's empty unless the trace is
 * explicitly enabled (a compile-time, debug-only switch) and is never rendered
 * in the normal summary.
 */
data class RepDebugMetrics(
    val values: Map<String, Double>,
    val frameTrace: List<RepFrameSample> = emptyList(),
    val flags: Map<String, Boolean> = emptyMap()
) {
    companion object {
        val EMPTY = RepDebugMetrics(emptyMap(), emptyList(), emptyMap())
    }
}

/** Running minimum that ignores NaN; a fully-NaN sequence stays NaN. */
internal fun minKeepNaN(current: Double, value: Double): Double = when {
    value.isNaN() -> current
    current.isNaN() -> value
    else -> minOf(current, value)
}

/** Running maximum that ignores NaN; a fully-NaN sequence stays NaN. */
internal fun maxKeepNaN(current: Double, value: Double): Double = when {
    value.isNaN() -> current
    current.isNaN() -> value
    else -> maxOf(current, value)
}

/**
 * Collects the optional, capped per-repetition frame trace. Recording is a
 * no-op (and the metrics lambda is never invoked) unless
 * [AnalysisConfig.ENABLE_REP_FRAME_TRACE] is on, so the common path costs a
 * single boolean check.
 */
internal class RepFrameRecorder {

    private val samples = mutableListOf<RepFrameSample>()
    private var index = 0

    fun record(phase: String, metrics: () -> Map<String, Double>) {
        if (!AnalysisConfig.ENABLE_REP_FRAME_TRACE) return
        if (samples.size >= AnalysisConfig.MAX_REP_FRAME_SAMPLES) return
        samples.add(RepFrameSample(index++, phase, metrics()))
    }

    fun snapshot(): List<RepFrameSample> = samples.toList()

    fun reset() {
        samples.clear()
        index = 0
    }
}
