package com.example.fitvisor__demo

import android.content.Context
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
     * with graceful fallbacks so a selection that a device can't honor never
     * crashes the workout:
     *  - a selected model whose `.task` asset is missing falls back to HEAVY;
     *  - a GPU-delegate init failure retries on CPU.
     * Each fallback is surfaced through [LandmarkerListener.onError] for logging.
     */
    private fun setupPoseLandmarker() {
        val settings = AppSettings(context)

        val requestedModel = settings.model
        val model = if (assetExists(requestedModel.assetPath)) {
            requestedModel
        } else {
            listener.onError(
                "${requestedModel.name} model file (${requestedModel.assetPath}) not found " +
                    "in assets; using HEAVY instead."
            )
            PoseModel.HEAVY
        }

        val useGpu = settings.useGpu
        try {
            poseLandmarker = createLandmarker(
                model.assetPath,
                if (useGpu) Delegate.GPU else Delegate.CPU
            )
        } catch (gpuOrInitError: Exception) {
            if (useGpu) {
                listener.onError(
                    "GPU delegate unavailable; falling back to CPU. " +
                        "(${gpuOrInitError.message})"
                )
                try {
                    poseLandmarker = createLandmarker(model.assetPath, Delegate.CPU)
                } catch (cpuError: Exception) {
                    listener.onError("Failed to initialize pose model: ${cpuError.message}")
                }
            } else {
                listener.onError("Failed to initialize pose model: ${gpuOrInitError.message}")
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
