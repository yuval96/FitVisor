package com.example.fitvisor__demo.model

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
 *  - Shoulder-press START-position problems (wrong elbow height, arms not bent
 *    enough) are intentionally only a state-machine gate, not a scored fault:
 *    INVALID_START_POSITION, ELBOW_NOT_AT_SHOULDER_HEIGHT and
 *    INVALID_UPPER_ARM_ANGLE are declared for future use but never raised.
 *  - Squat has no *minimum* torso lean requirement -- staying upright through
 *    the whole rep is valid form -- so INSUFFICIENT_TORSO_LEAN is never
 *    raised either; it is kept only so old persisted workout history that
 *    recorded it (from before this was corrected) can still be decoded.
 */
enum class RepError(val message: String) {

    // Squat + Push-up.
    INSUFFICIENT_DEPTH("Did not reach sufficient depth"),

    // Squat / Shoulder press torso lean.
    EXCESSIVE_TORSO_LEAN("Torso leaned too far forward"),
    INSUFFICIENT_TORSO_LEAN("Torso did not lean forward enough"),

    // Push-up body linearity.
    BODY_NOT_STRAIGHT("Body alignment was not straight"),
    INVALID_BOTTOM_ORIENTATION("Body was not level at the bottom"),

    // Shoulder press.
    INVALID_START_POSITION("Did not start from a valid low position"),
    ELBOW_NOT_AT_SHOULDER_HEIGHT("Elbows were not at shoulder height"),
    INVALID_UPPER_ARM_ANGLE("Upper arms were at the wrong angle"),
    ARMS_NOT_VERTICAL("Arms were not pressed vertically"),
    INSUFFICIENT_ELBOW_EXTENSION("Elbows were not fully extended"),
    ASYMMETRIC_ARM_POSITION("Arms were not raised symmetrically"),

    // Biceps curl.
    INCOMPLETE_CURL("Did not curl high enough"),
    EXCESSIVE_TORSO_MOVEMENT("Torso moved too much"),
    EXCESSIVE_UPPER_ARM_MOVEMENT("Upper arm moved away from the body"),

    // Squat knee-over-toe.
    KNEES_PASS_TOES("Knees traveled too far past the toes");
}
