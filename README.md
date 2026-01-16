# FitVisor - AI Fitness Assistant

This application uses MediaPipe Pose Landmarker to provide real-time biomechanical feedback for physical exercises.

## Pipeline Architecture

The app is built using a modular pipeline to ensure local, on-device processing:

1.  **Camera Input**: Uses CameraX to capture live frames.
2.  **MediaPipe Pose Module**: Detects body landmarks from the camera stream.
3.  **Kinematic Calculator**: Computes joint angles (e.g., knee angle) and features (e.g., torso inclination) using vector geometry.
4.  **Rule Engine**: A state machine that tracks exercise phases ("Up" -> "Down" -> "Up") and evaluates form against biomechanical thresholds.
5.  **Feedback UI**: Displays real-time corrective messages and session statistics.
6.  **Workout Manager**: Tracks session-wide totals (reps, correctness, time).

## Adding a New Exercise

To add a new exercise (e.g., Push-up):

1.  **Define New Constants**: Add landmark indices and angle thresholds in a new Rule Engine class.
2.  **Update Kinematic Calculator**: Add any new joint angle calculations needed (e.g., elbow angle).
3.  **Create Rule Engine**: Implement a new state machine (e.g., `PushUpRuleEngine`) that defines the phases and technique rules.
4.  **Update UI**: Modify the workout screen to allow exercise selection and pass the corresponding data to the pipeline.
