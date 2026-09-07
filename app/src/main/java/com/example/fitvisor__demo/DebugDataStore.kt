package com.example.fitvisor__demo

import android.content.Context
import java.text.DateFormat
import java.util.Date

/** Local test totals are independent of the ordinary per-workout counter. */
class DebugDataStore(context: Context) {
    private val settings = AppSettings(context)
    private val prefs = context.applicationContext.getSharedPreferences("fitvisor_debug_data", Context.MODE_PRIVATE)

    fun recordRep(type: ExerciseType, correct: Boolean) {
        if (!settings.testRepsEnabled) return
        val key = "${type.name}_${if (correct) "correct" else "incorrect"}"
        val edit = prefs.edit().putLong(key, prefs.getLong(key, 0) + 1)
        if (!prefs.contains("since")) edit.putLong("since", System.currentTimeMillis())
        edit.apply()
    }

    fun repReport(): String = buildString {
        val since = prefs.getLong("since", 0)
        if (since > 0) append("Since ${DateFormat.getDateTimeInstance().format(Date(since))}\n\n")
        for (type in ExerciseType.values()) {
            val correct = prefs.getLong("${type.name}_correct", 0)
            val incorrect = prefs.getLong("${type.name}_incorrect", 0)
            append("${type.displayName}: ${correct + incorrect}\n  Correct: $correct · Incorrect: $incorrect\n")
        }
    }

    fun resetReps() {
        val edit = prefs.edit().putLong("since", System.currentTimeMillis())
        for (type in ExerciseType.values()) {
            edit.remove("${type.name}_correct").remove("${type.name}_incorrect")
        }
        edit.apply()
    }

    fun saveLatency(report: String) { prefs.edit().putString("latency_report", report).apply() }
    fun latencyReport(): String = prefs.getString("latency_report", null) ?: "No saved measurements. Enable tracking and start a workout."
    fun resetLatency() { prefs.edit().remove("latency_report").apply() }
}
