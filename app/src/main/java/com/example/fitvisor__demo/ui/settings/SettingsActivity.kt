package com.example.fitvisor__demo.ui.settings

import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.example.fitvisor__demo.databinding.ActivitySettingsBinding
import com.example.fitvisor__demo.settings.AppSettings
import com.example.fitvisor__demo.settings.DebugDataStore
import com.example.fitvisor__demo.settings.PoseModel
import com.example.fitvisor__demo.ui.BrandingInsets
import com.example.fitvisor__demo.ui.RepTrackingPanel
import java.util.Locale

/**
 * User settings for the pose-detection pipeline: GPU delegate toggle and pose
 * model selection. Changes are persisted immediately via [AppSettings] and take
 * effect the next time a workout (and its [PoseLandmarkerHelper]) starts.
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySettingsBinding
    private lateinit var settings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        BrandingInsets.applyNavySystemBars(this)
        BrandingInsets.padForSystemBars(binding.settingsRoot)

        settings = AppSettings(this)

        binding.debugSwitch.isChecked = settings.debugEnabled
        binding.debugSwitch.setOnCheckedChangeListener { _, enabled ->
            settings.debugEnabled = enabled
            if (!enabled) {
                DebugDataStore(this).apply {
                    resetPerformanceMetrics()
                    resetPoseQualityMetrics()
                }
            }
            refreshDebugControls()
        }
        refreshDebugControls()

        binding.backButton.setOnClickListener { finish() }

        // Skeleton overlay toggle (independent of debug tools).
        binding.skeletonSwitch.isChecked = settings.showSkeleton
        binding.skeletonSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.showSkeleton = isChecked
        }

        // GPU delegate toggle.
        binding.gpuSwitch.isChecked = settings.useGpu
        binding.gpuSwitch.setOnCheckedChangeListener { _, isChecked ->
            settings.useGpu = isChecked
            invalidatePerformanceMetrics()
        }

        // Pose model selection.
        binding.modelGroup.check(radioIdFor(settings.model))
        binding.modelGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.model = modelFor(checkedId)
            invalidatePerformanceMetrics()
        }
        binding.poseQualityPanel.resetPoseQualityStatistics.setOnClickListener {
            DebugDataStore(this).resetPoseQualityMetrics()
            refreshPoseQualityMetrics()
        }
    }

    private fun radioIdFor(model: PoseModel): Int = when (model) {
        PoseModel.LITE -> binding.modelLite.id
        PoseModel.FULL -> binding.modelFull.id
        PoseModel.HEAVY -> binding.modelHeavy.id
    }

    private fun refreshDebugControls() {
        binding.debugControls.visibility = if (settings.debugEnabled) View.VISIBLE else View.GONE
        refreshPerformanceMetrics()
        refreshPoseQualityMetrics()
        RepTrackingPanel.bind(binding.repTrackingPanel.root)
    }

    private fun refreshPoseQualityMetrics() {
        val metrics = DebugDataStore(this).poseQualityMetrics()
        binding.poseQualityPanel.poseQualityCurrentVisibilityValue.text =
            formatVisibility(metrics?.currentVisibility)
        binding.poseQualityPanel.poseQualityAverageVisibilityValue.text =
            formatVisibility(metrics?.averageVisibility)
        binding.poseQualityPanel.poseQualityMeanJitterValue.text = metrics?.meanLandmarkJitter?.let {
            String.format(Locale.US, "%.4f", it)
        } ?: PLACEHOLDER
        binding.poseQualityPanel.poseQualityAngleStandardDeviationValue.text =
            metrics?.angleStandardDeviation?.let {
                String.format(Locale.US, "%.1f°", it)
            } ?: PLACEHOLDER
        binding.poseQualityPanel.poseQualityMinimumVisibilityValue.text =
            formatVisibility(metrics?.minimumVisibility)
        binding.poseQualityPanel.poseQualityBelowThresholdValue.text =
            metrics?.belowThresholdFramesPercent?.let {
                String.format(Locale.US, "%.1f%%", it)
            } ?: PLACEHOLDER
        binding.poseQualityPanel.poseQualitySamplesValue.text =
            String.format(Locale.US, "%d", metrics?.sampleCount ?: 0L)
    }

    private fun refreshPerformanceMetrics() {
        val metrics = DebugDataStore(this).performanceMetrics()?.takeIf {
            it.model == settings.model.displayName && it.configuredUseGpu == settings.useGpu
        }
        binding.performanceModelValue.text = settings.model.displayName
        binding.performanceDelegateValue.text = metrics?.delegate ?: PLACEHOLDER
        binding.performanceResolutionValue.text =
            if (metrics?.inputWidth != null && metrics.inputHeight != null) {
                "${metrics.inputWidth} × ${metrics.inputHeight}"
            } else {
                PLACEHOLDER
            }
        binding.performanceCameraFpsValue.text = formatFps(metrics?.cameraFps)
        binding.performanceAnalysisFpsValue.text = formatFps(metrics?.analysisFps)
        binding.performanceCurrentLatencyValue.text = formatLatency(metrics?.currentLatencyMs)
        binding.performanceAverageLatencyValue.text = formatLatency(metrics?.averageLatencyMs)
    }

    private fun invalidatePerformanceMetrics() {
        DebugDataStore(this).apply {
            resetPerformanceMetrics()
            resetPoseQualityMetrics()
        }
        refreshPerformanceMetrics()
        refreshPoseQualityMetrics()
    }

    private fun formatVisibility(value: Double?): String =
        value?.let { String.format(Locale.US, "%.2f", it) } ?: PLACEHOLDER

    private fun formatFps(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f", it) } ?: PLACEHOLDER

    private fun formatLatency(value: Double?): String =
        value?.let { String.format(Locale.US, "%.0f ms", it) } ?: PLACEHOLDER

    private fun modelFor(checkedId: Int): PoseModel = when (checkedId) {
        binding.modelLite.id -> PoseModel.LITE
        binding.modelFull.id -> PoseModel.FULL
        else -> PoseModel.HEAVY
    }

    private companion object {
        const val PLACEHOLDER = "-"
    }
}
