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
 *                                           Only checked once the elbow is
 *                                           near lockout (>= [ARM_VERTICAL_CHECK_MIN_ELBOW]):
 *                                           at the racked START position the
 *                                           wrist sits at shoulder height by
 *                                           definition, so this vector is
 *                                           naturally far from vertical there.
 *  - [RepError.ASYMMETRIC_ARM_POSITION]     left/right elbow angle differ by
 *                                           more than 12 deg (8-12 deg
 *                                           tolerated). Only checked once a
 *                                           press is underway (elbow angle >
 *                                           [START_ELBOW_MAX]), not while racked.
 *  - [RepError.INSUFFICIENT_ELBOW_EXTENSION] a genuine press attempt (PRESSING
 *                                           sustained for [PRESSING_SUSTAIN_FRAMES]
 *                                           consecutive frames) returns to
 *                                           the rack without ever reaching
 *                                           lockout. "Press started" is
 *                                           measured relative to the user's
 *                                           own rack angle (see
 *                                           [pressStartThreshold]), so a
 *                                           half press is caught, not just
 *                                           one that stalls just short of
 *                                           lockout. A brief blip that never
 *                                           sustains is still silently
 *                                           discarded, not scored.
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
        // Only checked once the arm is close to lockout (see armsNearTop in
        // processFrame) -- at the racked bottom the wrist sits at shoulder
        // height by design, so the same vector reads as ~80-90 deg there.
        private const val ARMS_VERTICAL_VIOLATION_MIN = 30.0
        private const val ARMS_VERTICAL_CONSEC_FRAMES = 3
        private const val ARM_VERTICAL_CHECK_MIN_ELBOW = 140.0

        // Left/right elbow-angle symmetry. <=8 deg difference is fine, 8-12 is
        // a tolerated grey zone, >12 deg is a real asymmetry fault. Lowered
        // from 25 (grey zone 20-25) -- that required a very exaggerated,
        // almost unrealistic imbalance before ever flagging anything.
        private const val ELBOW_ASYMMETRY_VIOLATION_MIN = 12.0
        private const val ASYMMETRY_CONSEC_FRAMES = 2

        // A press attempt must sustain PRESSING for this many consecutive
        // frames before a return to the rack without lockout is scored as a
        // fault rather than treated as harmless jitter right at the rack
        // boundary. The fastest possible "stable return to the rack" from
        // PRESSING already takes 3 total PRESSING-state frames (entry frame
        // + the 2 consecutive frames rackReturnGate itself needs) -- setting
        // this to 3 exactly filters that absolute-minimum bounce, while
        // anything held even one frame longer (i.e. any real, if brief,
        // press) still gets scored.
        private const val PRESSING_SUSTAIN_FRAMES = 3

        // Partial-press detection, relative to the user's own rack angle
        // (rackElbow). START_ELBOW_MAX alone used to be the only "press
        // started" boundary, but a half press (elbow ~115-130) keeps the
        // elbows inside the shoulder-height tolerance and under 135, so it
        // was indistinguishable from still being racked: it was neither
        // counted nor faulted, and INSUFFICIENT_ELBOW_EXTENSION could only
        // ever fire in the 135-150 strip right below lockout.
        //
        // A press has started once the elbow opens PRESS_START_RISE past the
        // rack angle, and is back at the rack once within PRESS_START_RISE -
        // RACK_RETURN_GAP of it (a 10 deg hysteresis gap). Relative rather
        // than absolute on purpose: after a normal rep completes on the way
        // down (~130), a slow lowering would otherwise cross a fixed
        // threshold while already back in START, register as a new press,
        // and fault on reaching the rack -- a phantom incorrect rep after
        // every slow rep. The learned rack angle follows the lowering down
        // instead, and it also adapts to wider/narrower grips.
        private const val PRESS_START_RISE = 20.0
        private const val RACK_RETURN_GAP = 10.0

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

    /**
     * How many consecutive frames [currentState] has been [State.PRESSING].
     * Used only to tell a genuine (if shallow) press attempt apart from a
     * single noisy frame crossing [START_ELBOW_MAX] while otherwise racked --
     * the state machine never consults it.
     */
    private val pressingGate = ConsecutiveGate(PRESSING_SUSTAIN_FRAMES)

    /**
     * The user's rack angle for the current START phase: the lowest elbow
     * angle held for 2 consecutive racked frames, so a single low outlier
     * frame can't drag it down. NaN until learned; re-learned on every return
     * to START. See [pressStartThreshold].
     */
    private var rackElbow = Double.NaN
    private var prevRackedElbow = Double.NaN

    /** Debounces the relative "press started" / "back at the rack" boundaries. */
    private val pressStartGate = ConsecutiveGate()
    private val rackReturnGate = ConsecutiveGate()

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
        // TODO(known bug, deferred 2026-09-26 while mid on-device testing):
        //  arms hanging straight down at the sides also satisfy this (elbow
        //  ~170), so after a set has started, dropping the arms and then
        //  re-racking is counted as a *correct* rep (START -> PRESSING ->
        //  TOP_REACHED -> LOWERING -> START). ARMS_NOT_VERTICAL can't catch
        //  it: angleFromVertical uses abs(deltaY), so arms pointing down read
        //  as perfectly vertical. Planned fix:
        //   1. topPose also requires the elbows above the shoulders
        //      (elbowShoulderVertical < 0); at a real lockout they're well
        //      above, when hanging well below.
        //   2. The legacy START -> PRESSING path
        //      (!startPose && elbowAngle > START_ELBOW_MAX) also requires
        //      elbowNotBelowShoulder, like the relative path already does --
        //      otherwise fix 1 just turns the phantom correct rep into a
        //      phantom INSUFFICIENT_ELBOW_EXTENSION one.
        //   3. Regression tests: drop arms + re-rack (95 -> 160 -> 170 with
        //      elbowShoulderVertical ~+0.2 -> 95) must not complete a rep;
        //      also arms straight out to the sides (T-pose).
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

        // Learn the rack angle while racked in START (see rackElbow).
        if (currentState == State.START && startPose) {
            if (!prevRackedElbow.isNaN()) {
                rackElbow = minKeepNaN(rackElbow, maxOf(elbowAngle, prevRackedElbow))
            }
            prevRackedElbow = elbowAngle
        } else {
            prevRackedElbow = Double.NaN
        }
        val pressStart = pressStartThreshold()
        // A press moves the elbows up; arms lowered toward the sides can open
        // the elbow too, so that must never read as a press.
        val elbowNotBelowShoulder =
            !elbowShoulderVertical.isNaN() && elbowShoulderVertical <= heightTol
        val stablePressStart = pressStartGate.update(
            !elbowAngle.isNaN() && elbowAngle > pressStart && elbowNotBelowShoulder
        )
        val stableRackReturn = rackReturnGate.update(
            elbowAtShoulder && !elbowAngle.isNaN() && elbowAngle <= pressStart - RACK_RETURN_GAP
        )

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
        // other stays vertical must still be caught. Only evaluated once the
        // arm is meaningfully extended (near the top): at the racked START
        // position the wrist sits *at shoulder height by definition*, so the
        // shoulder->wrist vector is naturally near-horizontal there -- judging
        // "vertical" against the rack position itself would trip on every
        // rep before a press even begins.
        val armsNearTop = !elbowAngle.isNaN() && elbowAngle >= ARM_VERTICAL_CHECK_MIN_ELBOW
        val armVertical = maxKeepNaN(leftArmVertical, rightArmVertical)
        val armsVerticalIssue =
            armsNearTop && !armVertical.isNaN() && armVertical > ARMS_VERTICAL_VIOLATION_MIN
        val armsVerticalTripped = if (armsNearTop) armsVerticalGate.update(armsVerticalIssue) else false
        if (repActive && armsVerticalTripped) errors.add(RepError.ARMS_NOT_VERTICAL)

        // Symmetry is meaningful once a real press is underway (past the
        // racked bottom, same boundary the state machine uses to leave
        // START) -- not while still racked, where both arms are expected to
        // sit close together anyway.
        val pastRack = !elbowAngle.isNaN() && elbowAngle > START_ELBOW_MAX
        val asymmetry =
            if (!leftElbowAngle.isNaN() && !rightElbowAngle.isNaN())
                abs(leftElbowAngle - rightElbowAngle)
            else Double.NaN
        val asymmetryIssue =
            pastRack && !asymmetry.isNaN() && asymmetry > ELBOW_ASYMMETRY_VIOLATION_MIN
        val asymmetryTripped = if (pastRack) asymmetryGate.update(asymmetryIssue) else false
        if (repActive && asymmetryTripped) errors.add(RepError.ASYMMETRIC_ARM_POSITION)

        if (repActive) {
            accumulateDebug(
                elbowAngle,
                torsoVerticalAngle,
                if (armsNearTop) armVertical else Double.NaN,
                if (pastRack) asymmetry else Double.NaN
            )
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

        // Sustained-PRESSING signal for the "abandoned press" check below,
        // read using the state as of the *start* of this frame (before any
        // transition below takes effect) -- see pressingGate's doc.
        val pressingSustained = pressingGate.update(currentState == State.PRESSING)

        when (currentState) {
            State.START -> {
                if (repActive && stableTop) {
                    currentState = State.TOP_REACHED
                    topReached = true
                    topElbow = elbowAngle
                } else if (repActive &&
                    ((!startPose && elbowAngle > START_ELBOW_MAX) || stablePressStart)
                ) {
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
                    stableRackReturn -> {
                        // Returned to the rack without ever reaching lockout.
                        // Only score this as a faulted repetition if PRESSING
                        // was actually sustained for a couple of frames;
                        // otherwise it's a brief blip past the press-start
                        // boundary and stays uncounted, as before.
                        if (!topReached && pressingSustained) {
                            errors.add(RepError.INSUFFICIENT_ELBOW_EXTENSION)
                            isRepCompleted = true
                            isRepCorrect = errors.isEmpty()
                            resultWarning = completionWarning()
                        }
                        currentState = State.START
                        resetRackTracking()
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

    /**
     * Elbow angle past which a press counts as started: [PRESS_START_RISE]
     * above the learned [rackElbow], capped at [START_ELBOW_MAX] (the old
     * fixed boundary, so a very open rack is never worse off than before).
     * Falls back to [START_ELBOW_MAX] until the rack angle has been learned.
     */
    private fun pressStartThreshold(): Double =
        if (rackElbow.isNaN()) START_ELBOW_MAX
        else minOf(rackElbow + PRESS_START_RISE, START_ELBOW_MAX)

    private fun resetRackTracking() {
        rackElbow = Double.NaN
        prevRackedElbow = Double.NaN
        pressStartGate.reset()
        rackReturnGate.reset()
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
        pressingGate.reset()
        resetRackTracking()
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
        pressingGate.reset()
        resetRackTracking()
        errors.clear()
        frameRecorder.reset()
        resetDebug()
        startElbowShoulder = Double.NaN
        lastRepStartElbowShoulder = Double.NaN
    }
}
