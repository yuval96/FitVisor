package com.example.fitvisor__demo.workout

import com.example.fitvisor__demo.model.RepError

/**
 * Plain-string encoding for the two small per-rep collections that don't need
 * their own tables: fault enums and the debug angle map. Pure functions (no
 * Room/Android dependency) so they're directly unit-testable.
 */
object WorkoutHistoryCodec {

    fun encodeErrors(errors: List<RepError>): String = errors.joinToString(",") { it.name }

    fun decodeErrors(raw: String): List<RepError> =
        if (raw.isEmpty()) emptyList()
        else raw.split(",").map {
            // Compatibility with the briefly-used names from the torso-range
            // implementation, as well as the original persisted enum name.
            when (it) {
                "EXCESSIVE_TORSO_LEAN_FWD" -> RepError.EXCESSIVE_TORSO_LEAN
                "EXCESSIVE_TORSO_LEAN_BWD" -> RepError.INSUFFICIENT_TORSO_LEAN
                else -> RepError.valueOf(it)
            }
        }

    fun encodeMetrics(values: Map<String, Double>): String =
        values.entries.joinToString(",") { (key, value) -> "$key=$value" }

    fun decodeMetrics(raw: String): Map<String, Double> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Double>()
        for (entry in raw.split(",")) {
            val separator = entry.indexOf('=')
            if (separator < 0) continue
            result[entry.substring(0, separator)] = entry.substring(separator + 1).toDouble()
        }
        return result
    }

    fun encodeFlags(flags: Map<String, Boolean>): String = flags.entries.joinToString(",") { (key, value) -> "$key=$value" }

    fun decodeFlags(raw: String): Map<String, Boolean> {
        if (raw.isEmpty()) return emptyMap()
        val result = LinkedHashMap<String, Boolean>()
        for (entry in raw.split(",")) {
            val separator = entry.indexOf('=')
            if (separator < 0) continue
            result[entry.substring(0, separator)] = entry.substring(separator + 1).toBoolean()
        }
        return result
    }
}
