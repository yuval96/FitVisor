# Debug tools and on-device measurements

## Controls

- Open Settings and turn on **Debug tools**. Defaults to off.
- Debug tools show the live skeleton, angles, phase, preview bounds, summary metric data and shoulder-press Logcat diagnostics. Per-rep metrics are retained even with debug off; their summary display remains debug-only.
- Enable **Track latency** and/or **Track test repetitions** separately. Turning Debug tools off disables both and hides their controls. Re-enabling debug leaves both trackers off.
- Ordinary workout repetition counts, corrective feedback and the timer work with debug off.
- Test repetitions accumulate locally across workouts, by exercise and app-classified correct/incorrect status. Turn tracking off or reset all test counts from Settings or the exercise Summary. Resetting test counts does not change ordinary workout results.
- Tracking off preserves saved test data. Counts are detected repetitions, not a manual ground truth or an accuracy measurement.

## Latency protocol

1. Choose model and CPU/GPU in Settings, enable Debug tools and Track latency, then start a new workout.
2. Exercise with the device in its normal camera position.
3. Leave the workout and open Settings to read the saved measurements. Text is selectable for copying into the project report.
4. Repeat with the same exercise and environment when comparing model/delegate settings. Start a new workout per comparison.

Measurements use `SystemClock.elapsedRealtimeNanos()` throughout. Camera timestamps only identify matching callbacks; they are never subtracted from the measurement clock.

- Image preparation: analyzer entry through bitmap conversion/rotation and MPImage preparation.
- Model callback: detectAsync submission through result callback. Includes MediaPipe scheduling/processing, not isolated neural-network inference.
- UI queue + analysis: callback through UI-thread analysis, repetition recording and view updates.
- Total to UI update: sum of those stages. Excludes camera capture/delivery delay and physical screen rendering.

The report shows mean, nearest-rank P95 and maximum milliseconds for the latest 600 completed frames at most. Dropped frames have no latency sample. Pending frame metadata is capped at 120 entries. Reports include device/Android, exercise, actual model/delegate after fallback and save time. The latest workout report replaces the previous report on pause. Turning latency off stops sample collection; Reset latency removes the saved report.

## Device acceptance checks

- Debug off: timer/count/corrective cards visible; skeleton/angles/debug controls absent.
- Debug on: diagnostic overlay and both optional controls visible, initially off.
- Enable both trackers, complete reps for each exercise, finish each exercise, then finish the workout and inspect Summary and Settings; totals increment once per detected rep.
- Disable test tracking from Settings or the completed Summary, start another workout and complete reps; ordinary workout count increases but test total stays fixed.
- Reset test counts; every exercise becomes zero and ordinary session results remain intact.
- Disable Debug tools and re-enable it; both trackers remain off, saved totals remain available.
- Background the app for ten seconds; exercise elapsed time does not include the pause. Resume and verify timing/counting continues. Time in exercise selection is also excluded from the summary's Active Time.
- Check corrective cards at normal training distance, with bright and dark camera backgrounds and long messages.
- Verify actual device latency with CPU/GPU and available model variants. No device results should be claimed from desktop unit tests.

## Automated verification

Run `:app:assembleDebug :app:testDebugUnitTest`. Tests cover exercise rules, session pause/resume/end duration, and latency stage calculation, dropped/duplicate frames, rolling window/P95 and pending-frame clearing.
