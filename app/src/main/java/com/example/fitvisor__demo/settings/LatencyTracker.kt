package com.example.fitvisor__demo.settings

import java.util.Locale
import kotlin.math.ceil

data class PerformanceMetricsSnapshot(
    val model: String,
    val delegate: String?,
    val configuredUseGpu: Boolean,
    val inputWidth: Int?,
    val inputHeight: Int?,
    val cameraFps: Double?,
    val analysisFps: Double?,
    val currentLatencyMs: Double?,
    val averageLatencyMs: Double?
)

/**
 * Bounded performance measurements using one monotonic clock, never camera
 * sensor time. The existing timestamp-matched latency instrumentation is also
 * the owner of the two lightweight FPS windows so the app has one metrics path.
 */
class LatencyTracker(
    private val capacity: Int = 600,
    private val fpsWindowNanos: Long = FPS_WINDOW_NANOS,
    private val minimumFpsSpanNanos: Long = MINIMUM_FPS_SPAN_NANOS
) {
    data class Frame(val start: Long, val submitted: Long)
    data class Sample(val preparation: Double, val model: Double, val ui: Double, val total: Double)
    private val pending = linkedMapOf<Long, Frame>()
    private val samples = ArrayDeque<Sample>()
    private val cameraFrames = TimestampWindow(FPS_TIMESTAMP_CAPACITY)
    private val analysisResults = TimestampWindow(FPS_TIMESTAMP_CAPACITY)
    private var inputWidth: Int? = null
    private var inputHeight: Int? = null

    /** Records an ImageProxy as soon as ImageAnalysis delivers it. */
    @Synchronized fun cameraFrame(timestampNanos: Long, width: Int, height: Int) {
        cameraFrames.add(timestampNanos)
        inputWidth = width
        inputHeight = height
    }

    /** Records a completed MediaPipe LIVE_STREAM result on its callback thread. */
    @Synchronized fun analysisResult(timestampNanos: Long) {
        analysisResults.add(timestampNanos)
    }

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

    @Synchronized fun reset() {
        pending.clear()
        samples.clear()
        cameraFrames.clear()
        analysisResults.clear()
        inputWidth = null
        inputHeight = null
    }

    @Synchronized fun snapshot(
        model: String,
        delegate: String?,
        configuredUseGpu: Boolean,
        timestampNanos: Long
    ): PerformanceMetricsSnapshot = PerformanceMetricsSnapshot(
        model = model,
        delegate = delegate,
        configuredUseGpu = configuredUseGpu,
        inputWidth = inputWidth,
        inputHeight = inputHeight,
        cameraFps = cameraFrames.fps(timestampNanos, fpsWindowNanos, minimumFpsSpanNanos),
        analysisFps = analysisResults.fps(timestampNanos, fpsWindowNanos, minimumFpsSpanNanos),
        currentLatencyMs = samples.lastOrNull()?.total,
        averageLatencyMs = samples.takeIf { it.isNotEmpty() }?.let { values ->
            values.sumOf { it.total } / values.size
        }
    )

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

    /** Fixed ring buffer: recording a frame does not allocate per-frame objects. */
    private class TimestampWindow(capacity: Int) {
        private val timestamps = LongArray(capacity)
        private var start = 0
        private var size = 0

        fun add(timestampNanos: Long) {
            if (size > 0 && timestampNanos <= timestampAt(size - 1)) return
            if (size < timestamps.size) {
                timestamps[(start + size) % timestamps.size] = timestampNanos
                size++
            } else {
                timestamps[start] = timestampNanos
                start = (start + 1) % timestamps.size
            }
        }

        fun fps(nowNanos: Long, windowNanos: Long, minimumSpanNanos: Long): Double? {
            val cutoff = nowNanos - windowNanos
            while (size > 0 && timestampAt(0) < cutoff) {
                start = (start + 1) % timestamps.size
                size--
            }
            if (size < 2) return null
            val span = timestampAt(size - 1) - timestampAt(0)
            if (span < minimumSpanNanos) return null
            return (size - 1) * NANOS_PER_SECOND / span
        }

        fun clear() {
            start = 0
            size = 0
        }

        private fun timestampAt(index: Int): Long =
            timestamps[(start + index) % timestamps.size]
    }

    companion object {
        const val FPS_WINDOW_SECONDS = 3
        private const val FPS_WINDOW_NANOS = FPS_WINDOW_SECONDS * 1_000_000_000L
        private const val MINIMUM_FPS_SPAN_NANOS = 2_000_000_000L
        private const val FPS_TIMESTAMP_CAPACITY = 512
        private const val NANOS_PER_SECOND = 1_000_000_000.0
    }
}
