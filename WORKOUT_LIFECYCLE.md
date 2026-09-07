# In-memory multi-exercise workouts

Home starts one workout and then shows exercise selection. Camera permission is
requested before creating an exercise session. Finish Exercise (or system Back
from the camera) saves completed reps in that exercise and returns to selection.
Only Finish Workout ends the parent workout and opens its complete summary.
Two selections of the same exercise remain separate sections with separate IDs
and rep numbering. Zero-rep exercises are retained.

`WorkoutSessionManager` owns the lifecycle and uses the existing `WorkoutManager`
for each exercise's repetition records and pause-aware elapsed timer.
`ActiveWorkoutStore` keeps it independent of Activity instances. Recreating the
camera Activity resumes the existing exercise; it does not start another one.
Backgrounding resets only the partial movement, keeping completed reps.

Wall-clock start/end times describe the workout and each exercise. Active Time
is the sum of exercise durations, excluding pauses and time selecting exercises.
Errors and aggregate metrics are retained regardless of debug visibility.
Optional frame traces retain their existing compile-time switch and cap.

Completed workouts are persisted to a Room database (`WorkoutHistoryRepository`)
keyed by workout ID, including every exercise, rep, error, and per-rep metric.
The summary screen always loads by ID from that store, whether opened right
after finishing a workout or later from Home's Workout History list — so it
survives process death and shows the same result either way. A missing ID
(for example a deleted workout) returns to Home with a message rather than
showing empty or mismatched results. Deleting a workout from History removes
it and all of its child rows transactionally.

Host tests cover transitions, repeated types, late/duplicate events, pause timing,
rep data retention, empty sessions, and completed-summary ID matching. Device
tests cover Home recreation and grouped summary rendering. Manual acceptance:
start a workout, perform two exercises (also repeat one type), finish each,
rotate/background the camera, finish the workout, inspect every rep/error, and
verify Back cannot resume a completed workout. Check repeated camera/model
creation and release on both CPU and supported GPU devices.
