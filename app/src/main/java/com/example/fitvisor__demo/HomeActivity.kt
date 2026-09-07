package com.example.fitvisor__demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.example.fitvisor__demo.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

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
                SessionResultsHolder.clear()
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
            SessionResultsHolder.set(completed)
            renderWorkout()
            startActivity(Intent(this, SummaryActivity::class.java)
                .putExtra(SummaryActivity.EXTRA_WORKOUT_ID, completed.id))
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("pendingExercise", pendingExercise.name)
        outState.putString("pendingWorkoutId", pendingWorkoutId)
        super.onSaveInstanceState(outState)
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
