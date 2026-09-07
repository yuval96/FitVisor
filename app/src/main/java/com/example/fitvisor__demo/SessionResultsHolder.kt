package com.example.fitvisor__demo

/** The last completed workout, retained only in this process for its summary. */
object SessionResultsHolder {
    private var workout: WorkoutSession? = null

    fun set(session: WorkoutSession) {
        check(session.endTimeMillis != null) { "Only completed workouts can be summarized." }
        workout = session
    }

    fun get(workoutId: String?): WorkoutSession? = workout?.takeIf { it.id == workoutId }

    fun clear() {
        workout = null
    }
}
