package com.example.fitvisor__demo

/**
 * Tiny in-process handoff for the finished session's per-rep records.
 *
 * The rep list (with its debug metrics and optional frame traces) can be large
 * and awkward to pass through an Intent, so [WorkoutActivity] parks it here right
 * before launching [SummaryActivity], which reads it back. This is deliberately
 * session-level only: it is not persisted and does not survive process death,
 * which is no worse than the app's existing behavior (the summary is always
 * opened from the live, in-process workout). The scalar totals are still passed
 * as Intent extras, so the numeric summary works even if this holder is empty.
 */
object SessionResultsHolder {

    /** Records of the most recently summarized session, in completion order. */
    var repRecords: List<RepRecord> = emptyList()
        private set

    fun set(records: List<RepRecord>) {
        repRecords = records.toList()
    }

    fun clear() {
        repRecords = emptyList()
    }
}
