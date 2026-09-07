package com.example.fitvisor__demo

import android.content.Intent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fitvisor__demo.model.ExerciseType
import com.example.fitvisor__demo.model.RepDebugMetrics
import com.example.fitvisor__demo.model.RepError
import com.example.fitvisor__demo.ui.home.HomeActivity
import com.example.fitvisor__demo.ui.summary.SummaryActivity
import com.example.fitvisor__demo.workout.ActiveWorkoutStore
import com.example.fitvisor__demo.workout.WorkoutHistoryRepository
import com.example.fitvisor__demo.workout.WorkoutSession
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WorkoutFlowInstrumentedTest {
    private val manager get() = ActiveWorkoutStore.manager
    private val repository by lazy {
        WorkoutHistoryRepository.getInstance(InstrumentationRegistry.getInstrumentation().targetContext)
    }

    @After fun clearTestWorkout() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            manager.currentExerciseId?.let { manager.finishExercise(it) }
            manager.currentWorkout?.let { manager.finishWorkout(it.id) }
        }
    }

    /** Summary loading is asynchronous now (Room), so poll instead of asserting immediately. */
    private fun waitUntil(timeoutMs: Long = 5_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Condition not met within ${timeoutMs}ms")
            Thread.sleep(50)
        }
    }

    @Test fun homeStartAndRecreationRetainTheSameWorkout() {
        ActivityScenario.launch(HomeActivity::class.java).use { scenario ->
            var id = ""
            scenario.onActivity { home ->
                assertNull(manager.currentWorkout)
                assertEquals(View.GONE, home.findViewById<View>(R.id.cardSquat).visibility)
                home.findViewById<View>(R.id.startWorkoutButton).performClick()
                id = manager.currentWorkout!!.id
                assertEquals(View.VISIBLE, home.findViewById<View>(R.id.cardSquat).visibility)
            }
            scenario.recreate()
            scenario.onActivity { home ->
                assertEquals(id, manager.currentWorkout!!.id)
                assertEquals(View.GONE, home.findViewById<View>(R.id.startWorkoutButton).visibility)
                assertEquals(View.VISIBLE, home.findViewById<View>(R.id.finishWorkoutButton).visibility)
            }
        }
    }

    @Test fun summaryKeepsRepeatedExerciseSectionsAndErrorsAfterRecreation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var id = ""
        var completed: WorkoutSession? = null
        instrumentation.runOnMainSync {
            val workout = manager.startWorkout()
            id = workout.id
            repeat(2) { index ->
                val exercise = manager.startExercise(id, ExerciseType.SQUAT)!!
                manager.resumeExercise(exercise.id)
                manager.recordRep(exercise.id, index == 0,
                    if (index == 0) emptySet() else setOf(RepError.INSUFFICIENT_DEPTH),
                    RepDebugMetrics(mapOf("minKneeAngle" to 120.0)))
                manager.finishExercise(exercise.id)
            }
            completed = manager.finishWorkout(id)!!
        }
        // Summary now always loads by ID from the workout history database, the
        // same path as after finishing a workout from HomeActivity — so seed
        // the DB directly rather than the old in-memory handoff.
        runBlocking { repository.saveCompletedWorkout(completed!!) }
        val intent = Intent(instrumentation.targetContext, SummaryActivity::class.java)
            .putExtra(SummaryActivity.EXTRA_WORKOUT_ID, id)
        ActivityScenario.launch<SummaryActivity>(intent).use { scenario ->
            fun checkSummary() {
                waitUntil {
                    var populated = false
                    scenario.onActivity { summary ->
                        populated = summary.findViewById<LinearLayout>(R.id.repDetailsContainer).childCount > 0
                    }
                    populated
                }
                scenario.onActivity { summary ->
                    assertNull(manager.currentWorkout)
                    val sections = summary.findViewById<LinearLayout>(R.id.repDetailsContainer)
                    assertEquals(2, sections.childCount)
                    assertEquals("1. Squat", sections.getChildAt(0)
                        .findViewById<TextView>(R.id.exerciseSessionTitle).text.toString())
                    assertEquals("2. Squat", sections.getChildAt(1)
                        .findViewById<TextView>(R.id.exerciseSessionTitle).text.toString())
                    val secondReps = sections.getChildAt(1)
                        .findViewById<LinearLayout>(R.id.exerciseSessionReps)
                    assertEquals(1, secondReps.childCount)
                    assertEquals(RepError.INSUFFICIENT_DEPTH.message, secondReps.getChildAt(0)
                        .findViewById<TextView>(R.id.repErrorsText).text.toString())
                }
            }
            checkSummary()
            scenario.recreate()
            checkSummary()
        }
        runBlocking { repository.deleteWorkout(id) }
    }
}
