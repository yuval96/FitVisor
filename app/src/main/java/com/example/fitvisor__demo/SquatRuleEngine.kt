package com.example.fitvisor__demo

/**
 * Rule Engine Module implemented as a state machine.
 * Handles phase detection, repetition counting, and technique evaluation.
 */
class SquatRuleEngine {

    companion object {
        // Threshold constants
        private const val SQUAT_DOWN_THRESHOLD = 100.0 // Angle to consider "Down"
        private const val SQUAT_UP_THRESHOLD = 160.0   // Angle to consider "Up"
        private const val TORSO_INCLINATION_LIMIT = 35.0
    }

    enum class State {
        UP, DOWN
    }

    private var currentState = State.UP
    private var wasTechniqueValidInCurrentRep = true
    private var currentWarning: String? = null

    /**
     * Data class to return the result of the frame analysis.
     */
    data class AnalysisResult(
        val state: State,
        val isRepCompleted: Boolean,
        val isRepCorrect: Boolean,
        val warning: String?
    )

    /**
     * Processes current angles and returns the result for the UI and Workout Manager.
     */
    fun processFrame(kneeAngle: Double, torsoAngle: Double, kneeMisaligned: Boolean): AnalysisResult {
        var isRepCompleted = false
        var isRepCorrect = false
        currentWarning = null

        // Check technique rules
        val torsoIssue = torsoAngle > TORSO_INCLINATION_LIMIT
        if (torsoIssue) {
            currentWarning = "Keep your back straighter"
            wasTechniqueValidInCurrentRep = false
        }
        if (kneeMisaligned) {
            currentWarning = "Check knee alignment"
            wasTechniqueValidInCurrentRep = false
        }

        // State Machine logic
        when (currentState) {
            State.UP -> {
                if (kneeAngle < SQUAT_DOWN_THRESHOLD) {
                    currentState = State.DOWN
                    // Reset technique flag for the new descent
                    wasTechniqueValidInCurrentRep = !torsoIssue && !kneeMisaligned
                }
            }
            State.DOWN -> {
                if (kneeAngle > SQUAT_UP_THRESHOLD) {
                    currentState = State.UP
                    isRepCompleted = true
                    isRepCorrect = wasTechniqueValidInCurrentRep
                }
            }
        }

        // If we are deep enough but torso is leaning, prioritize "Go lower" or technique
        if (currentState == State.UP && kneeAngle < SQUAT_UP_THRESHOLD && kneeAngle > SQUAT_DOWN_THRESHOLD) {
            if (currentWarning == null) currentWarning = "Go lower"
        }

        return AnalysisResult(currentState, isRepCompleted, isRepCorrect, currentWarning)
    }

    fun reset() {
        currentState = State.UP
        wasTechniqueValidInCurrentRep = true
        currentWarning = null
    }
}
