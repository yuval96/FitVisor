package com.example.fitvisor__demo.ui.home

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.example.fitvisor__demo.R
import com.example.fitvisor__demo.audio.AudioFeedbackEvent
import com.example.fitvisor__demo.audio.AudioFeedbackManager
import com.example.fitvisor__demo.databinding.ActivityHomeBinding
import com.example.fitvisor__demo.model.ExerciseType
import com.example.fitvisor__demo.ui.BrandingInsets
import com.example.fitvisor__demo.ui.WorkoutTimeFormat
import com.example.fitvisor__demo.ui.settings.SettingsActivity
import com.example.fitvisor__demo.ui.summary.SummaryActivity
import com.example.fitvisor__demo.ui.workout.WorkoutActivity
import com.example.fitvisor__demo.workout.ActiveWorkoutStore
import com.example.fitvisor__demo.workout.ExerciseSession
import com.example.fitvisor__demo.workout.WorkoutHistoryRepository
import com.example.fitvisor__demo.workout.WorkoutSession
import kotlinx.coroutines.launch

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var repository: WorkoutHistoryRepository
    private lateinit var audioFeedback: AudioFeedbackManager

    /** Exercise the user tapped, held while the camera permission is requested. */
    private var pendingExercise: ExerciseType = ExerciseType.SQUAT
    private var pendingWorkoutId: String? = null
    private val sessions get() = ActiveWorkoutStore.manager

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ splash screen. Installed before super.onCreate(); it stays
        // visible only until the first frame is drawn (no artificial delay) and
        // then hands off to the existing home screen.
        installSplashScreen()

        super.onCreate(savedInstanceState)
        pendingExercise = ExerciseType.fromNameOrDefault(savedInstanceState?.getString("pendingExercise"))
        pendingWorkoutId = savedInstanceState?.getString("pendingWorkoutId")
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)
        repository = WorkoutHistoryRepository.getInstance(applicationContext)
        audioFeedback = AudioFeedbackManager(this)

        // Branded system bars + inset handling (edge-to-edge on Android 15+).
        BrandingInsets.applyNavySystemBars(this)
        BrandingInsets.padForSystemBars(binding.homeScroll)
        // Keep the settings gear clear of the status bar / display cutout too.
        BrandingInsets.padForSystemBars(binding.settingsBar)

        binding.settingsButton.setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        binding.startWorkoutButton.setOnClickListener {
            if (sessions.currentWorkout == null) {
                sessions.startWorkout()
                renderWorkout()
            }
        }
        binding.resumeExerciseButton.setOnClickListener {
            sessions.currentExercise?.let { openExercise(it) }
        }
        binding.finishWorkoutButton.setOnClickListener {
            val id = sessions.currentWorkout?.id ?: return@setOnClickListener
            val completed = sessions.finishWorkout(id) ?: return@setOnClickListener
            audioFeedback.play(AudioFeedbackEvent.WORKOUT_COMPLETE)
            renderWorkout()
            lifecycleScope.launch {
                repository.saveCompletedWorkout(completed)
                loadHistory()
                startActivity(Intent(this@HomeActivity, SummaryActivity::class.java)
                    .putExtra(SummaryActivity.EXTRA_WORKOUT_ID, completed.id))
            }
        }

        binding.cardSquat.setOnClickListener { onExerciseSelected(ExerciseType.SQUAT) }
        binding.cardPushUp.setOnClickListener { onExerciseSelected(ExerciseType.PUSH_UP) }
        binding.cardShoulderPress.setOnClickListener { onExerciseSelected(ExerciseType.SHOULDER_PRESS) }
        binding.cardBicepsCurl.setOnClickListener { onExerciseSelected(ExerciseType.BICEPS_CURL) }
    }

    private fun onExerciseSelected(exercise: ExerciseType) {
        val workout = sessions.currentWorkout ?: return
        if (sessions.currentExercise != null) return
        pendingExercise = exercise
        pendingWorkoutId = workout.id
        if (allPermissionsGranted()) {
            startExercise(exercise)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startExercise(exercise: ExerciseType) {
        val workoutId = pendingWorkoutId ?: return
        pendingWorkoutId = null
        val session = sessions.startExercise(workoutId, exercise) ?: return
        renderWorkout()
        openExercise(session)
    }

    private fun openExercise(session: ExerciseSession) {
        startActivity(Intent(this, WorkoutActivity::class.java)
            .putExtra(WorkoutActivity.EXTRA_EXERCISE_SESSION_ID, session.id)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
    }

    override fun onResume() {
        super.onResume()
        renderWorkout()
        loadHistory()
    }

    private fun loadHistory() {
        lifecycleScope.launch {
            renderHistory(repository.getAllWorkouts())
        }
    }

    private fun renderHistory(workouts: List<WorkoutSession>) {
        val container = binding.historyContainer
        container.removeAllViews()
        binding.historyEmptyText.visibility = if (workouts.isEmpty()) View.VISIBLE else View.GONE
        val inflater = LayoutInflater.from(this)
        for (workout in workouts) {
            val item = inflater.inflate(R.layout.view_workout_history_item, container, false)
            item.findViewById<TextView>(R.id.historyDateText).text =
                WorkoutTimeFormat.date(workout.startTimeMillis)
            item.findViewById<TextView>(R.id.historyExercisesText).text =
                workout.exercises.joinToString(", ") { it.exerciseType.displayName }
            item.findViewById<TextView>(R.id.historyTotalsText).text =
                getString(R.string.history_item_totals, workout.totalReps, workout.correctReps)
            item.setOnClickListener {
                startActivity(Intent(this, SummaryActivity::class.java)
                    .putExtra(SummaryActivity.EXTRA_WORKOUT_ID, workout.id))
            }
            item.findViewById<ImageButton>(R.id.historyDeleteButton).setOnClickListener {
                confirmDeleteWorkout(workout.id)
            }
            container.addView(item)
        }
    }

    private fun confirmDeleteWorkout(workoutId: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_workout_title)
            .setMessage(R.string.delete_workout_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                lifecycleScope.launch {
                    repository.deleteWorkout(workoutId)
                    loadHistory()
                }
            }
            .show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExercise", pendingExercise.name)
        outState.putString("pendingWorkoutId", pendingWorkoutId)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        if (::audioFeedback.isInitialized) audioFeedback.close()
        super.onDestroy()
    }

    private fun renderWorkout() {
        val workout = sessions.currentWorkout
        val exercise = sessions.currentExercise
        binding.startWorkoutButton.visibility = if (workout == null) View.VISIBLE else View.GONE
        val selectionVisibility = if (workout != null && exercise == null) View.VISIBLE else View.GONE
        listOf(binding.chooseLabel, binding.cardSquat, binding.cardPushUp,
            binding.cardShoulderPress, binding.cardBicepsCurl).forEach { it.visibility = selectionVisibility }
        binding.finishWorkoutButton.visibility = selectionVisibility
        binding.resumeExerciseButton.visibility = if (exercise != null) View.VISIBLE else View.GONE
        binding.workoutStatus.visibility = if (workout != null) View.VISIBLE else View.GONE
        binding.workoutStatus.text = workout?.let {
            val completed = it.exercises.filter { item -> item.endTimeMillis != null }
            getString(R.string.active_workout_status, completed.size, it.totalReps) +
                completed.mapIndexed { index, item ->
                    "\n" + getString(R.string.completed_exercise_line,
                        index + 1, item.exerciseType.displayName, item.reps.size)
                }.joinToString("")
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        this, Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            if (allPermissionsGranted()) {
                startExercise(pendingExercise)
            } else {
                pendingWorkoutId = null
                Toast.makeText(this, "Camera permission is required to use this app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
