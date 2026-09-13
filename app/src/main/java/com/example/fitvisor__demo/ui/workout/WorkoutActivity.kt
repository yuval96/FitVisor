package com.example.fitvisor__demo.ui.workout

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.os.SystemClock
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
import com.example.fitvisor__demo.R
import com.example.fitvisor__demo.audio.AudioFeedbackEvent
import com.example.fitvisor__demo.audio.AudioFeedbackManager
import com.example.fitvisor__demo.databinding.ActivityMainBinding
import com.example.fitvisor__demo.debug.DebugSessionManager
import com.example.fitvisor__demo.debug.DebugSessionState
import com.example.fitvisor__demo.debug.DebugSessionTransition
import com.example.fitvisor__demo.debug.DebugSessionUpdate
import com.example.fitvisor__demo.exercises.ExerciseAnalyzer
import com.example.fitvisor__demo.exercises.ExerciseFrameOutput
import com.example.fitvisor__demo.exercises.bicepscurl.BicepsCurlAnalyzer
import com.example.fitvisor__demo.exercises.pushup.PushUpAnalyzer
import com.example.fitvisor__demo.exercises.shoulderpress.ShoulderPressAnalyzer
import com.example.fitvisor__demo.exercises.squat.SquatAnalyzer
import com.example.fitvisor__demo.model.ExerciseAnalysisResult
import com.example.fitvisor__demo.model.ExerciseType
import com.example.fitvisor__demo.model.OverlayMetrics
import com.example.fitvisor__demo.model.RepDebugMetrics
import com.example.fitvisor__demo.pose.LandmarkSmoother
import com.example.fitvisor__demo.pose.PendingFrameCache
import com.example.fitvisor__demo.pose.PoseLandmarkerHelper
import com.example.fitvisor__demo.settings.AnalysisConfig
import com.example.fitvisor__demo.settings.AppSettings
import com.example.fitvisor__demo.settings.DebugDataStore
import com.example.fitvisor__demo.settings.LatencyTracker
import com.example.fitvisor__demo.settings.PoseQualityTracker
import com.example.fitvisor__demo.ui.BrandingInsets
import com.example.fitvisor__demo.ui.home.HomeActivity
import com.example.fitvisor__demo.ui.summary.SummaryActivity
import com.example.fitvisor__demo.workout.ActiveWorkoutStore
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
    private lateinit var audioFeedback: AudioFeedbackManager
    private val latencyTracker = LatencyTracker()
    private val poseQualityTracker = PoseQualityTracker()
    private val debugSessionManager = DebugSessionManager()
    private val measuredFrames = PendingFrameCache<Unit>(MEASURED_FRAME_WINDOW_MILLIS)
    @Volatile private var measurePerformance = false
    @Volatile private var workoutVisible = false
    private val hideRepFeedback = Runnable { binding.repFeedback.visibility = View.GONE }
    private val debugSessionTick = object : Runnable {
        override fun run() {
            if (!workoutVisible) return
            val update = debugSessionManager.update()
            handleDebugSessionUpdate(update)
            if (
                update.state == DebugSessionState.PREPARING ||
                update.state == DebugSessionState.MEASURING
            ) {
                binding.debugSessionStatus.postDelayed(this, DEBUG_SESSION_TICK_MILLIS)
            }
        }
    }
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
        private const val DEBUG_SESSION_TICK_MILLIS = 200L
        private const val MEASURED_FRAME_WINDOW_MILLIS = 5_000L
        private const val MILLIS_PER_SECOND = 1_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        BrandingInsets.padForSystemBars(binding.root)
        settings = AppSettings(this)
        debugStore = DebugDataStore(this)
        audioFeedback = AudioFeedbackManager(this)

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
        // Opens Summary in "interim" mode: a plain forward navigation (no
        // CLEAR_TOP, no finish() here), so this Activity stays on the back
        // stack and simply resumes — with its existing onPause/onResume
        // pause-and-restore behavior — once the user returns from Summary.
        binding.viewSummaryButton.setOnClickListener {
            startActivity(Intent(this, SummaryActivity::class.java)
                .putExtra(SummaryActivity.EXTRA_SHOW_ACTIVE_WORKOUT, true))
        }
        // Back also ends only this exercise, preserving its completed reps.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finishExercise()
        })

        startCamera()
    }

    private fun createAnalyzer(type: ExerciseType): ExerciseAnalyzer =
        when (type) {
            ExerciseType.SQUAT -> SquatAnalyzer { measurePerformance }
            ExerciseType.PUSH_UP -> PushUpAnalyzer { measurePerformance }
            ExerciseType.SHOULDER_PRESS -> ShoulderPressAnalyzer(
                debugEnabled = { measurePerformance },
                poseQualityEnabled = { measurePerformance }
            )
            ExerciseType.BICEPS_CURL -> BicepsCurlAnalyzer { measurePerformance }
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
                        val measurementStart = if (measurePerformance) SystemClock.elapsedRealtimeNanos() else null
                        if (measurementStart != null) {
                            latencyTracker.cameraFrame(measurementStart, imageProxy.width, imageProxy.height)
                        }
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

                            if (measurementStart != null && measurePerformance) {
                                latencyTracker.submit(frameTimeMillis, measurementStart, SystemClock.elapsedRealtimeNanos())
                                measuredFrames.put(frameTimeMillis, Unit)
                            }
                            poseLandmarkerHelper.detectLiveStream(
                                frameTimeMillis,
                                mpImage,
                                rotatedBitmap
                            )
                        } catch (exception: Exception) {
                            measuredFrames.remove(
                                TimeUnit.NANOSECONDS.toMillis(imageProxy.imageInfo.timestamp)
                            )
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
        val measurementFrame =
            measuredFrames.take(result.timestampMs()) != null && measurePerformance && workoutVisible
        val callbackTime = if (measurementFrame) {
            SystemClock.elapsedRealtimeNanos().also(latencyTracker::analysisResult)
        } else {
            0L
        }
        runOnUiThread {
            if (!workoutVisible || sessions.currentExerciseId != exerciseSessionId) return@runOnUiThread
            var debugUpdateAfterFrame: DebugSessionUpdate? = null
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
                        imageHeight,
                        rawLandmarks
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

            if (measurementFrame && measurePerformance) {
                output.poseQuality?.let(poseQualityTracker::add)
            }

            if (output.result.isRepCompleted) {
                val recorded = sessions.recordRep(
                    exerciseId = exerciseSessionId,
                    isCorrect = output.result.isRepCorrect,
                    errors = output.result.errors,
                    debugMetrics = output.result.debugMetrics ?: RepDebugMetrics.EMPTY
                )
                if (recorded != null) {
                    val isDebugMeasurementRep =
                        measurementFrame && debugSessionManager.state == DebugSessionState.MEASURING
                    if (isDebugMeasurementRep) {
                        debugStore.recordRep(exerciseType, output.result.isRepCorrect)
                    }
                    updateRepCounter()
                    showRepFeedback(output.result.isRepCorrect)
                    audioFeedback.play(
                        if (output.result.isRepCorrect) {
                            AudioFeedbackEvent.REP_CORRECT
                        } else {
                            AudioFeedbackEvent.REP_INCORRECT
                        }
                    )
                    if (isDebugMeasurementRep) {
                        val debugUpdate = debugSessionManager.recordCompletedRepetition()
                        if (debugUpdate.transition == DebugSessionTransition.MEASUREMENT_COMPLETED) {
                            // Stop new samples immediately, then include this completing
                            // frame's final latency sample before persisting the snapshot.
                            measurePerformance = false
                            debugUpdateAfterFrame = debugUpdate
                        } else {
                            handleDebugSessionUpdate(debugUpdate)
                        }
                    }
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
            if (measurementFrame && callbackTime != 0L) {
                latencyTracker.complete(result.timestampMs(), callbackTime, SystemClock.elapsedRealtimeNanos())
            }
            debugUpdateAfterFrame?.let(::handleDebugSessionUpdate)
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
        if (settings.debugEnabled) {
            handleDebugSessionUpdate(debugSessionManager.update())
        }
        workoutVisible = false
        sessions.finishExercise(exerciseSessionId)
        returnToSelection()
    }

    private fun startDebugSessionIfEnabled() {
        binding.debugSessionStatus.removeCallbacks(debugSessionTick)
        measurePerformance = false
        measuredFrames.clear()
        latencyTracker.reset()
        poseQualityTracker.reset()
        debugSessionManager.reset()

        if (!settings.debugEnabled) {
            binding.debugSessionStatus.visibility = View.GONE
            return
        }

        handleDebugSessionUpdate(debugSessionManager.start())
        binding.debugSessionStatus.postDelayed(debugSessionTick, DEBUG_SESSION_TICK_MILLIS)
    }

    private fun handleDebugSessionUpdate(update: DebugSessionUpdate) {
        when (update.transition) {
            DebugSessionTransition.MEASUREMENT_STARTED -> startDebugMeasurement()
            DebugSessionTransition.MEASUREMENT_COMPLETED -> completeDebugMeasurement()
            DebugSessionTransition.NONE -> Unit
        }
        renderDebugSessionStatus(update)
    }

    private fun startDebugMeasurement() {
        // The persistent results are invalidated at the exact start of the new
        // measurement, not during the five-second preparation window.
        measurePerformance = false
        measuredFrames.clear()
        latencyTracker.reset()
        poseQualityTracker.reset()
        debugStore.resetPerformanceMetrics()
        debugStore.resetPoseQualityMetrics()
        measurePerformance = true
    }

    private fun completeDebugMeasurement() {
        measurePerformance = false
        measuredFrames.clear()
        latencyTracker.clearPending()
        debugStore.savePerformanceMetrics(
            latencyTracker.snapshot(
                model = poseLandmarkerHelper.selectedModel.displayName,
                delegate = poseLandmarkerHelper.activeDelegate?.name,
                configuredUseGpu = poseLandmarkerHelper.requestedGpu,
                timestampNanos = SystemClock.elapsedRealtimeNanos()
            )
        )
        debugStore.savePoseQualityMetrics(poseQualityTracker.snapshot())
        audioFeedback.play(AudioFeedbackEvent.WORKOUT_COMPLETE)
    }

    private fun renderDebugSessionStatus(update: DebugSessionUpdate) {
        val text = when (update.state) {
            DebugSessionState.IDLE -> null
            DebugSessionState.PREPARING -> getString(
                R.string.debug_measurement_starts_in,
                update.preparationSecondsRemaining ?: 1
            )
            DebugSessionState.MEASURING -> getString(
                R.string.debug_measurement_in_progress,
                update.measurementElapsedMillis / MILLIS_PER_SECOND,
                update.completedRepetitions
            )
            DebugSessionState.COMPLETED -> getString(
                R.string.debug_measurement_completed,
                update.measurementElapsedMillis / MILLIS_PER_SECOND,
                update.completedRepetitions
            )
        }
        binding.debugSessionStatus.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text != null && binding.debugSessionStatus.text.toString() != text) {
            binding.debugSessionStatus.text = text
        }
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
        workoutVisible = true
        startDebugSessionIfEnabled()
        binding.workoutTimer.removeCallbacks(timerTick)
        timerTick.run()
    }

    override fun onPause() {
        workoutVisible = false
        sessions.pauseExercise(exerciseSessionId)
        binding.workoutTimer.removeCallbacks(timerTick)
        binding.debugSessionStatus.removeCallbacks(debugSessionTick)
        binding.repFeedback.removeCallbacks(hideRepFeedback)
        binding.repFeedback.visibility = View.GONE
        measurePerformance = false
        measuredFrames.clear()
        debugSessionManager.reset()
        binding.debugSessionStatus.visibility = View.GONE
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
        if (::audioFeedback.isInitialized) audioFeedback.close()

        if (::cameraExecutor.isInitialized) {
            // Wait behind submitted frames before releasing this exercise's detector.
            cameraExecutor.execute { runOnUiThread { poseLandmarkerHelper.close() } }
            cameraExecutor.shutdown()
        }
    }

}
