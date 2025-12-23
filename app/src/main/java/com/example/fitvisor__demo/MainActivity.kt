package com.example.fitvisor__demo

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.example.fitvisor__demo.databinding.ActivityMainBinding
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.sqrt

// Object to hold landmark indices
object PoseLandmarkIndices {
    const val L_HIP = 23
    const val R_HIP = 24
    const val L_KNEE = 25
    const val R_KNEE = 26
    const val L_ANKLE = 27
    const val L_SH = 11
    const val R_SH = 12
    const val L_FOOT_INDEX = 29
    const val R_FOOT_INDEX = 30
}

class MainActivity : AppCompatActivity(), PoseLandmarkerHelper.LandmarkerListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var poseLandmarkerHelper: PoseLandmarkerHelper
    private lateinit var cameraExecutor: ExecutorService
    private var camera: Camera? = null

    // Squat analysis state
    private var squatStage = "up"
    private var repCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                REQUEST_CODE_PERMISSIONS
            )
        }

        poseLandmarkerHelper = PoseLandmarkerHelper(this, this)
        cameraExecutor = Executors.newSingleThreadExecutor()
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
                            // Rotate the image back to vertical.
                            postRotate(rotationDegrees.toFloat())
                        }
                        val rotatedBitmap = Bitmap.createBitmap(
                            bitmap,
                            0,
                            0,
                            bitmap.width,
                            bitmap.height,
                            matrix,
                            true
                        )

                        val mpImage = BitmapImageBuilder(rotatedBitmap).build()
                        poseLandmarkerHelper.detectLiveStream(
                            frameTimeMicros,
                            mpImage
                        )
                    }
                }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                camera = cameraProvider.bindToLifecycle(
                    this, cameraSelector, preview, imageAnalyzer
                )
            } catch (exc: Exception) {
                Log.e(TAG, "Use case binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResults(result: PoseLandmarkerResult, imageHeight: Int, imageWidth: Int) {
        runOnUiThread {
            var kneeAngle = 0.0
            var torsoAngle = 0.0
            var leftKneeStatus = "N/A"
            var rightKneeStatus = "N/A"
            var torsoStatus = ""

            result.landmarks().firstOrNull()?.let { landmarkList ->
                if (landmarkList.isNotEmpty()) {
                    // Calculate knee angle (we'll use the left knee for this example)
                    val leftHip = landmarkList[PoseLandmarkIndices.L_HIP]
                    val leftKnee = landmarkList[PoseLandmarkIndices.L_KNEE]
                    val leftAnkle = landmarkList[PoseLandmarkIndices.L_ANKLE]
                    kneeAngle = getAngle(leftHip, leftKnee, leftAnkle)

                    // Calculate torso angle
                    torsoAngle = computeTorsoAngle(landmarkList)
                    torsoStatus = getTorsoStatus(torsoAngle)


                    // Update rep count
                    updateRepCount(kneeAngle)

                    // Knee tracking
                    val closerLeg = getCloserLeg(landmarkList)
                    if (closerLeg == "left") {
                        val leftBad = isKneeOverFoot(landmarkList, "left")
                        leftKneeStatus = getKneeTrackingStatus(leftBad)
                    } else {
                        val rightBad = isKneeOverFoot(landmarkList, "right")
                        rightKneeStatus = getKneeTrackingStatus(rightBad)
                    }
                }
            }

            // Pass the results to the OverlayView
            binding.overlayView.setResults(
                result,
                imageHeight,
                imageWidth,
                kneeAngle,
                torsoAngle,
                repCount,
                leftKneeStatus,
                rightKneeStatus,
                torsoStatus
            )
        }
    }

    override fun onError(error: String) {
        Log.e(TAG, error)
    }

    private fun updateRepCount(kneeAngle: Double) {
        if (kneeAngle < SQUAT_DOWN_ANGLE && squatStage == "up") {
            squatStage = "down"
        } else if (kneeAngle > SQUAT_UP_ANGLE && squatStage == "down") {
            squatStage = "up"
            repCount++
        }
    }

    private fun getTorsoStatus(torsoAngle: Double): String {
        return if (torsoAngle < TORSO_OK_ANGLE) "Torso OK" else "Torso LEANING"
    }

    private fun computeTorsoAngle(landmarks: List<NormalizedLandmark>): Double {
        val leftShoulder = landmarks[PoseLandmarkIndices.L_SH]
        val rightShoulder = landmarks[PoseLandmarkIndices.R_SH]
        val leftHip = landmarks[PoseLandmarkIndices.L_HIP]
        val rightHip = landmarks[PoseLandmarkIndices.R_HIP]

        val midShoulderX = (leftShoulder.x() + rightShoulder.x()) / 2
        val midShoulderY = (leftShoulder.y() + rightShoulder.y()) / 2

        val midHipX = (leftHip.x() + rightHip.x()) / 2
        val midHipY = (leftHip.y() + rightHip.y()) / 2

        val vecX = midShoulderX - midHipX
        val vecY = midShoulderY - midHipY

        // Vertical vector (pointing up)
        val verticalVecX = 0f
        val verticalVecY = -1f

        val dotProduct = (vecX * verticalVecX) + (vecY * verticalVecY)
        val vecMagnitude = sqrt((vecX * vecX + vecY * vecY).toDouble())
        val verticalMagnitude = sqrt((verticalVecX * verticalVecX + verticalVecY * verticalVecY).toDouble())
        
        val cosTheta = dotProduct / (vecMagnitude * verticalMagnitude)
        return Math.toDegrees(acos(cosTheta))
    }

    private fun getAngle(firstPoint: NormalizedLandmark, midPoint: NormalizedLandmark, lastPoint: NormalizedLandmark): Double {
        val radians = atan2(lastPoint.y() - midPoint.y(), lastPoint.x() - midPoint.x()) -
                atan2(firstPoint.y() - midPoint.y(), firstPoint.x() - midPoint.x())
        var degrees = Math.toDegrees(abs(radians).toDouble())
        if (degrees > 180.0) {
            degrees = 360.0 - degrees
        }
        return degrees
    }
    
    private fun getCloserLeg(landmarks: List<NormalizedLandmark>): String {
        val leftKneeZ = landmarks[PoseLandmarkIndices.L_KNEE].z()
        val rightKneeZ = landmarks[PoseLandmarkIndices.R_KNEE].z()
        return if (leftKneeZ < rightKneeZ) "left" else "right"
    }

    private fun isKneeOverFoot(landmarks: List<NormalizedLandmark>, side: String): Boolean {
        return if (side == "left") {
            val knee = landmarks[PoseLandmarkIndices.L_KNEE]
            val foot = landmarks[PoseLandmarkIndices.L_FOOT_INDEX]
            knee.x() < foot.x()
        } else {
            val knee = landmarks[PoseLandmarkIndices.R_KNEE]
            val foot = landmarks[PoseLandmarkIndices.R_FOOT_INDEX]
            knee.x() > foot.x()
        }
    }

    private fun getKneeTrackingStatus(isBad: Boolean): String {
        return if (!isBad) "OK" else "MISALIGNED"
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
                startCamera()
            } else {
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
    }

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_CODE_PERMISSIONS = 10

        // Squat constants
        const val SQUAT_DOWN_ANGLE = 100
        const val SQUAT_UP_ANGLE = 160
        const val TORSO_OK_ANGLE = 35
    }
}