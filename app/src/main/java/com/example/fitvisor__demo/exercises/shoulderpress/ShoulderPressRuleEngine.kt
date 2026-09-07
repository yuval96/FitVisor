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
 * Repetition-state detection (above) is kept separate from technique validation.
 * The only retained posture check is torso uprightness, which is applied with a
 * relaxed, debounced threshold and only affects whether a *completed* rep is
 * marked correct -- it never blocks the state machine or the rep count.
 *
 * Inputs per frame:
 *  - [elbowAngle]            shoulder-elbow-wrist, degrees.
 *  - [torsoVerticalAngle]    torso deviation from vertical, degrees (0..90).
 *  - [elbowShoulderVertical] normalized signed vertical distance elbow->shoulder
 *                            (image Y, 0..1). ~0 means the elbow is at shoulder
 *                            height.
 *  - [bodyScale]             normalized torso length (|shoulder.y - hip.y|), used
 *                            to make the shoulder-height tolerance body-relative.
 *
 * The previous strict rules -- a racked `upper arm / torso ~= low band`, a
 * `low elbow angle` band, and a `vertical press` angle at the top -- were the
 * source of false negatives on valid presses and have been removed as rep /
 * correctness criteria. [lastFailingRule] is exposed for temporary on-device
 * Logcat debugging; the state machine never consults it.
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

        // Secondary form check only: torso must stay within this many degrees of
        // vertical. Debounced by a ConsecutiveGate and deliberately relaxed -- it
        // decides correct/incorrect for a completed rep, never the rep count.
        private const val TORSO_VERTICAL_LIMIT = 15.0

        private const val WARNING_TORSO = "Keep your torso upright"
        private const val WARNING_ELBOWS_HEIGHT = "Bring elbows to shoulder height"
    }

    enum class State { START, PRESSING, TOP_REACHED, LOWERING }

    private var currentState = State.START

    // A rep becomes "active" once a valid START (elbows at shoulder height) has
    // been observed, and stays active until the rep completes. Pressing to the
    // top is only allowed from an active (real) START, which prevents a phantom
    // rep if the session happens to begin with the arms already extended.
    private var sawStart = false
    private var topReached = false
    private var torsoViolated = false

    /**
     * Temporary on-device debug: a human-readable description of the condition
     * currently blocking a valid rep, recomputed every frame. Read by
     * [ShoulderPressAnalyzer] for Logcat only; the state machine never uses it.
     */
    var lastFailingRule: String = "none"
        private set

    private val torsoGate = ConsecutiveGate()
    private val startPoseGate = ConsecutiveGate()
    private val topPoseGate = ConsecutiveGate()

    private val errors = linkedSetOf<RepError>()
    private val frameRecorder = RepFrameRecorder()

    // Per-rep debug accumulators.
    private var minElbow = Double.NaN
    private var maxElbow = Double.NaN
    private var topElbow = Double.NaN
    private var maxTorso = Double.NaN
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

    fun processFrame(
        elbowAngle: Double,
        torsoVerticalAngle: Double,
        elbowShoulderVertical: Double,
        bodyScale: Double
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

        // Torso posture (secondary form check only), debounced so a single jittery
        // frame cannot fail a rep.
        val torsoIssue =
            !torsoVerticalAngle.isNaN() && torsoVerticalAngle > TORSO_VERTICAL_LIMIT
        val torsoTripped = torsoGate.update(torsoIssue)
        if (repActive && torsoTripped) {
            torsoViolated = true
            errors.add(RepError.EXCESSIVE_TORSO_LEAN)
        }

        if (repActive) {
            accumulateDebug(elbowAngle, torsoVerticalAngle)
            frameRecorder.record(currentState.name) {
                linkedMapOf(
                    "elbow" to elbowAngle,
                    "torso" to torsoVerticalAngle,
                    "elbowShoulder" to elbowShoulderVertical
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
                    stableStart -> currentState = State.START // incomplete attempt
                }
            }

            State.TOP_REACHED -> {
                when {
                    stableStart -> {
                        isRepCompleted = true
                        isRepCorrect = !torsoViolated
                        resultWarning = if (!isRepCorrect) WARNING_TORSO else null
                    }
                    !topPose -> currentState = State.LOWERING
                }
            }

            State.LOWERING -> {
                if (stableStart) {
                    isRepCompleted = true
                    isRepCorrect = !torsoViolated
                    resultWarning = if (!isRepCorrect) WARNING_TORSO else null
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
                currentState == State.START && !sawStart && !startPose ->
                    WARNING_ELBOWS_HEIGHT
                else -> null
            }
        }

        // Debug-only: the single condition currently blocking a valid rep.
        lastFailingRule = when {
            repActive && torsoTripped ->
                "torso lean: %.1f > %.1f".format(torsoVerticalAngle, TORSO_VERTICAL_LIMIT)
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

    /** Body-relative shoulder-height tolerance, floored for small/distant subjects. */
    private fun startHeightTolerance(bodyScale: Double): Double {
        val ratioTol =
            if (!bodyScale.isNaN() && bodyScale > 0.0) START_HEIGHT_TOL_RATIO * bodyScale
            else 0.0
        return maxOf(ratioTol, START_HEIGHT_TOL_FLOOR)
    }

    private fun accumulateDebug(elbowAngle: Double, torsoVerticalAngle: Double) {
        minElbow = minKeepNaN(minElbow, elbowAngle)
        maxElbow = maxKeepNaN(maxElbow, elbowAngle)
        maxTorso = maxKeepNaN(maxTorso, torsoVerticalAngle)
    }

    private fun buildDebugMetrics(): RepDebugMetrics = RepDebugMetrics(
        values = linkedMapOf(
            "minElbowAngle" to minElbow,
            "maxElbowAngle" to maxElbow,
            "topElbowAngle" to topElbow,
            "maxTorsoAngle" to maxTorso
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
        errors.clear()
        frameRecorder.reset()
        resetDebug()
        torsoViolated = false
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
        // startElbowShoulder is (re)assigned by finishRep from the completing frame.
    }

    fun reset() {
        currentState = State.START
        sawStart = false
        topReached = false
        torsoViolated = false
        lastFailingRule = "none"
        torsoGate.reset()
        startPoseGate.reset()
        topPoseGate.reset()
        errors.clear()
        frameRecorder.reset()
        resetDebug()
        startElbowShoulder = Double.NaN
        lastRepStartElbowShoulder = Double.NaN
    }
}
