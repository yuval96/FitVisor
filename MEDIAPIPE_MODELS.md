# Bundled Pose Landmarker models

FitVisor uses MediaPipe Tasks Vision 0.10.11. All three task bundles are ordinary
Git-tracked application assets under `app/src/main/assets/`.

The Lite and Full bundles are the official float16 version 1 downloads from
Google's MediaPipe model storage, linked by the
[Pose Landmarker guide](https://developers.google.com/edge/mediapipe/solutions/vision/pose_landmarker).
Heavy is the existing project asset and was not replaced.

| Model | Bytes | SHA-256 |
| --- | ---: | --- |
| Lite | 5,777,746 | `59929e1d1ee95287735ddd833b19cf4ac46d29bc7afddbbf6753c459690d574a` |
| Full | 9,398,198 | `5134a3aad27a58b93da0088d431f366da362b44e3ccfbe3462b3827a839011b1` |
| Heavy | 30,664,242 | `64437af838a65d18e5ba7a0d39b465540069bc8aae8308de3e318aad31fcbc7b` |

Download sources:

- [Lite](https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task)
- [Full](https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task)

Each archive contains `pose_detector.tflite` and
`pose_landmarks_detector.tflite`. Host unit tests read both components, check
their TFLite identifiers, lengths and ZIP CRCs, and verify selection mappings.

Model selection is exact: missing/unreadable assets or initialization failures
are reported through the workout's error logger; no other model is substituted.
Successful initialization logs the actual asset and delegate under
`PoseLandmarkerHelper`. A GPU initialization failure retries the same asset on
CPU. Settings changes apply to the next workout.

`PoseModelsInstrumentedTest` checks real Settings radio-button persistence and
initializes each packaged model with the existing Android SDK on CPU. Run it
with `:app:connectedDebugAndroidTest` on an authorized device/emulator. Host unit
tests and APK assembly do not establish native inference compatibility by themselves.
Physical-device acceptance should also cover live camera inference for each
model, successful GPU initialization where supported, and same-model CPU retry
on devices where GPU initialization fails.
