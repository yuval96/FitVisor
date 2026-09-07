package com.example.fitvisor__demo

import android.content.Context

/**
 * Pose model variants, ordered fastest -> most accurate. Only [HEAVY] ships in
 * assets by default; selecting [LITE] or [FULL] requires the matching `.task`
 * file to be added to `app/src/main/assets`. [PoseLandmarkerHelper] falls back
 * to [HEAVY] at runtime if the chosen file is missing.
 */
enum class PoseModel(val assetPath: String) {
    LITE("pose_landmarker_lite.task"),
    FULL("pose_landmarker_full.task"),
    HEAVY("pose_landmarker_heavy.task");

    companion object {
        /** Parses a stored enum name, defaulting to [HEAVY] for unknown/null. */
        fun fromNameOrDefault(name: String?): PoseModel =
            values().firstOrNull { it.name == name } ?: HEAVY
    }
}

/**
 * Lightweight, persistent user settings for the pose-detection pipeline
 * (SharedPreferences-backed). These are read when a workout's
 * [PoseLandmarkerHelper] is created, so a change takes effect on the next
 * workout the user starts.
 *
 * Defaults intentionally preserve the app's original behavior: CPU delegate and
 * the heavy model.
 */
class AppSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** When true, the pose detector uses the GPU delegate (CPU fallback on failure). */
    var useGpu: Boolean
        get() = prefs.getBoolean(KEY_USE_GPU, DEFAULT_USE_GPU)
        set(value) { prefs.edit().putBoolean(KEY_USE_GPU, value).apply() }

    /** Selected pose model variant. */
    var model: PoseModel
        get() = PoseModel.fromNameOrDefault(prefs.getString(KEY_MODEL, null))
        set(value) { prefs.edit().putString(KEY_MODEL, value.name).apply() }

    var debugEnabled: Boolean
        get() = prefs.getBoolean("debug_enabled", false)
        set(value) {
            val editor = prefs.edit().putBoolean("debug_enabled", value)
            if (!value) {
                editor.putBoolean("latency_enabled", false)
                editor.putBoolean("test_reps_enabled", false)
            }
            editor.apply()
        }

    var latencyEnabled: Boolean
        get() = debugEnabled && prefs.getBoolean("latency_enabled", false)
        set(value) { prefs.edit().putBoolean("latency_enabled", value && debugEnabled).apply() }

    var testRepsEnabled: Boolean
        get() = debugEnabled && prefs.getBoolean("test_reps_enabled", false)
        set(value) { prefs.edit().putBoolean("test_reps_enabled", value && debugEnabled).apply() }

    companion object {
        private const val PREFS_NAME = "fitvisor_settings"
        private const val KEY_USE_GPU = "use_gpu"
        private const val KEY_MODEL = "pose_model"

        private const val DEFAULT_USE_GPU = false
    }
}
