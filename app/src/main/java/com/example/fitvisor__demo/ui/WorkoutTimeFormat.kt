package com.example.fitvisor__demo.ui

import android.content.Context
import com.example.fitvisor__demo.R
import java.text.DateFormat
import java.util.Date

/** Shared date/duration formatting for the workout summary and history list. */
object WorkoutTimeFormat {

    fun timeRange(context: Context, start: Long, end: Long): String {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return context.getString(R.string.workout_time_range, formatter.format(Date(start)), formatter.format(Date(end)))
    }

    /**
     * Same as [timeRange], but shows "in progress" instead of a finish time
     * when [end] is null — the interim summary shows this for a workout/
     * exercise that hasn't ended yet.
     */
    fun timeRangeOrInProgress(context: Context, start: Long, end: Long?): String =
        if (end != null) timeRange(context, start, end)
        else context.getString(R.string.workout_time_range_in_progress, date(start))

    fun date(start: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(start))

    fun duration(seconds: Long): String =
        if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"
}
