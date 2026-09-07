package com.example.fitvisor__demo

/**
 * Exercise-agnostic payload the [OverlayView] renders.
 *
 * [values] is an ordered map of label -> angle (in degrees) so every exercise
 * can show its own set of metrics without the overlay being hardcoded for a
 * specific one. Use a [LinkedHashMap] (or `linkedMapOf`) to preserve order.
 */
data class OverlayMetrics(
    val values: Map<String, Double>,
    val warning: String?,
    val phase: String?
) {
    companion object {
        val EMPTY = OverlayMetrics(emptyMap(), null, null)
    }
}
