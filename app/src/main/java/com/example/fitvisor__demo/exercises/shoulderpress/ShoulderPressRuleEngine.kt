package com.example.fitvisor__demo.exercises.shoulderpress

import com.example.fitvisor__demo.model.ExerciseAnalysisResult
import com.example.fitvisor__demo.model.RepDebugMetrics
import com.example.fitvisor__demo.model.RepError
import com.example.fitvisor__demo.model.RepFrameRecorder
import com.example.fitvisor__demo.model.maxKeepNaN
import com.example.fitvisor__demo.model.minKeepNaN
import com.example.fitvisor__demo.utils.ConsecutiveGate
import kotlin.math.abs

/**
 * Shoulder-press analysis.
 *
 * State machine:
 *
 *     START -> PRESSING -> TOP_REACHED -> LOWERING -> START (counts the rep)
 *
 * The intermediate states are important: a static pose can never repeatedly
 * complete a repetition. START and TOP also use mutually exclusive elbow-angle
 * bands and require two consecutive frames, preventing noisy/overlapping angle
 * readings from alternating between the endpoints while the user is holding.
 *
 *  - START requires bent arms *and* elbows approximately at shoulder height:
 *    `elbowAngle <= START_ELBOW_MAX` and
 *    `abs(elbow.y - shoulder.y) <= tolerance`, where the tolerance is
 *    *body-relative* (a fraction of the torso length) rather than a fixed pixel
 *    distance, so it holds across camera distances and resolutions.
 *  - TOP_REACHED is detected purely from elbow extension (`elbowAngle >= 150`).
 *
 * Repetition-*detection* (arming a rep, and knowing when one completes) is kept
 * separate from *technique validation*. A START-position problem (wrong elbow
 * height, arms not bent enough) only blocks a repetition from arming/completing
 * -- it is never itself scored as an incorrect repetition; the user is simply
 * guided back to a valid start. Once a repetition is armed, four independent,
 * debounced technique checks can each attach a sticky fault that survives to
 * the end of the repetition even if the user corrects before finishing:
 *
 *  - [RepError.EXCESSIVE_TORSO_LEAN]        torso tilts > 15 deg from vertical.
 *  - [RepError.ARMS_NOT_VERTICAL]           shoulder->wrist tilts > 30 deg from
 *                                           vertical (20-30 deg is a tolerated
 *                                           grey zone -- anatomy/camera noise).
 *  - [RepError.ASYMMETRIC_ARM_POSITION]     left/right elbow angle differ by
 *                                           more than 25 deg (20-25 deg
 *                                           tolerated).
 *  - [RepError.INSUFFICIENT_ELBOW_EXTENSION] a genuine press attempt (elbow
 *                                           angle cleared [PRESS_ATTEMPT_MIN_ELBOW])
 *                                           returns to START without ever
 *                                           reaching lockout. Small jitter
 *                                           around the START boundary that
 *                                           never clears that threshold is
 *                                           still silently discarded, not
 *                                           scored.
 *
 * A completed repetition is correct iff none of the above faults were raised
 * during it. [lastFailingRule] and [activeErrors] are exposed for temporary
 * on-device Logcat debugging; the state machine never consults them.
 */
class ShoulderPressRuleEngine {

    companion object {
        // START: "elbows approximately at shoulder height".
        // The tolerance is body-relative -- a fraction of the torso length
        // (|shoulder.y - hip.y|) -- so it scales with the person's apparent size
        // instead of being a fixed pixel/frame distance. It is floored so that a
        // small/distant subject (tiny torso) is not over-constrained.
        private const val START_HEIGHT_TOL_RATIO = 0.30   // fraction of torso length
        private const val START_HEIGHT_TOL_FLOOR = 0.10   // normalized frame-height floor

        // Keeps START mutually exclusive from a straight-arm lockout. Without
        // this condition a generous height tolerance can classify the same held
        // pose as both START and TOP on alternating frames.
        private const val START_ELBOW_MAX = 135.0

        // TOP_REACHED: elbows extended to (near) lockout.
        private const val TOP_ELBOW_MIN = 150.0

        // Torso must stay within this many degrees of vertical. Debounced by a
        // ConsecutiveGate; deliberately relaxed.
        private const val TORSO_VERTICAL_LIMIT = 15.0

        // Arm verticality (shoulder->wrist vs. vertical axis). <=20 deg is fine;
        // 20-30 deg is a tolerated grey zone (anatomy / camera-angle noise, not
        // scored); >30 deg is a real technique fault (pressing forward/outward).
        private const val ARMS_VERTICAL_VIOLATION_MIN = 30.0
        private const val ARMS_VERTICAL_CONSEC_FRAMES = 3

        // Left/right elbow-angle symmetry. <=20 deg difference is fine, 20-25 is
        // a tolerated grey zone, >25 deg is a real asymmetry fault.
        private const val ELBOW_ASYMMETRY_VIOLATION_MIN = 25.0
        private const val ASYMMETRY_CONSEC_FRAMES = 3

        // A press must clear this elbow angle -- comfortably past
        // START_ELBOW_MAX but short of TOP_ELBOW_MIN -- before a return to
        // START without lockout is scored as a fault rather than treated as
        // harmless jitter around the START boundary.
        private const val PRESS_ATTEMPT_MIN_ELBOW = 145.0

        private const val WARNING_TORSO = "Keep your torso upright"
        private const val WARNING_ELBOWS_HEIGHT = "Bring elbows to shoulder height"
        private const val WARNING_ARMS_VERTICAL = "Press arms straight overhead"
        private const val WARNING_ASYMMETRY = "Keep both arms level"
        private const val WARNING_INSUFFICIENT_EXTENSION = "Extend your arms fully overhead"
    }

    enum class State { START, PRESSING, TOP_REACHED, LOWERING }

    private var currentState = State.START

    // A rep becomes "active" once a valid START (elbows at shoulder height) has
    // been observed, and stays active until the rep completes. Pressing to the
    // top is only allowed from an active (real) START, which prevents a phantom
    // rep if the session happens to begin with the arms already extended.
    private var sawStart = false
    private var topReached = false

    /**
     * Temporary on-device debug: a human-readable description of the condition
     * currently blocking a valid rep, recomputed every frame. Read by
     * [ShoulderPressAnalyzer] for Logcat only; the state machine never uses it.
     */
    var lastFailingRule: String = "none"
        private set

    /**
     * Debug-only snapshot of the faults accumulated so far *during the
     * in-progress* repetition (unlike [ExerciseAnalysisResult.errors], which is
     * only populated on the completing frame). Read by [ShoulderPressAnalyzer]
     * for Logcat only.
     */
    val activeErrors: Set<RepError> get() = errors.toSet()

    private val torsoGate = ConsecutiveGate()
    private val startPoseGate = ConsecutiveGate()
    private val topPoseGate = ConsecutiveGate()
    private val armsVerticalGate = ConsecutiveGate(ARMS_VERTICAL_CONSEC_FRAMES)
    private val asymmetryGate = ConsecutiveGate(ASYMMETRY_CONSEC_FRAMES)

    private val errors = linkedSetOf<RepError>()
    private val frameRecorder = RepFrameRecorder()

    // Per-rep debug accumulators.
    private var minElbow = Double.NaN
    private var maxElbow = Double.NaN
    private var topElbow = Double.NaN
    private var maxTorso = Double.NaN
    private var maxArmVertical = Double.NaN
    private var maxAsymmetry = Double.NaN
    // Elbow->shoulder vertical difference captured at the START of the rep.
    private var startElbowShoulder = Double.NaN

    /**
     * Elbow->shoulder vertical difference captured at the START of the most
     * recently completed rep. Precise value for the Logcat debug channel (the
     * summary shows the coarser angle aggregates only). Never overwritten by
     * [finishRep], so it stays valid after [processFrame] returns.
     */
    var lastRepStartElbowShoulder: Double = Double.NaN
        private set

    /**
     * @param elbowAngle shoulder-elbow-wrist, degrees. Averaged across
     *   whichever arm(s) are reliable -- still the basis for START/TOP
     *   detection.
     * @param torsoVerticalAngle torso deviation from vertical, degrees (0..90).
     * @param elbowShoulderVertical normalized signed vertical distance
     *   elbow->shoulder (image Y, 0..1). ~0 means the elbow is at shoulder
     *   height.
     * @param bodyScale normalized torso length (|shoulder.y - hip.y|), used to
     *   make the shoulder-height tolerance body-relative.
     * @param leftElbowAngle / [rightElbowAngle] per-side elbow angles, degrees.
     *   NaN when that side isn't reliable this frame. Used only for the
     *   left/right symmetry check.
     * @param leftArmVertical / [rightArmVertical] per-side shoulder->wrist
     *   deviation from vertical, degrees. NaN when that side isn't reliable
     *   this frame. Used only for the arm-verticality check.
     */
    fun processFrame(
        elbowAngle: Double,
        torsoVerticalAngle: Double,
        elbowShoulderVertical: Double,
        bodyScale: Double,
        leftElbowAngle: Double = Double.NaN,
        rightElbowAngle: Double = Double.NaN,
        leftArmVertical: Double = Double.NaN,
        rightArmVertical: Double = Double.NaN
    ): ExerciseAnalysisResult {

        var isRepCompleted = false
        var isRepCorrect = false
        var resultWarning: String? = null

        val heightTol = startHeightTolerance(bodyScale)
        val elbowAtShoulder =
            !elbowShoulderVertical.isNaN() && abs(elbowShoulderVertical) <= heightTol
        val startPose =
            elbowAtShoulder && !elbowAngle.isNaN() && elbowAngle <= START_ELBOW_MAX
        val topPose =
            !elbowAngle.isNaN() && elbowAngle >= TOP_ELBOW_MIN
        val stableStart = startPoseGate.update(startPose)
        val stableTop = topPoseGate.update(topPose)

        // Establish / refresh the START position only after a stable bent-arm
        // rack. A straight-arm top pose can therefore never arm a repetition.
        if (currentState == State.START && stableStart) {
            sawStart = true
            startElbowShoulder = elbowShoulderVertical
        }

        val repActive = sawStart

        // --- Sticky technique checks -------------------------------------
        // Each is debounced independently and, once tripped during an active
        // repetition, stays attached to it (the `errors` set is only cleared
        // in finishRep/reset) even if the user corrects before finishing.

        val torsoIssue =
            !torsoVerticalAngle.isNaN() && torsoVerticalAngle > TORSO_VERTICAL_LIMIT
        val torsoTripped = torsoGate.update(torsoIssue)
        if (repActive && torsoTripped) errors.add(RepError.EXCESSIVE_TORSO_LEAN)

        // Most-deviated arm wins: a single arm pressing forward while the
        // other stays vertical must still be caught.
        val armVertical = maxKeepNaN(leftArmVertical, rightArmVertical)
        val armsVerticalIssue =
            !armVertical.isNaN() && armVertical > ARMS_VERTICAL_VIOLATION_MIN
        val armsVerticalTripped = armsVerticalGate.update(armsVerticalIssue)
        if (repActive && armsVerticalTripped) errors.add(RepError.ARMS_NOT_VERTICAL)

        val asymmetry =
            if (!leftElbowAngle.isNaN() && !rightElbowAngle.isNaN())
                abs(leftElbowAngle - rightElbowAngle)
            else Double.NaN
        val asymmetryIssue =
            !asymmetry.isNaN() && asymmetry > ELBOW_ASYMMETRY_VIOLATION_MIN
        val asymmetryTripped = asymmetryGate.update(asymmetryIssue)
        if (repActive && asymmetryTripped) errors.add(RepError.ASYMMETRIC_ARM_POSITION)

        if (repActive) {
            accumulateDebug(elbowAngle, torsoVerticalAngle, armVertical, asymmetry)
            frameRecorder.record(currentState.name) {
                linkedMapOf(
                    "elbow" to elbowAngle,
                    "torso" to torsoVerticalAngle,
                    "elbowShoulder" to elbowShoulderVertical,
                    "armVertical" to armVertical,
                    "asymmetry" to asymmetry
                )
            }
        }

        when (currentState) {
            State.START -> {
                if (repActive && stableTop) {
                    currentState = State.TOP_REACHED
                    topReached = true
                    topElbow = elbowAngle
                } else if (repActive && !startPose && elbowAngle > START_ELBOW_MAX) {
                    currentState = State.PRESSING
                }
            }

            State.PRESSING -> {
                when {
                    stableTop -> {
                        currentState = State.TOP_REACHED
                        topReached = true
                        topElbow = elbowAngle
                    }
                    stableStart -> {
                        // Returned to START without ever reaching lockout.
                        // Only score this as a faulted repetition if a real
                        // press was underway (elbow cleared
                        // PRESS_ATTEMPT_MIN_ELBOW); otherwise it's jitter
                        // around the START boundary and stays uncounted, as
                        // before.
                        if (!topReached && maxElbow >= PRESS_ATTEMPT_MIN_ELBOW) {
                            errors.add(RepError.INSUFFICIENT_ELBOW_EXTENSION)
                            isRepCompleted = true
                            isRepCorrect = errors.isEmpty()
                            resultWarning = completionWarning()
                        }
                        currentState = State.START
                    }
                }
            }

            State.TOP_REACHED -> {
                when {
                    stableStart -> {
                        isRepCompleted = true
                        isRepCorrect = errors.isEmpty()
                        resultWarning = completionWarning()
                    }
                    !topPose -> currentState = State.LOWERING
                }
            }

            State.LOWERING -> {
                if (stableStart) {
                    isRepCompleted = true
                    isRepCorrect = errors.isEmpty()
                    resultWarning = completionWarning()
                } else if (stableTop) {
                    // The user raised the arms again before returning to START.
                    currentState = State.TOP_REACHED
                }
            }
        }

        // Live corrective warning on non-completing frames.
        if (!isRepCompleted) {
            resultWarning = when {
                repActive && torsoTripped -> WARNING_TORSO
                repActive && armsVerticalTripped -> WARNING_ARMS_VERTICAL
                repActive && asymmetryTripped -> WARNING_ASYMMETRY
                currentState == State.START && !sawStart && !startPose ->
                    WARNING_ELBOWS_HEIGHT
                else -> null
            }
        }

        // Debug-only: the single condition currently blocking a valid rep.
        lastFailingRule = when {
            repActive && torsoTripped ->
                "torso lean: %.1f > %.1f".format(torsoVerticalAngle, TORSO_VERTICAL_LIMIT)
            repActive && armsVerticalTripped ->
                "arms not vertical: %.1f > %.1f".format(armVertical, ARMS_VERTICAL_VIOLATION_MIN)
            repActive && asymmetryTripped ->
                "asymmetry: %.1f > %.1f".format(asymmetry, ELBOW_ASYMMETRY_VIOLATION_MIN)
            currentState == State.START && !startPose ->
                "start not reached: elbow=%.1f (max %.1f), height |%.3f| (max %.3f)".format(
                    elbowAngle, START_ELBOW_MAX, elbowShoulderVertical, heightTol)
            else -> "none"
        }

        val completedErrors: Set<RepError>
        val completedMetrics: RepDebugMetrics?
        if (isRepCompleted) {
            completedErrors = errors.toSet()
            completedMetrics = buildDebugMetrics()
            lastRepStartElbowShoulder = startElbowShoulder
            finishRep(elbowShoulderVertical, startPose)
        } else {
            completedErrors = emptySet()
            completedMetrics = null
        }

        return ExerciseAnalysisResult(
            isRepCompleted = isRepCompleted,
            isRepCorrect = isRepCorrect,
            warning = resultWarning,
            phaseName = currentState.name,
            errors = completedErrors,
            debugMetrics = completedMetrics
        )
    }

    /** Highest-priority explanation for a just-completed, incorrect repetition. */
    private fun completionWarning(): String? = when {
        errors.isEmpty() -> null
        RepError.EXCESSIVE_TORSO_LEAN in errors -> WARNING_TORSO
        RepError.ARMS_NOT_VERTICAL in errors -> WARNING_ARMS_VERTICAL
        RepError.ASYMMETRIC_ARM_POSITION in errors -> WARNING_ASYMMETRY
        RepError.INSUFFICIENT_ELBOW_EXTENSION in errors -> WARNING_INSUFFICIENT_EXTENSION
        else -> null
    }

    /** Body-relative shoulder-height tolerance, floored for small/distant subjects. */
    private fun startHeightTolerance(bodyScale: Double): Double {
        val ratioTol =
            if (!bodyScale.isNaN() && bodyScale > 0.0) START_HEIGHT_TOL_RATIO * bodyScale
            else 0.0
        return maxOf(ratioTol, START_HEIGHT_TOL_FLOOR)
    }

    private fun accumulateDebug(
        elbowAngle: Double,
        torsoVerticalAngle: Double,
        armVertical: Double,
        asymmetry: Double
    ) {
        minElbow = minKeepNaN(minElbow, elbowAngle)
        maxElbow = maxKeepNaN(maxElbow, elbowAngle)
        maxTorso = maxKeepNaN(maxTorso, torsoVerticalAngle)
        maxArmVertical = maxKeepNaN(maxArmVertical, armVertical)
        maxAsymmetry = maxKeepNaN(maxAsymmetry, asymmetry)
    }

    private fun buildDebugMetrics(): RepDebugMetrics = RepDebugMetrics(
        values = linkedMapOf(
            "minElbowAngle" to minElbow,
            "maxElbowAngle" to maxElbow,
            "topElbowAngle" to topElbow,
            "maxTorsoAngle" to maxTorso,
            "maxArmVerticalAngle" to maxArmVertical,
            "maxAsymmetry" to maxAsymmetry
        ),
        frameTrace = frameRecorder.snapshot(),
        // Non-angle state; the precise start elbow->shoulder difference is
        // logged to Logcat separately.
        flags = linkedMapOf(
            "startDetected" to sawStart,
            "topReached" to topReached
        )
    )

    private fun finishRep(returnElbowShoulder: Double, atStartPose: Boolean) {
        currentState = State.START
        torsoGate.reset()
        startPoseGate.reset()
        topPoseGate.reset()
        armsVerticalGate.reset()
        asymmetryGate.reset()
        errors.clear()
        frameRecorder.reset()
        resetDebug()
        topReached = false
        // The completing frame is itself a valid START for the next rep, so the
        // next rep is armed immediately from this shoulder-height reading.
        sawStart = atStartPose
        startElbowShoulder = if (atStartPose) returnElbowShoulder else Double.NaN
    }

    private fun resetDebug() {
        minElbow = Double.NaN
        maxElbow = Double.NaN
        topElbow = Double.NaN
        maxTorso = Double.NaN
        maxArmVertical = Double.NaN
        maxAsymmetry = Double.NaN
        // startElbowShoulder is (re)assigned by finishRep from the completing frame.
    }

    fun reset() {
        currentState = State.START
        sawStart = false
        topReached = false
        lastFailingRule = "none"
        torsoGate.reset()
        startPoseGate.reset()
        topPoseGate.reset()
        armsVerticalGate.reset()
        asymmetryGate.reset()
        errors.clear()
        frameRecorder.reset()
        resetDebug()
        startElbowShoulder = Double.NaN
        lastRepStartElbowShoulder = Double.NaN
    }
}
