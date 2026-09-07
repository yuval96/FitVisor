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

`SessionResultsHolder` retains only the last completed workout, matched by ID.
The summary derives counts and details from that single snapshot. Missing state
(for example after process death) returns to Home with a message, rather than
showing empty or mismatched results. There is no database, history, or process
death recovery in this phase.

Host tests cover transitions, repeated types, late/duplicate events, pause timing,
rep data retention, empty sessions, and completed-summary ID matching. Device
tests cover Home recreation and grouped summary rendering. Manual acceptance:
start a workout, perform two exercises (also repeat one type), finish each,
rotate/background the camera, finish the workout, inspect every rep/error, and
verify Back cannot resume a completed workout. Check repeated camera/model
creation and release on both CPU and supported GPU devices.
