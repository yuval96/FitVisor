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
import androidx.lifecycle.lifecycleScope
import com.example.fitvisor__demo.databinding.ActivitySummaryBinding
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Workout summary. Either the final, persisted record (loaded by ID from the
 * workout history database — reached from Home or History), or the *interim*
 * view of the still-active workout (reached from the workout screen's
 * Summary button, via [EXTRA_SHOW_ACTIVE_WORKOUT]) — same layout and binding
 * code either way, just a different data source and back target. Opening the
 * interim summary never touches [ActiveWorkoutStore]: it only reads the live
 * snapshot, so the active workout/exercise is untouched and resumes normally
 * (via WorkoutActivity's existing onPause/onResume) when the user returns.
 */
class SummaryActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySummaryBinding
    private lateinit var repository: WorkoutHistoryRepository

    /** True when showing the still-active workout rather than a finished one. */
    private var isInterim = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySummaryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = WorkoutHistoryRepository.getInstance(applicationContext)

        BrandingInsets.applyNavySystemBars(this)
        BrandingInsets.padForSystemBars(binding.summaryRoot)

        binding.backButton.setOnClickListener { goBack() }
        binding.backHomeButton.setOnClickListener { goBack() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        if (intent.getBooleanExtra(EXTRA_SHOW_ACTIVE_WORKOUT, false)) {
            isInterim = true
            val workout = ActiveWorkoutStore.manager.currentWorkout
            if (workout == null) {
                // Nothing active to show (e.g. reached this screen stale);
                // there is no in-progress workout to go back to either.
                isInterim = false
                Toast.makeText(this, R.string.workout_unavailable, Toast.LENGTH_LONG).show()
                goHome()
                return
            }
            applyInterimChrome()
            bind(workout)
            return
        }

        val workoutId = intent.getStringExtra(EXTRA_WORKOUT_ID)
        lifecycleScope.launch {
            val workout = workoutId?.let { repository.getWorkout(it) }
            if (workout == null) {
                Toast.makeText(this@SummaryActivity, R.string.workout_unavailable, Toast.LENGTH_LONG).show()
                goHome()
                return@launch
            }
            bind(workout)
        }
    }

    /** Swaps in "in progress" wording for the interim summary's title/back action. */
    private fun applyInterimChrome() {
        binding.summaryTitle.text = getString(R.string.summary_title_in_progress)
        binding.backHomeButton.text = getString(R.string.summary_back_to_workout)
    }

    /** Interim: just closes, returning to the still-alive WorkoutActivity. Final: goes Home. */
    private fun goBack() {
        if (isInterim) finish() else goHome()
    }

    private fun bind(workout: WorkoutSession) {
        val total = workout.totalReps
        val correct = workout.correctReps
        val incorrect = workout.incorrectReps
        val duration = workout.durationSeconds

        binding.exerciseNameText.text = getString(R.string.workout_exercise_count, workout.exercises.size)
        binding.workoutDateText.text = WorkoutTimeFormat.timeRangeOrInProgress(this, workout.startTimeMillis, workout.endTimeMillis)

        bindStats(total, correct, incorrect, duration)
        bindFormScore(total, correct)
        bindFeedback(total, correct, incorrect)
        bindExercises(workout.exercises)
        RepTrackingPanel.bind(binding.repTrackingPanel.root)
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
                WorkoutTimeFormat.timeRangeOrInProgress(this, exercise.startTimeMillis, exercise.endTimeMillis)
            section.findViewById<TextView>(R.id.exerciseSessionTotals).text =
                getString(R.string.exercise_summary_totals, exercise.reps.size,
                    exercise.correctReps, exercise.incorrectReps, WorkoutTimeFormat.duration(exercise.durationSeconds))
            bindRepDetails(exercise.reps, section.findViewById(R.id.exerciseSessionReps))
            container.addView(section)
        }
    }

    private fun bindStats(total: Int, correct: Int, incorrect: Int, duration: Long) {
        binding.statTotal.setValue(total.toString())
        binding.statCorrect.setValue(correct.toString())
        binding.statIncorrect.setValue(incorrect.toString())
        binding.statDuration.setValue(WorkoutTimeFormat.duration(duration))
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
            val debugMetrics = record.debugMetrics
            if (AppSettings(this).debugEnabled &&
                (debugMetrics.values.isNotEmpty() || debugMetrics.flags.isNotEmpty() || debugMetrics.ratios.isNotEmpty())
            ) {
                debugView.text = formatDebugMetrics(debugMetrics)
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
            builder.append('\n').append(prettifyLabel(label)).append(": ").append(formatAngle(value))
        }
        for ((label, value) in metrics.flags) {
            builder.append('\n').append(prettifyLabel(label)).append(": ")
                .append(if (value) getString(R.string.summary_rep_debug_yes) else getString(R.string.summary_rep_debug_no))
        }
        for ((label, value) in metrics.ratios) {
            builder.append('\n').append(prettifyLabel(label)).append(": ").append(formatRatio(value))
        }
        return builder.toString()
    }

    private fun formatAngle(value: Double): String =
        if (value.isNaN()) "--" else String.format(Locale.US, "%.1f°", value)

    /** Plain (no unit) formatting for dimensionless debug values, e.g. a normalized offset or a confidence score. */
    private fun formatRatio(value: Double): String =
        if (value.isNaN()) "--" else String.format(Locale.US, "%.2f", value)

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

    private fun color(resId: Int) = ContextCompat.getColor(this, resId)

    companion object {
        const val EXTRA_WORKOUT_ID = "SUMMARY_WORKOUT_ID"
        /** Boolean extra: show the live in-progress workout instead of loading one by ID. */
        const val EXTRA_SHOW_ACTIVE_WORKOUT = "SUMMARY_SHOW_ACTIVE_WORKOUT"

        private const val STRONG_SCORE = 80
        private const val MEDIUM_SCORE = 50
    }
}
