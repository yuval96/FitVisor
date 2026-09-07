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
            refreshDebugControls()
        }
        binding.latencySwitch.setOnCheckedChangeListener { _, enabled -> settings.latencyEnabled = enabled }
        binding.resetLatency.setOnClickListener {
            DebugDataStore(this).resetLatency()
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
        }

        // Pose model selection.
        binding.modelGroup.check(radioIdFor(settings.model))
        binding.modelGroup.setOnCheckedChangeListener { _, checkedId ->
            settings.model = modelFor(checkedId)
        }
    }

    private fun radioIdFor(model: PoseModel): Int = when (model) {
        PoseModel.LITE -> binding.modelLite.id
        PoseModel.FULL -> binding.modelFull.id
        PoseModel.HEAVY -> binding.modelHeavy.id
    }

    private fun refreshDebugControls() {
        binding.debugControls.visibility = if (settings.debugEnabled) View.VISIBLE else View.GONE
        binding.latencySwitch.isChecked = settings.latencyEnabled
        binding.latencyReport.text = DebugDataStore(this).latencyReport()
        RepTrackingPanel.bind(binding.repTrackingPanel.root)
    }

    private fun modelFor(checkedId: Int): PoseModel = when (checkedId) {
        binding.modelLite.id -> PoseModel.LITE
        binding.modelFull.id -> PoseModel.FULL
        else -> PoseModel.HEAVY
    }
}
