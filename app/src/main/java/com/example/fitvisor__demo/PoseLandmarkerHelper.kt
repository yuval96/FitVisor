package com.example.fitvisor__demo

import android.content.Context
import android.util.Log
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarkerResult

class PoseLandmarkerHelper(
    val context: Context,
    val listener: LandmarkerListener
) {

    private var poseLandmarker: PoseLandmarker? = null
    var runtimeDescription: String = "Detector unavailable"
        private set

    init {
        setupPoseLandmarker()
    }

    /**
     * Builds the detector from the user's [AppSettings] (model + GPU delegate),
     * without substituting models. A GPU initialization failure retries the same
     * asset on CPU. Missing/corrupt assets leave detection unavailable and report
     * the requested asset through [LandmarkerListener.onError].
     */
    private fun setupPoseLandmarker() {
        val settings = AppSettings(context)

        val requestedModel = settings.model
        runtimeDescription = "${requestedModel.assetPath} · unavailable"
        val assetPath = try {
            requestedModel.requireAsset(::assetExists)
        } catch (error: IllegalStateException) {
            listener.onError(error.message ?: "Selected pose model unavailable")
            return
        }

        val useGpu = settings.useGpu
        try {
            poseLandmarker = createLandmarker(
                assetPath,
                if (useGpu) Delegate.GPU else Delegate.CPU
            )
        } catch (gpuOrInitError: Exception) {
            if (useGpu) {
                listener.onError(
                    "GPU initialization failed for $assetPath; retrying the same model on CPU. " +
                        "(${gpuOrInitError.message})"
                )
                try {
                    poseLandmarker = createLandmarker(assetPath, Delegate.CPU)
                } catch (cpuError: Exception) {
                    listener.onError("Failed to initialize $assetPath on CPU: ${cpuError.message}. No other model was substituted.")
                }
            } else {
                listener.onError("Failed to initialize $assetPath on CPU: ${gpuOrInitError.message}. No other model was substituted.")
            }
        }
    }

    private fun createLandmarker(assetPath: String, delegate: Delegate): PoseLandmarker {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(assetPath)
            .setDelegate(delegate)
            .build()
        val options = PoseLandmarker.PoseLandmarkerOptions.builder()
            .setBaseOptions(baseOptions)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::onResults)
            .setErrorListener(this::onError)
            .build()
        return PoseLandmarker.createFromOptions(context, options).also {
            runtimeDescription = "$assetPath · $delegate"
            Log.i("PoseLandmarkerHelper", "Loaded $runtimeDescription")
        }
    }

    /** True if [path] can be opened from the app's assets. */
    private fun assetExists(path: String): Boolean = try {
        context.assets.open(path).close()
        true
    } catch (e: Exception) {
        false
    }

    fun detectLiveStream(
        timestampMillis: Long,
        image: MPImage
    ) {
        poseLandmarker?.detectAsync(image, timestampMillis)
    }

    /** Release the native detector after the workout Activity stops submitting frames. */
    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
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
