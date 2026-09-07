package com.example.fitvisor__demo

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
import android.os.Build
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.example.fitvisor__demo.databinding.ActivityMainBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class WorkoutActivity :
    AppCompatActivity(),
    PoseLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var cameraExecutor: ExecutorService

    private var camera: Camera? = null

    private lateinit var exerciseType: ExerciseType
    private lateinit var analyzer: ExerciseAnalyzer

    private val sessions get() = ActiveWorkoutStore.manager
    private var exerciseSessionId = ""
    private lateinit var settings: AppSettings
    private lateinit var debugStore: DebugDataStore
    private val latencyTracker = LatencyTracker()
    @Volatile private var measureLatency = false
    @Volatile private var workoutVisible = false
    private val hideRepFeedback = Runnable { binding.repFeedback.visibility = View.GONE }
    private val timerTick = object : Runnable {
        override fun run() {
            val seconds = sessions.currentExercise?.takeIf { it.id == exerciseSessionId }?.durationSeconds ?: 0L
            binding.workoutTimer.text = getString(R.string.workout_time,
                java.lang.String.format(java.util.Locale.US, "%02d:%02d", seconds / 60, seconds % 60))
            binding.workoutTimer.postDelayed(this, 1000)
        }
    }

    private val landmarkSmoother =
        LandmarkSmoother(alpha = AnalysisConfig.LANDMARK_SMOOTHING_ALPHA)

    companion object {
        const val EXTRA_EXERCISE_SESSION_ID = "EXERCISE_SESSION_ID"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        BrandingInsets.padForSystemBars(binding.root)
        settings = AppSettings(this)
        debugStore = DebugDataStore(this)

        exerciseSessionId = intent.getStringExtra(EXTRA_EXERCISE_SESSION_ID) ?: ""
        val session = sessions.currentExercise?.takeIf { it.id == exerciseSessionId }
        if (session == null) {
            Toast.makeText(this, R.string.workout_unavailable, Toast.LENGTH_LONG).show()
            returnToSelection()
            return
        }
        exerciseType = session.exerciseType
        analyzer = createAnalyzer(exerciseType)

        binding.exerciseTitle.text = exerciseType.displayName
        binding.exerciseInstruction.text = exerciseType.cameraInstruction

        poseLandmarkerHelper =
            PoseLandmarkerHelper(this, this)

        cameraExecutor =
            Executors.newSingleThreadExecutor()

        updateRepCounter()

        binding.summaryButton.setOnClickListener {
            finishExercise()
        }
        // Back also ends only this exercise, preserving its completed reps.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishExercise()
        })

        startCamera()
    }

    private fun createAnalyzer(type: ExerciseType): ExerciseAnalyzer =
        when (type) {
            ExerciseType.SQUAT -> SquatAnalyzer()
            ExerciseType.PUSH_UP -> PushUpAnalyzer()
            ExerciseType.SHOULDER_PRESS -> ShoulderPressAnalyzer { settings.debugEnabled }
            ExerciseType.BICEPS_CURL -> BicepsCurlAnalyzer()
        }

    private fun startCamera() {
        val cameraProviderFuture =
            ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            val cameraProvider =
                cameraProviderFuture.get()
            if (isFinishing || isDestroyed) return@addListener

            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(
                        binding.previewView.surfaceProvider
                    )
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(
                    ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST
                )
                .setOutputImageFormat(
                    ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888
                )
                .build()
                .also { analyzer ->

                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        if (!workoutVisible) {
                            imageProxy.close()
                            return@setAnalyzer
                        }
                        val measurementStart = if (measureLatency) SystemClock.elapsedRealtimeNanos() else null
                        try {
                            val frameTimeNanos =
                                imageProxy.imageInfo.timestamp

                            val frameTimeMillis =
                                TimeUnit.NANOSECONDS.toMillis(
                                    frameTimeNanos
                                )

                            val rotationDegrees =
                                imageProxy.imageInfo.rotationDegrees

                            val bitmap =
                                imageProxy.toBitmap()

                            val matrix = Matrix().apply {
                                postRotate(rotationDegrees.toFloat())
                            }

                            val rotatedBitmap =
                                Bitmap.createBitmap(
                                    bitmap,
                                    0,
                                    0,
                                    bitmap.width,
                                    bitmap.height,
                                    matrix,
                                    true
                                )

                            val mpImage =
                                BitmapImageBuilder(rotatedBitmap)
                                    .build()

                            if (measurementStart != null && measureLatency) {
                                latencyTracker.submit(frameTimeMillis, measurementStart, SystemClock.elapsedRealtimeNanos())
                            }
                            poseLandmarkerHelper.detectLiveStream(
                                frameTimeMillis,
                                mpImage,
                                rotatedBitmap
                            )
                        } catch (exception: Exception) {
                            Log.e(
                                "WorkoutActivity",
                                "Failed to analyze camera frame",
                                exception
                            )
                        } finally {
                            imageProxy.close()
                        }
                    }
                }

            val cameraSelector =
                CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()

                camera = cameraProvider.bindToLifecycle(
                    this,
                    cameraSelector,
                    preview,
                    imageAnalyzer
                )
            } catch (exception: Exception) {
                Log.e(
                    "WorkoutActivity",
                    "Use case binding failed",
                    exception
                )
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResults(
        result: PoseLandmarkerResult,
        inputFrame: Bitmap?,
        imageHeight: Int,
        imageWidth: Int
    ) {
        val callbackTime = if (measureLatency) SystemClock.elapsedRealtimeNanos() else 0L
        runOnUiThread {
            if (!workoutVisible || sessions.currentExerciseId != exerciseSessionId) return@runOnUiThread
            val rawLandmarks =
                result.landmarks().firstOrNull()

            val smoothedLandmarks =
                rawLandmarks?.let {
                    landmarkSmoother.smooth(it)
                }

            val output: ExerciseFrameOutput =
                if (!smoothedLandmarks.isNullOrEmpty()) {
                    analyzer.analyze(
                        smoothedLandmarks,
                        imageWidth,
                        imageHeight
                    )
                } else {
                    ExerciseFrameOutput(
                        ExerciseAnalysisResult(
                            isRepCompleted = false,
                            isRepCorrect = false,
                            warning = "No body detected",
                            phaseName = "-"
                        ),
                        OverlayMetrics(emptyMap(), "No body detected", null)
                    )
                }

            if (output.result.isRepCompleted) {
                val recorded = sessions.recordRep(
                    exerciseId = exerciseSessionId,
                    isCorrect = output.result.isRepCorrect,
                    errors = output.result.errors,
                    debugMetrics = output.result.debugMetrics ?: RepDebugMetrics.EMPTY
                )
                if (recorded != null) {
                    debugStore.recordRep(exerciseType, output.result.isRepCorrect)
                    updateRepCounter()
                    showRepFeedback(output.result.isRepCorrect)
                }
            }

            binding.overlayView.setResults(
                smoothedLandmarks,
                inputFrame,
                imageHeight,
                imageWidth,
                output.metrics
            )
            val warning = output.metrics.warning?.takeIf { it.isNotBlank() }
            binding.correctionFeedback.text = warning
            binding.correctionFeedback.visibility = if (warning == null) View.GONE else View.VISIBLE
            if (measureLatency && callbackTime != 0L) {
                latencyTracker.complete(result.timestampMs(), callbackTime, SystemClock.elapsedRealtimeNanos())
            }
        }
    }

    private fun showRepFeedback(
        isCorrect: Boolean
    ) {
        binding.repFeedback.text =
            if (isCorrect) {
                "Correct rep"
            } else {
                "Incorrect rep"
            }

        binding.repFeedback.visibility =
            View.VISIBLE

        binding.repFeedback.removeCallbacks(hideRepFeedback)
        binding.repFeedback.postDelayed(hideRepFeedback, 1500)
    }

    private fun updateRepCounter() {
        val count = sessions.currentExercise?.takeIf { it.id == exerciseSessionId }?.reps?.size ?: 0
        binding.repCounter.text = getString(R.string.workout_rep_count, count)
        binding.repCounter.contentDescription = getString(R.string.cd_rep_count, count)
    }

    private fun finishExercise() {
        workoutVisible = false
        sessions.finishExercise(exerciseSessionId)
        returnToSelection()
    }

    private fun returnToSelection() {
        startActivity(Intent(this, HomeActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    override fun onResume() {
        super.onResume()
        if (isFinishing || !::analyzer.isInitialized) return
        if (sessions.currentExerciseId != exerciseSessionId) {
            returnToSelection()
            return
        }
        sessions.resumeExercise(exerciseSessionId)
        binding.overlayView.setDebugEnabled(settings.debugEnabled)
        binding.overlayView.setShowSkeleton(settings.showSkeleton)
        measureLatency = settings.latencyEnabled
        workoutVisible = true
        binding.workoutTimer.removeCallbacks(timerTick)
        timerTick.run()
    }

    override fun onPause() {
        workoutVisible = false
        sessions.pauseExercise(exerciseSessionId)
        binding.workoutTimer.removeCallbacks(timerTick)
        binding.repFeedback.removeCallbacks(hideRepFeedback)
        binding.repFeedback.visibility = View.GONE
        if (measureLatency) {
            debugStore.saveLatency("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE}\n" +
                "${exerciseType.displayName} · ${poseLandmarkerHelper.runtimeDescription}\n" +
                "Saved ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date())}\n\n" + latencyTracker.report())
        }
        measureLatency = false
        latencyTracker.clearPending()
        // Do not join half a movement before backgrounding to one after resuming.
        if (::analyzer.isInitialized) analyzer.reset()
        landmarkSmoother.reset()
        super.onPause()
    }

    override fun onError(error: String) {
        Log.e(
            "WorkoutActivity",
            error
        )
    }

    override fun onDestroy() {
        super.onDestroy()

        landmarkSmoother.reset()
        if (::analyzer.isInitialized) analyzer.reset()

        if (::cameraExecutor.isInitialized) {
            // Wait behind submitted frames before releasing this exercise's detector.
            cameraExecutor.execute { runOnUiThread { poseLandmarkerHelper.close() } }
            cameraExecutor.shutdown()
        }
    }
}
