package com.example.fitvisor__demo.pose

/**
 * Chooses which body side (left/right) to track for a side-view exercise, based
 * on per-side confidence.
 *
 * Behaviour (extracted from the original squat logic so every side-view
 * exercise shares it):
 *  - Stick with the current side to avoid flicker.
 *  - Switch immediately if the current side becomes unreliable and the other is
 *    reliable.
 *  - Otherwise only switch when the other side is clearly better, by more than
 *    [switchMargin]. A tiny confidence difference never triggers a switch.
 */
class SideSelector(
    private val minConfidence: Float = MIN_SIDE_CONFIDENCE,
    private val switchMargin: Float = SIDE_SWITCH_MARGIN
) {
    enum class Side { LEFT, RIGHT }

    private var current = Side.LEFT

    val currentSide: Side get() = current

    fun select(leftConfidence: Float, rightConfidence: Float): Side {
        current = when (current) {
            Side.LEFT -> {
                val leftUnreliable = leftConfidence < minConfidence
                val rightReliable = rightConfidence >= minConfidence
                when {
                    leftUnreliable && rightReliable -> Side.RIGHT
                    rightConfidence > leftConfidence + switchMargin -> Side.RIGHT
                    else -> Side.LEFT
                }
            }

            Side.RIGHT -> {
                val rightUnreliable = rightConfidence < minConfidence
                val leftReliable = leftConfidence >= minConfidence
                when {
                    rightUnreliable && leftReliable -> Side.LEFT
                    leftConfidence > rightConfidence + switchMargin -> Side.LEFT
                    else -> Side.RIGHT
                }
            }
        }
        return current
    }

    fun reset() {
        current = Side.LEFT
    }

    companion object {
        const val MIN_SIDE_CONFIDENCE = 0.45f
        const val SIDE_SWITCH_MARGIN = 0.15f
    }
}
