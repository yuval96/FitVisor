package com.example.fitvisor__demo

import android.content.Intent
import android.widget.RadioGroup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitvisor__demo.settings.AppSettings
import com.example.fitvisor__demo.settings.PoseModel
import com.example.fitvisor__demo.ui.settings.SettingsActivity
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.poselandmarker.PoseLandmarker
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Requires a device/emulator; verifies the real Settings wiring and native SDK. */
@RunWith(AndroidJUnit4::class)
class PoseModelsInstrumentedTest {
    @Test fun settingsRadioButtonsPersistTheCorrespondingModel() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val settings = AppSettings(context)
        val original = settings.model
        val activity = instrumentation.startActivitySync(
            Intent(context, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        try {
            instrumentation.runOnMainSync {
                val group = activity.findViewById<RadioGroup>(R.id.modelGroup)
                for ((id, model) in listOf(
                    R.id.modelLite to PoseModel.LITE,
                    R.id.modelFull to PoseModel.FULL,
                    R.id.modelHeavy to PoseModel.HEAVY
                )) {
                    group.check(id)
                    assertEquals(model, AppSettings(context).model)
                }
            }
        } finally {
            instrumentation.runOnMainSync { activity.finish() }
            settings.model = original
        }
    }

    @Test fun everyPackagedModelInitializesWithTheExistingNativeSdkOnCpu() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (model in PoseModel.values()) {
            val detector = PoseLandmarker.createFromOptions(context,
                PoseLandmarker.PoseLandmarkerOptions.builder()
                    .setBaseOptions(BaseOptions.builder()
                        .setModelAssetPath(model.assetPath)
                        .setDelegate(Delegate.CPU).build())
                    .setRunningMode(RunningMode.IMAGE).build())
            detector.close()
        }
    }
}
