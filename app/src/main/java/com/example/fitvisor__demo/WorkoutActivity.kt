package com.example.fitvisor__demo

import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.view.View
import androidx.appcompat.app.AlertDialog
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

class WorkoutActivity : AppCompatActivity(), PoseLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null

    // Modular Components
    private val ruleEngine = SquatRuleEngine()
    private val workoutManager = WorkoutManager()
    
    // 4) Landmark Smoothing (EMA)
    private val landmarkSmoother = LandmarkSmoother(alpha = 0.35f)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        poseLandmarkerHelper = PoseLandmarkerHelper(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()

        workoutManager.startSession()

        // 1) In-session Summary Button
        binding.summaryButton.setOnClickListener {
            showSummaryDialog()
        }

        startCamera()
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder()
                .build()
                .also {
                    it.setSurfaceProvider(binding.previewView.surfaceProvider)
                }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { analyzer ->
                    analyzer.setAnalyzer(cameraExecutor) { imageProxy ->
                        val frameTimeNanos = imageProxy.imageInfo.timestamp
                        val frameTimeMicros = TimeUnit.NANOSECONDS.toMicros(frameTimeNanos)
                        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
                        val bitmap = imageProxy.toBitmap()
                        imageProxy.close()

                        val matrix = Matrix().apply {
                            postRotate(rotationDegrees.toFloat())
                        }
                        val rotatedBitmap = Bitmap.createBitmap(
                            bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
                        )

                        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                        poseLandmarkerHelper.detectLiveStream(frameTimeMicros, mpImage)
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e("WorkoutActivity", "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResults(result: PoseLandmarkerResult, imageHeight: Int, imageWidth: Int) {
        runOnUiThread {
            var kneeAngle = 0.0
            var torsoAngle = 0.0
            var warningMessage: String? = null

            // 4) Reduce overlay sensitivity and jitter
            val rawLandmarks = result.landmarks().firstOrNull()
            val smoothedLandmarks = rawLandmarks?.let { landmarkSmoother.smooth(it) }

            if (smoothedLandmarks != null && smoothedLandmarks.isNotEmpty()) {
                // 3) Use same filtered landmarks for logic and drawing
                kneeAngle = KinematicCalculator.calculateAngle(
                    smoothedLandmarks[PoseLandmarkIndices.L_HIP],
                    smoothedLandmarks[PoseLandmarkIndices.L_KNEE],
                    smoothedLandmarks[PoseLandmarkIndices.L_ANKLE]
                )
                torsoAngle = KinematicCalculator.calculateTorsoAngle(
                    smoothedLandmarks[PoseLandmarkIndices.L_SH],
                    smoothedLandmarks[PoseLandmarkIndices.R_SH],
                    smoothedLandmarks[PoseLandmarkIndices.L_HIP],
                    smoothedLandmarks[PoseLandmarkIndices.R_HIP]
                )

                val knee = smoothedLandmarks[PoseLandmarkIndices.L_KNEE]
                val foot = smoothedLandmarks[PoseLandmarkIndices.L_FOOT_INDEX]
                val kneeMisaligned = knee.x() < foot.x()

                val analysis = ruleEngine.processFrame(kneeAngle, torsoAngle, kneeMisaligned)
                warningMessage = analysis.warning

                if (analysis.isRepCompleted) {
                    workoutManager.addRep(analysis.isRepCorrect)
                    // 2) Rep-completion feedback
                    showRepFeedback()
                }
            }

            // 3) Update Overlay with Correct Mapping (Signature match)
            binding.overlayView.setResults(
                smoothedLandmarks,
                imageHeight,
                imageWidth,
                kneeAngle,
                torsoAngle,
                warningMessage
            )
        }
    }

    private fun showRepFeedback() {
        binding.repFeedback.visibility = View.VISIBLE
        binding.repFeedback.postDelayed({
            binding.repFeedback.visibility = View.GONE
        }, 800)
    }

    private fun showSummaryDialog() {
        val summary = workoutManager.getSummary()
        AlertDialog.Builder(this)
            .setTitle("Session Summary")
            .setMessage("Total Reps: ${summary.totalReps}\n" +
                        "Correct: ${summary.correctReps}\n" +
                        "Incorrect: ${summary.incorrectReps}\n" +
                        "Duration: ${summary.durationSeconds}s")
            .setPositiveButton("Resume") { dialog, _ -> dialog.dismiss() }
            .setNegativeButton("End Session") { _, _ -> finish() }
            .show()
    }

    override fun onError(error: String) {
        Log.e("WorkoutActivity", error)
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }
}
