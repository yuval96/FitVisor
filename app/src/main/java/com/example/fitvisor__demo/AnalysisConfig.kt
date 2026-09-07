package com.example.fitvisor__demo

/**
 * Central, easily-tuned configuration for the analysis pipeline.
 *
 * Values that we expect to iterate on during real-device testing live here in a
 * single place, so a change is a one-line edit rather than a hunt through the
 * activity/analyzer/engine code.
 */
object AnalysisConfig {

    /**
     * Exponential-moving-average factor for [LandmarkSmoother].
     *
     *   alpha = 1.0   -> no smoothing (raw landmarks, no added latency)
     *   alpha -> 1.0  -> less smoothing / less latency (more responsive, more jitter)
     *   alpha lower   -> stronger smoothing / more lag (smoother, more delayed)
     *
     * Set to 1.0 (no smoothing, raw landmarks) to eliminate the input lag
     * reported on-device. To A/B test, change only this constant (e.g. try
     * 1.0, 0.9, 0.8).
     */
    const val LANDMARK_SMOOTHING_ALPHA = 1.0f

    /**
     * When true, each rule engine records a per-frame trace of phase + metrics
     * for the current repetition (see [RepFrameSample]). Debug-only and capped
     * by [MAX_REP_FRAME_SAMPLES]; it is never shown in the workout summary.
     *
     * MUST default to false so normal sessions do nothing extra.
     */
    const val ENABLE_REP_FRAME_TRACE = false

    /** Hard cap on frame samples kept per repetition when the trace is enabled. */
    const val MAX_REP_FRAME_SAMPLES = 300

    // User-facing diagnostics are controlled by AppSettings.debugEnabled.
}
