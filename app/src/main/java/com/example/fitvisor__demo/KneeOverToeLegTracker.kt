package com.example.fitvisor__demo

/**
 * Per-frame kinematic output of the squat's knee-over-toe check, for whichever
 * leg [KneeOverToeLegTracker] currently has locked in. [confidence] is the
 * minimum landmark confidence (knee/ankle/foot-index) for that leg.
 */
data class KneeOverToeMetrics(
    val legIsLeft: Boolean,
    val confidence: Float,
    val ankleAngle: Double,
    val normalizedKneeToeOffset: Double
)

/**
 * Chooses and *locks* which leg (left/right) the squat's knee-over-toe check
 * uses, based on the minimum confidence across knee/ankle/foot-index for each
 * leg (see [LandmarkConfidence]).
 *
 * Per-frame confidence is noisy, so re-picking the "best" leg every single
 * frame would make the metric flicker between legs mid-repetition. Instead,
 * the leg is free to change while the user is standing ([isStanding] true,
 * i.e. between repetitions), and is then held fixed for the whole repetition
 * once squatting begins — it only changes early if it stops meeting
 * [minConfidence] and the other leg currently does.
 */
class KneeOverToeLegTracker(private val minConfidence: Float = SideSelector.MIN_SIDE_CONFIDENCE) {

    private var lockedSide: SideSelector.Side? = null

    /**
     * @param isStanding whether the squat engine's phase, as of the *start* of
     * this frame, was still the standing/start state — the window during
     * which the lock is free to move, so it reflects the freshest read the
     * instant a repetition begins. Returns the leg to use this frame, or null
     * if neither leg currently meets [minConfidence].
     */
    fun update(leftConfidence: Float, rightConfidence: Float, isStanding: Boolean): SideSelector.Side? {
        val leftOk = leftConfidence >= minConfidence
        val rightOk = rightConfidence >= minConfidence
        val best = when {
            leftOk && rightOk -> if (leftConfidence >= rightConfidence) SideSelector.Side.LEFT else SideSelector.Side.RIGHT
            leftOk -> SideSelector.Side.LEFT
            rightOk -> SideSelector.Side.RIGHT
            else -> null
        }

        if (isStanding) {
            // Free to re-pick every frame while standing; whatever this
            // resolves to on the last standing frame becomes the rep's lock.
            lockedSide = best
            return lockedSide
        }

        val locked = lockedSide
        val lockedStillOk = when (locked) {
            SideSelector.Side.LEFT -> leftOk
            SideSelector.Side.RIGHT -> rightOk
            null -> false
        }
        if (!lockedStillOk) {
            lockedSide = best // switches leg, or goes null if neither qualifies
        }
        return lockedSide
    }

    fun reset() {
        lockedSide = null
    }
}
