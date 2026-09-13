package com.example.fitvisor__demo.settings

import android.content.Context

/**
 * Bundled pose model variants, ordered fastest -> most accurate.
 * A missing selected asset is an error; models are never substituted at runtime.
 */
enum class PoseModel(val assetPath: String) {
    LITE("pose_landmarker_lite.task"),
    FULL("pose_landmarker_full.task"),
    HEAVY("pose_landmarker_heavy.task");

    val displayName: String
        get() = when (this) {
            LITE -> "Lite"
            FULL -> "Full"
            HEAVY -> "Heavy"
        }

    internal fun requireAsset(assetExists: (String) -> Boolean): String {
        check(assetExists(assetPath)) {
            "$name model asset ($assetPath) is missing or unreadable. Pose detection unavailable; no other model was substituted."
        }
        return assetPath
    }

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

    /**
     * When true, the workout overlay draws the synced camera frame + skeleton.
     * When false, only the live camera preview is shown (no skeleton). Independent
     * of [debugEnabled].
     */
    var showSkeleton: Boolean
        get() = prefs.getBoolean(KEY_SHOW_SKELETON, DEFAULT_SHOW_SKELETON)
        set(value) { prefs.edit().putBoolean(KEY_SHOW_SKELETON, value).apply() }

    var debugEnabled: Boolean
        get() = prefs.getBoolean("debug_enabled", false)
        set(value) {
            val editor = prefs.edit().putBoolean("debug_enabled", value)
            if (!value) {
                editor.putBoolean("test_reps_enabled", false)
            }
            editor.apply()
        }

    var testRepsEnabled: Boolean
        get() = debugEnabled && prefs.getBoolean("test_reps_enabled", false)
        set(value) { prefs.edit().putBoolean("test_reps_enabled", value && debugEnabled).apply() }

    companion object {
        private const val PREFS_NAME = "fitvisor_settings"
        private const val KEY_USE_GPU = "use_gpu"
        private const val KEY_MODEL = "pose_model"
        private const val KEY_SHOW_SKELETON = "show_skeleton"

        private const val DEFAULT_USE_GPU = false
        private const val DEFAULT_SHOW_SKELETON = true
    }
}
