package com.example.fitvisor__demo

import android.content.Context
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseLandmarkerHelper(
    val context: Context,
    val listener: LandmarkerListener
) {

    private var poseLandmarker: PoseLandmarker? = null

    init {
        setupPoseLandmarker()
    }

    private fun setupPoseLandmarker() {
        val baseOptionsBuilder = BaseOptions.builder().setModelAssetPath("pose_landmarker_heavy.task")
        val baseOptions = baseOptionsBuilder.build()
        val optionsBuilder = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::onResults)
            .setErrorListener(this::onError)

        val options = optionsBuilder.build()
        poseLandmarker = PoseLandmarker.createFromOptions(context, options)
    }

    fun detectLiveStream(frameTime: Long, image: com.google.mediapipe.framework.image.MPImage) {
        poseLandmarker?.detectAsync(image, frameTime)
    }

    private fun onResults(result: PoseLandmarkerResult, input: com.google.mediapipe.framework.image.MPImage) {
        listener.onResults(result, input.height, input.width)
    }

    private fun onError(error: RuntimeException) {
        listener.onError(error.message ?: "Unknown error")
    }

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(result: PoseLandmarkerResult, imageHeight: Int, imageWidth: Int)
    }
}