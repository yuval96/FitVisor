package com.example.fitvisor__demo

/**
 * Structured, exercise-agnostic representation of a technique fault detected
 * during a single repetition. Kept independent from display text: the [message]
 * is the human-readable form for the summary UI, while the enum constant is what
 * the engines and tests reason about.
 *
 * IMPORTANT: only faults that correspond to a validation the code actually runs
 * are represented here. Currently-disabled or not-yet-implemented checks are
 * deliberately omitted so a rep is never annotated with a fault the app cannot
 * really detect:
 *  - Squat knee alignment is disabled -> no KNEE_ALIGNMENT.
 *  - Push-up top-orientation is not implemented -> no INVALID_TOP_ORIENTATION.
 *  - Biceps-curl lowering is not validated (the rep counts on the way up) -> no
 *    INCOMPLETE_LOWERING.
 */
enum class RepError(val message: String) {

    // Squat + Push-up (and shoulder press "did not extend").
    INSUFFICIENT_DEPTH("Did not reach sufficient depth"),

    // Squat / Shoulder press torso lean.
    EXCESSIVE_TORSO_LEAN("Torso leaned too far forward"),

    // Push-up body linearity.
    BODY_NOT_STRAIGHT("Body alignment was not straight"),
    INVALID_BOTTOM_ORIENTATION("Body was not level at the bottom"),

    // Shoulder press.
    INVALID_START_POSITION("Did not start from a valid low position"),
    ELBOW_NOT_AT_SHOULDER_HEIGHT("Elbows were not at shoulder height"),
    INVALID_UPPER_ARM_ANGLE("Upper arms were at the wrong angle"),
    ARMS_NOT_VERTICAL("Arms were not pressed vertically"),
    INSUFFICIENT_ELBOW_EXTENSION("Elbows were not fully extended"),

    // Biceps curl.
    INCOMPLETE_CURL("Did not curl high enough"),
    EXCESSIVE_TORSO_MOVEMENT("Torso moved too much"),
    EXCESSIVE_UPPER_ARM_MOVEMENT("Upper arm moved away from the body");
}
