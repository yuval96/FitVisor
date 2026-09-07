package com.example.fitvisor__demo

import java.util.Locale
import kotlin.math.ceil

/** Bounded measurements using one monotonic clock, never camera sensor time. */
class LatencyTracker(private val capacity: Int = 600) {
    data class Frame(val start: Long, val submitted: Long)
    data class Sample(val preparation: Double, val model: Double, val ui: Double, val total: Double)
    private val pending = linkedMapOf<Long, Frame>()
    private val samples = ArrayDeque<Sample>()

    @Synchronized fun submit(id: Long, start: Long, submitted: Long) {
        pending[id] = Frame(start, submitted)
        while (pending.size > 120) pending.remove(pending.keys.first())
    }

    @Synchronized fun complete(id: Long, callback: Long, updated: Long) {
        val frame = pending.remove(id) ?: return
        // LIVE_STREAM may drop submitted frames. Do not report them as completed.
        pending.keys.removeAll { it < id }
        if (frame.start > frame.submitted || frame.submitted > callback || callback > updated) return
        samples.addLast(Sample(
            (frame.submitted - frame.start) / 1e6,
            (callback - frame.submitted) / 1e6,
            (updated - callback) / 1e6,
            (updated - frame.start) / 1e6
        ))
        while (samples.size > capacity) samples.removeFirst()
    }

    @Synchronized fun clearPending() { pending.clear() }

    @Synchronized fun report(): String {
        if (samples.isEmpty()) return "No completed frame measurements yet."
        fun line(name: String, values: List<Double>): String {
            val sorted = values.sorted()
            val p95 = sorted[(ceil(sorted.size * .95).toInt() - 1).coerceAtLeast(0)]
            return String.format(Locale.US, "%s: avg %.1f · P95 %.1f · max %.1f ms", name, values.average(), p95, sorted.last())
        }
        return "${samples.size} completed frames (latest $capacity maximum)\n" + listOf(
            line("Image preparation", samples.map { it.preparation }),
            line("Model callback", samples.map { it.model }),
            line("UI queue + analysis", samples.map { it.ui }),
            line("Total to UI update", samples.map { it.total })
        ).joinToString("\n")
    }
}
