package com.example.fitvisor__demo

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.fitvisor__demo.databinding.ActivityHomeBinding

class HomeActivity : AppCompatActivity() {

    private lateinit var binding: ActivityHomeBinding

    /** Exercise the user tapped, held while the camera permission is requested. */
    private var pendingExercise: ExerciseType = ExerciseType.SQUAT

    override fun onCreate(savedInstanceState: Bundle?) {
        // Android 12+ splash screen. Installed before super.onCreate(); it stays
        // visible only until the first frame is drawn (no artificial delay) and
        // then hands off to the existing home screen.
        installSplashScreen()

        super.onCreate(savedInstanceState)
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

        binding.cardSquat.setOnClickListener { onExerciseSelected(ExerciseType.SQUAT) }
        binding.cardPushUp.setOnClickListener { onExerciseSelected(ExerciseType.PUSH_UP) }
        binding.cardShoulderPress.setOnClickListener { onExerciseSelected(ExerciseType.SHOULDER_PRESS) }
        binding.cardBicepsCurl.setOnClickListener { onExerciseSelected(ExerciseType.BICEPS_CURL) }
    }

    private fun onExerciseSelected(exercise: ExerciseType) {
        pendingExercise = exercise
        if (allPermissionsGranted()) {
            startWorkout(exercise)
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_PERMISSIONS
            )
        }
    }

    private fun startWorkout(exercise: ExerciseType) {
        val intent = Intent(this, WorkoutActivity::class.java)
        intent.putExtra(WorkoutActivity.EXTRA_EXERCISE_TYPE, exercise.name)
        startActivity(intent)
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
                startWorkout(pendingExercise)
            } else {
                Toast.makeText(this, "Camera permission is required to use this app", Toast.LENGTH_SHORT).show()
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
    }
}
