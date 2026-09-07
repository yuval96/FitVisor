package com.example.fitvisor__demo.utils

/**
 * Small debounce helper: a condition must hold for [requiredFrames] consecutive
 * frames before it is considered "tripped".
 *
 * Used by rule engines so a single jittery frame does not latch a technique
 * violation, and warnings do not flicker on and off every frame.
 */
class ConsecutiveGate(private val requiredFrames: Int = 2) {

    private var count = 0

    /**
     * Feeds one frame's condition.
     * @return true once the condition has held for [requiredFrames] frames in a row.
     */
    fun update(conditionActive: Boolean): Boolean {
        count = if (conditionActive) count + 1 else 0
        return count >= requiredFrames
    }

    fun reset() {
        count = 0
    }
}
