package com.example.fitvisor__demo.pose

import com.example.fitvisor__demo.utils.ConsecutiveGate

/**
 * Chooses which body side (left/right) to track for a side-view exercise, based
 * on per-side confidence.
 *
 * Behaviour (extracted from the original squat logic so every side-view
 * exercise shares it):
 *  - The first call after construction/[reset] has no side locked in yet, so
 *    it picks whichever side looks more confident right away -- there used to
 *    be a hardcoded LEFT starting side here, which meant a user turned
 *    right-side-on to the camera always started locked onto their occluded,
 *    lower-confidence side and had to fight the switch conditions below just
 *    to get to the side actually facing the camera, while a left-side-on user
 *    started correctly by luck. That bias no longer exists.
 *  - Once a side is locked in, stick with it to avoid flicker.
 *  - Switch only once the switch condition (current side unreliable with the
 *    other reliable, or the other side clearly better by more than
 *    [switchMargin]) has held for [ConsecutiveGate.requiredFrames] consecutive
 *    frames -- the same debounce duration the rule engines use for their own
 *    thresholds. A single noisy confidence reading (more common in low light)
 *    must not flip which leg's angle feeds the rep state machine, since the
 *    two legs' angles are never numerically identical and a flip reads as a
 *    sudden jump to it.
 */
class SideSelector(
    private val minConfidence: Float = MIN_SIDE_CONFIDENCE,
    private val switchMargin: Float = SIDE_SWITCH_MARGIN
) {
    enum class Side { LEFT, RIGHT }

    private var current: Side? = null
    private val switchGate = ConsecutiveGate()

    val currentSide: Side? get() = current

    fun select(leftConfidence: Float, rightConfidence: Float): Side {
        val locked = current
        if (locked == null) {
            val picked = if (rightConfidence > leftConfidence) Side.RIGHT else Side.LEFT
            current = picked
            return picked
        }

        val wantsSwitch = when (locked) {
            Side.LEFT -> {
                val leftUnreliable = leftConfidence < minConfidence
                val rightReliable = rightConfidence >= minConfidence
                (leftUnreliable && rightReliable) || rightConfidence > leftConfidence + switchMargin
            }

            Side.RIGHT -> {
                val rightUnreliable = rightConfidence < minConfidence
                val leftReliable = leftConfidence >= minConfidence
                (rightUnreliable && leftReliable) || leftConfidence > rightConfidence + switchMargin
            }
        }

        if (switchGate.update(wantsSwitch)) {
            current = if (locked == Side.LEFT) Side.RIGHT else Side.LEFT
            switchGate.reset()
        }
        return current!!
    }

    fun reset() {
        current = null
        switchGate.reset()
    }

    companion object {
        const val MIN_SIDE_CONFIDENCE = 0.45f
        const val SIDE_SWITCH_MARGIN = 0.15f
    }
}
