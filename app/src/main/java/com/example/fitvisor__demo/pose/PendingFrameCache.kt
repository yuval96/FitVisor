package com.example.fitvisor__demo.pose

import java.util.concurrent.ConcurrentHashMap

/**
 * Correlates an async result back to the exact input that produced it, keyed
 * by the timestamp used to submit it. Used to hand the pose-detection overlay
 * the precise camera frame [PoseLandmarkerHelper] analyzed, instead of trying
 * to reconstruct one from whatever the detector's result callback returns.
 *
 * Framework-independent (no Android/MediaPipe types) so the retention and
 * eviction logic can be unit tested directly. Thread-safe: entries are put on
 * the frame-submission thread and taken on the detector's callback thread.
 */
class PendingFrameCache<T>(private val windowMillis: Long) {

    private val entries = ConcurrentHashMap<Long, T>()

    /**
     * Stores [value] under [timestampMillis], then evicts any entry older than
     * [windowMillis] relative to it — bounding the cache when a submitted
     * entry's result never arrives (e.g. the detector errors out for it).
     */
    fun put(timestampMillis: Long, value: T) {
        entries[timestampMillis] = value
        entries.keys.removeAll { it < timestampMillis - windowMillis }
    }

    /** Removes and returns the entry for [timestampMillis], or null if absent/already taken. */
    fun take(timestampMillis: Long): T? = entries.remove(timestampMillis)

    fun remove(timestampMillis: Long) {
        entries.remove(timestampMillis)
    }

    fun clear() {
        entries.clear()
    }

    val size: Int get() = entries.size
}
