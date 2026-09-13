package com.example.fitvisor__demo.settings

import android.content.Context
import androidx.core.content.edit
import com.example.fitvisor__demo.model.ExerciseType
import java.text.DateFormat
import java.util.Date

/** Local test totals are independent of the ordinary per-workout counter. */
class DebugDataStore(context: Context) {
    private val settings = AppSettings(context)
    private val prefs = context.applicationContext.getSharedPreferences("fitvisor_debug_data", Context.MODE_PRIVATE)

    fun recordRep(type: ExerciseType, correct: Boolean) {
        if (!settings.testRepsEnabled) return
        val key = "${type.name}_${if (correct) "correct" else "incorrect"}"
        val edit = prefs.edit().putLong(key, prefs.getLong(key, 0) + 1)
        if (!prefs.contains("since")) edit.putLong("since", System.currentTimeMillis())
        edit.apply()
    }

    fun repReport(): String = buildString {
        val since = prefs.getLong("since", 0)
        if (since > 0) append("Since ${DateFormat.getDateTimeInstance().format(Date(since))}\n\n")
        for (type in ExerciseType.values()) {
            val correct = prefs.getLong("${type.name}_correct", 0)
            val incorrect = prefs.getLong("${type.name}_incorrect", 0)
            append("${type.displayName}: ${correct + incorrect}\n  Correct: $correct · Incorrect: $incorrect\n")
        }
    }

    fun resetReps() {
        val edit = prefs.edit().putLong("since", System.currentTimeMillis())
        for (type in ExerciseType.values()) {
            edit.remove("${type.name}_correct").remove("${type.name}_incorrect")
        }
        edit.apply()
    }

    fun savePerformanceMetrics(metrics: PerformanceMetricsSnapshot) {
        val edit = prefs.edit()
            .putString(KEY_METRICS_MODEL, metrics.model)
            .putBoolean(KEY_METRICS_CONFIGURED_GPU, metrics.configuredUseGpu)
            .remove(KEY_METRICS_DELEGATE)
            .remove(KEY_METRICS_INPUT_WIDTH)
            .remove(KEY_METRICS_INPUT_HEIGHT)
            .remove(KEY_METRICS_CAMERA_FPS)
            .remove(KEY_METRICS_ANALYSIS_FPS)
            .remove(KEY_METRICS_CURRENT_LATENCY)
            .remove(KEY_METRICS_AVERAGE_LATENCY)
            .remove(LEGACY_LATENCY_REPORT)

        metrics.delegate?.let { edit.putString(KEY_METRICS_DELEGATE, it) }
        metrics.inputWidth?.let { edit.putInt(KEY_METRICS_INPUT_WIDTH, it) }
        metrics.inputHeight?.let { edit.putInt(KEY_METRICS_INPUT_HEIGHT, it) }
        metrics.cameraFps?.let { edit.putFloat(KEY_METRICS_CAMERA_FPS, it.toFloat()) }
        metrics.analysisFps?.let { edit.putFloat(KEY_METRICS_ANALYSIS_FPS, it.toFloat()) }
        metrics.currentLatencyMs?.let { edit.putFloat(KEY_METRICS_CURRENT_LATENCY, it.toFloat()) }
        metrics.averageLatencyMs?.let { edit.putFloat(KEY_METRICS_AVERAGE_LATENCY, it.toFloat()) }
        edit.apply()
    }

    fun performanceMetrics(): PerformanceMetricsSnapshot? {
        val model = prefs.getString(KEY_METRICS_MODEL, null) ?: return null
        return PerformanceMetricsSnapshot(
            model = model,
            delegate = prefs.getString(KEY_METRICS_DELEGATE, null),
            configuredUseGpu = prefs.getBoolean(KEY_METRICS_CONFIGURED_GPU, false),
            inputWidth = prefs.intOrNull(KEY_METRICS_INPUT_WIDTH),
            inputHeight = prefs.intOrNull(KEY_METRICS_INPUT_HEIGHT),
            cameraFps = prefs.floatOrNull(KEY_METRICS_CAMERA_FPS)?.toDouble(),
            analysisFps = prefs.floatOrNull(KEY_METRICS_ANALYSIS_FPS)?.toDouble(),
            currentLatencyMs = prefs.floatOrNull(KEY_METRICS_CURRENT_LATENCY)?.toDouble(),
            averageLatencyMs = prefs.floatOrNull(KEY_METRICS_AVERAGE_LATENCY)?.toDouble()
        )
    }

    fun resetPerformanceMetrics() {
        prefs.edit()
            .remove(KEY_METRICS_MODEL)
            .remove(KEY_METRICS_CONFIGURED_GPU)
            .remove(KEY_METRICS_DELEGATE)
            .remove(KEY_METRICS_INPUT_WIDTH)
            .remove(KEY_METRICS_INPUT_HEIGHT)
            .remove(KEY_METRICS_CAMERA_FPS)
            .remove(KEY_METRICS_ANALYSIS_FPS)
            .remove(KEY_METRICS_CURRENT_LATENCY)
            .remove(KEY_METRICS_AVERAGE_LATENCY)
            .remove(LEGACY_LATENCY_REPORT)
            .apply()
    }

    fun savePoseQualityMetrics(metrics: PoseQualityMetricsSnapshot) {
        prefs.edit {
            putLong(KEY_POSE_QUALITY_SAMPLES, metrics.sampleCount)
            remove(KEY_POSE_QUALITY_CURRENT)
            remove(KEY_POSE_QUALITY_AVERAGE)
            remove(KEY_POSE_QUALITY_MINIMUM)
            remove(KEY_POSE_QUALITY_BELOW_THRESHOLD_PERCENT)
            remove(KEY_POSE_QUALITY_MEAN_JITTER)
            remove(KEY_POSE_QUALITY_ANGLE_STANDARD_DEVIATION)

            metrics.currentVisibility?.let { putFloat(KEY_POSE_QUALITY_CURRENT, it.toFloat()) }
            metrics.averageVisibility?.let { putFloat(KEY_POSE_QUALITY_AVERAGE, it.toFloat()) }
            metrics.minimumVisibility?.let { putFloat(KEY_POSE_QUALITY_MINIMUM, it.toFloat()) }
            metrics.belowThresholdFramesPercent?.let {
                putFloat(KEY_POSE_QUALITY_BELOW_THRESHOLD_PERCENT, it.toFloat())
            }
            metrics.meanLandmarkJitter?.let {
                putFloat(KEY_POSE_QUALITY_MEAN_JITTER, it.toFloat())
            }
            metrics.angleStandardDeviation?.let {
                putFloat(KEY_POSE_QUALITY_ANGLE_STANDARD_DEVIATION, it.toFloat())
            }
        }
    }

    fun poseQualityMetrics(): PoseQualityMetricsSnapshot? {
        if (!prefs.contains(KEY_POSE_QUALITY_SAMPLES)) return null
        return PoseQualityMetricsSnapshot(
            currentVisibility = prefs.floatOrNull(KEY_POSE_QUALITY_CURRENT)?.toDouble(),
            averageVisibility = prefs.floatOrNull(KEY_POSE_QUALITY_AVERAGE)?.toDouble(),
            minimumVisibility = prefs.floatOrNull(KEY_POSE_QUALITY_MINIMUM)?.toDouble(),
            belowThresholdFramesPercent =
                prefs.floatOrNull(KEY_POSE_QUALITY_BELOW_THRESHOLD_PERCENT)?.toDouble(),
            meanLandmarkJitter = prefs.floatOrNull(KEY_POSE_QUALITY_MEAN_JITTER)?.toDouble(),
            angleStandardDeviation =
                prefs.floatOrNull(KEY_POSE_QUALITY_ANGLE_STANDARD_DEVIATION)?.toDouble(),
            sampleCount = prefs.getLong(KEY_POSE_QUALITY_SAMPLES, 0L)
        )
    }

    /** Clears only Pose Quality; repetition and performance metrics are untouched. */
    fun resetPoseQualityMetrics() {
        prefs.edit {
            remove(KEY_POSE_QUALITY_CURRENT)
            remove(KEY_POSE_QUALITY_AVERAGE)
            remove(KEY_POSE_QUALITY_MINIMUM)
            remove(KEY_POSE_QUALITY_BELOW_THRESHOLD_PERCENT)
            remove(KEY_POSE_QUALITY_MEAN_JITTER)
            remove(KEY_POSE_QUALITY_ANGLE_STANDARD_DEVIATION)
            remove(KEY_POSE_QUALITY_SAMPLES)
        }
    }

    private fun android.content.SharedPreferences.intOrNull(key: String): Int? =
        if (contains(key)) getInt(key, 0) else null

    private fun android.content.SharedPreferences.floatOrNull(key: String): Float? =
        if (contains(key)) getFloat(key, 0f) else null

    private companion object {
        const val KEY_METRICS_MODEL = "performance_model"
        const val KEY_METRICS_CONFIGURED_GPU = "performance_configured_gpu"
        const val KEY_METRICS_DELEGATE = "performance_delegate"
        const val KEY_METRICS_INPUT_WIDTH = "performance_input_width"
        const val KEY_METRICS_INPUT_HEIGHT = "performance_input_height"
        const val KEY_METRICS_CAMERA_FPS = "performance_camera_fps"
        const val KEY_METRICS_ANALYSIS_FPS = "performance_analysis_fps"
        const val KEY_METRICS_CURRENT_LATENCY = "performance_current_latency"
        const val KEY_METRICS_AVERAGE_LATENCY = "performance_average_latency"
        const val KEY_POSE_QUALITY_CURRENT = "pose_quality_current"
        const val KEY_POSE_QUALITY_AVERAGE = "pose_quality_average"
        const val KEY_POSE_QUALITY_MINIMUM = "pose_quality_minimum"
        const val KEY_POSE_QUALITY_BELOW_THRESHOLD_PERCENT =
            "pose_quality_below_threshold_percent"
        const val KEY_POSE_QUALITY_MEAN_JITTER = "pose_quality_mean_jitter"
        const val KEY_POSE_QUALITY_ANGLE_STANDARD_DEVIATION =
            "pose_quality_angle_standard_deviation"
        const val KEY_POSE_QUALITY_SAMPLES = "pose_quality_samples"
        const val LEGACY_LATENCY_REPORT = "latency_report"
    }
}
