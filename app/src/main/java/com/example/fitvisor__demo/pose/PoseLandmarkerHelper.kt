package com.example.fitvisor__demo.pose

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import com.example.fitvisor__demo.settings.AppSettings
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
    lateinit var selectedModel: com.example.fitvisor__demo.settings.PoseModel
        private set
    var requestedGpu: Boolean = false
        private set
    var activeDelegate: Delegate? = null
        private set

    /**
     * The exact bitmap submitted to [detectLiveStream], keyed by the same
     * timestamp passed to `detectAsync` (which [PoseLandmarkerResult.timestampMs]
     * echoes back). Used to hand the overlay back the frame it actually
     * analyzed, instead of [com.google.mediapipe.framework.image.BitmapExtractor]
     * on the MPImage the result listener receives: that image is reconstructed
     * by the native graph from its own internal buffer, which is not guaranteed
     * to have the same pixel layout/row stride as the bitmap we submitted —
     * extracting it produced visibly corrupted (striped, wrongly scaled) frames
     * on-device. Keeping our own reference sidesteps that reconstruction
     * entirely: what's displayed is byte-for-byte what was analyzed.
     */
    private val pendingFrames = PendingFrameCache<Bitmap>(PENDING_FRAME_WINDOW_MS)

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
        selectedModel = requestedModel
        runtimeDescription = "${requestedModel.assetPath} · unavailable"
        val assetPath = try {
            requestedModel.requireAsset(::assetExists)
        } catch (error: IllegalStateException) {
            listener.onError(error.message ?: "Selected pose model unavailable")
            return
        }

        val useGpu = settings.useGpu
        requestedGpu = useGpu
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
            activeDelegate = delegate
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

    /**
     * [frame] is the exact bitmap [image] was built from; it's retained here
     * (keyed by [timestampMillis]) so [onResults] can hand back the same object
     * rather than re-extracting one from the result's MPImage. If submission
     * fails synchronously, the entry is removed immediately so it can't leak.
     */
    fun detectLiveStream(
        timestampMillis: Long,
        image: MPImage,
        frame: Bitmap
    ) {
        val landmarker = poseLandmarker ?: return
        pendingFrames.put(timestampMillis, frame)
        try {
            landmarker.detectAsync(image, timestampMillis)
        } catch (e: Exception) {
            pendingFrames.remove(timestampMillis)
            throw e
        }
    }

    /** Release the native detector after the workout Activity stops submitting frames. */
    fun close() {
        poseLandmarker?.close()
        poseLandmarker = null
        activeDelegate = null
        pendingFrames.clear()
    }

    private fun onResults(result: PoseLandmarkerResult, input: MPImage) {
        // The frame that produced these landmarks; see [pendingFrames]. Falls
        // back to the result's own reported size only if our copy is missing
        // (e.g. it aged out), so the overlay still gets a plausible size for
        // skeleton-only rendering.
        val frame = pendingFrames.take(result.timestampMs())
        listener.onResults(result, frame, frame?.height ?: input.height, frame?.width ?: input.width)
    }

    private fun onError(error: RuntimeException) {
        listener.onError(error.message ?: "Unknown error")
    }

    interface LandmarkerListener {
        fun onError(error: String)
        fun onResults(result: PoseLandmarkerResult, inputFrame: Bitmap?, imageHeight: Int, imageWidth: Int)
    }

    companion object {
        /** Generous vs. typical live-stream latency; just bounds the cache, not a real deadline. */
        private const val PENDING_FRAME_WINDOW_MS = 5_000L
    }
}
