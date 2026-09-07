package com.example.fitvisor__demo

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.example.fitvisor__demo.databinding.ActivitySummaryBinding
import java.util.Locale
import java.text.DateFormat
import java.util.Date

/**
 * Completed workout summary. Counts and details come from the same snapshot,
 * with an ordered section for every exercise session (including repeated types).
 */
class SummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySummaryBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BrandingInsets.applyNavySystemBars(this)
        BrandingInsets.padForSystemBars(binding.summaryRoot)

        val workout = SessionResultsHolder.get(intent.getStringExtra(EXTRA_WORKOUT_ID))
        if (workout == null) {
            Toast.makeText(this, R.string.workout_unavailable, Toast.LENGTH_LONG).show()
            goHome()
            return
        }
        val total = workout.totalReps
        val correct = workout.correctReps
        val incorrect = workout.incorrectReps
        val duration = workout.durationSeconds

        binding.exerciseNameText.text = getString(R.string.workout_exercise_count, workout.exercises.size)
        binding.workoutDateText.text = formatTimeRange(workout.startTimeMillis, workout.endTimeMillis!!)

        bindStats(total, correct, incorrect, duration)
        bindFormScore(total, correct)
        bindFeedback(total, correct, incorrect)
        bindExercises(workout.exercises)
        RepTrackingPanel.bind(binding.repTrackingPanel.root)

        // This workout is already finished; no summary action can resume it.
        binding.backButton.setOnClickListener { goHome() }
        binding.backHomeButton.setOnClickListener { goHome() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })
    }

    private fun bindExercises(exercises: List<ExerciseSession>) {
        val container = binding.repDetailsContainer
        container.removeAllViews()
        if (exercises.isEmpty()) {
            container.addView(TextView(this).apply {
                setText(R.string.summary_no_exercises)
                setTextColor(color(R.color.text_secondary))
            })
        }
        exercises.forEachIndexed { index, exercise ->
            val section = layoutInflater.inflate(R.layout.view_exercise_summary, container, false)
            section.findViewById<TextView>(R.id.exerciseSessionTitle).text =
                getString(R.string.exercise_summary_title, index + 1, exercise.exerciseType.displayName)
            section.findViewById<TextView>(R.id.exerciseSessionTimes).text =
                formatTimeRange(exercise.startTimeMillis, exercise.endTimeMillis!!)
            section.findViewById<TextView>(R.id.exerciseSessionTotals).text =
                getString(R.string.exercise_summary_totals, exercise.reps.size,
                    exercise.correctReps, exercise.incorrectReps, formatDuration(exercise.durationSeconds))
            bindRepDetails(exercise.reps, section.findViewById(R.id.exerciseSessionReps))
            container.addView(section)
        }
    }

    private fun formatTimeRange(start: Long, end: Long): String {
        val formatter = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        return getString(R.string.workout_time_range, formatter.format(Date(start)), formatter.format(Date(end)))
    }

    private fun bindStats(total: Int, correct: Int, incorrect: Int, duration: Long) {
        binding.statTotal.setValue(total.toString())
        binding.statCorrect.setValue(correct.toString())
        binding.statIncorrect.setValue(incorrect.toString())
        binding.statDuration.setValue(formatDuration(duration))
    }

    private fun bindFormScore(total: Int, correct: Int) {
        if (total <= 0) {
            binding.scoreValue.text = getString(R.string.summary_score_na)
            val neutral = color(R.color.text_secondary)
            binding.scoreValue.setTextColor(neutral)
            binding.formScoreRing.setProgress(0f)
            binding.formScoreRing.setProgressColor(color(R.color.bg_elevated))
            return
        }

        val score = Math.round(correct * 100f / total)
        val scoreColor = when {
            score >= STRONG_SCORE -> color(R.color.feedback_success)
            score >= MEDIUM_SCORE -> color(R.color.feedback_warning)
            else -> color(R.color.feedback_error)
        }
        binding.scoreValue.text = getString(R.string.summary_score_percent, score)
        binding.scoreValue.setTextColor(scoreColor)
        binding.formScoreRing.setProgressColor(scoreColor)
        binding.formScoreRing.setProgress(score.toFloat())
    }

    private fun bindFeedback(total: Int, correct: Int, incorrect: Int) {
        val iconRes: Int
        val colorRes: Int
        val messageRes: Int
        when {
            total <= 0 -> {
                iconRes = R.drawable.ic_warning
                colorRes = R.color.feedback_warning
                messageRes = R.string.feedback_none
            }
            incorrect == 0 -> {
                iconRes = R.drawable.ic_check_circle
                colorRes = R.color.feedback_success
                messageRes = R.string.feedback_all_correct
            }
            correct == 0 -> {
                iconRes = R.drawable.ic_error_circle
                colorRes = R.color.feedback_error
                messageRes = R.string.feedback_all_incorrect
            }
            correct >= incorrect -> {
                iconRes = R.drawable.ic_warning
                colorRes = R.color.feedback_warning
                messageRes = R.string.feedback_mostly_correct
            }
            else -> {
                iconRes = R.drawable.ic_error_circle
                colorRes = R.color.feedback_error
                messageRes = R.string.feedback_mostly_incorrect
            }
        }
        binding.feedbackIcon.setImageResource(iconRes)
        binding.feedbackText.setText(messageRes)
        binding.feedbackText.setTextColor(color(colorRes))
    }

    /**
     * Renders one card per completed repetition into the scrollable container.
     * A plain [android.widget.LinearLayout] (rather than a RecyclerView) is used
     * because the whole summary already lives in a ScrollView and the rep count
     * per session is modest; this keeps the code simple and avoids nested
     * scrolling. Debug angle metrics are shown only when
     * [AppSettings.debugEnabled] is enabled.
     */
    private fun bindRepDetails(records: List<RepRecord>, container: LinearLayout) {

        if (records.isEmpty()) {
            val empty = TextView(this).apply {
                setText(R.string.summary_rep_none)
                setTextColor(color(R.color.text_secondary))
                textSize = 14f
            }
            container.addView(empty)
            return
        }

        val inflater = LayoutInflater.from(this)
        for (record in records) {
            val item = inflater.inflate(R.layout.view_rep_detail, container, false)

            item.findViewById<TextView>(R.id.repNumberText).text =
                getString(R.string.summary_rep_number, record.repNumber)

            val status = item.findViewById<TextView>(R.id.repStatusText)
            if (record.isCorrect) {
                status.setText(R.string.summary_rep_correct)
                status.setTextColor(color(R.color.feedback_success))
            } else {
                status.setText(R.string.summary_rep_incorrect)
                status.setTextColor(color(R.color.feedback_error))
            }

            val errorsView = item.findViewById<TextView>(R.id.repErrorsText)
            if (record.errors.isEmpty()) {
                errorsView.visibility = View.GONE
            } else {
                errorsView.text = record.errors.joinToString("\n") { it.message }
                errorsView.visibility = View.VISIBLE
            }

            val debugView = item.findViewById<TextView>(R.id.repDebugText)
            if (AppSettings(this).debugEnabled &&
                record.debugMetrics.values.isNotEmpty()
            ) {
                debugView.text = formatDebugMetrics(record.debugMetrics)
                debugView.visibility = View.VISIBLE
            } else {
                debugView.visibility = View.GONE
            }

            container.addView(item)
        }
    }

    private fun formatDebugMetrics(metrics: RepDebugMetrics): String {
        val builder = StringBuilder(getString(R.string.summary_rep_debug_header))
        for ((label, value) in metrics.values) {
            builder.append('\n')
                .append(prettifyLabel(label))
                .append(": ")
                .append(if (label == "startDetected" || label == "topReached")
                    String.format(Locale.US, "%.0f", value) else formatAngle(value))
        }
        return builder.toString()
    }

    private fun formatAngle(value: Double): String =
        if (value.isNaN()) "--" else String.format(Locale.US, "%.1f°", value)

    /** "minKneeAngle" -> "Min knee angle" for readable debug labels. */
    private fun prettifyLabel(key: String): String {
        val spaced = key.replace(Regex("([a-z0-9])([A-Z])"), "$1 $2").lowercase(Locale.US)
        return spaced.replaceFirstChar { it.uppercase(Locale.US) }
    }

    private fun goHome() {
        val intent = Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        startActivity(intent)
        finish()
    }

    private fun formatDuration(seconds: Long): String =
        if (seconds >= 60) "${seconds / 60}m ${seconds % 60}s" else "${seconds}s"

    private fun color(resId: Int) = ContextCompat.getColor(this, resId)

    companion object {
        const val EXTRA_WORKOUT_ID = "SUMMARY_WORKOUT_ID"

        private const val STRONG_SCORE = 80
        private const val MEDIUM_SCORE = 50
    }
}
