package com.example.fitvisor__demo

/**
 * The exercises supported by FitVisor.
 *
 * Each type carries the user-facing display name and a short camera-position
 * instruction shown on the workout screen.
 */
enum class ExerciseType(
    val displayName: String,
    val cameraInstruction: String
) {
    SQUAT(
        "Squat",
        "Stand sideways with your full body visible"
    ),
    PUSH_UP(
        "Push-up",
        "Place the phone sideways so your full body is visible"
    ),
    SHOULDER_PRESS(
        "Shoulder Press",
        "Face the camera and keep your upper body visible"
    ),
    BICEPS_CURL(
        "Biceps Curl",
        "Stand sideways or slightly angled with your working arm visible"
    );

    companion object {
        /**
         * Parses an exercise from its [name]. Falls back to [SQUAT] when the
         * value is missing or does not match a known exercise, so the workout
         * screen can never be launched in an undefined state.
         */
        fun fromNameOrDefault(name: String?): ExerciseType {
            if (name == null) return SQUAT
            return values().firstOrNull { it.name == name } ?: SQUAT
        }
    }
}
