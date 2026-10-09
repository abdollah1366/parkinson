# Walking (Gait) Assessment: algorithm and limits

Version: `gait-algo-1.0.0`, scoring `not-scored`. Source: `app/src/main/java/com/example/parkinson/gait/`.

## Scope

- **Camera pose only.** MediaPipe Pose Landmarker Lite (`pose_landmarker_lite.task`, bundled) on the back camera.
- **No phone inertial sensors.** A phone that films the walk cannot also be worn at the waist, so this protocol
  does not use the accelerometer or gyroscope. `PhoneInertialAnalyzer` exists but is not connected to this test.
- **No plantar pressure.** No pressure hardware is connected. Every stored result has
  `plantarPressureMeasured = false`; no barometer or other value is substituted.
- No gait score, severity grade or diagnosis. All values are image-plane and uncalibrated.

## Protocol (as shown to the person)

1. Phone on a stable stand, about 2 to 3 m from the walking line, full body including both feet in view.
2. A straight line of about 5 m, walked side-on to the camera (so the legs separate in the image).
3. 3 s countdown, then 20 s of walking at a comfortable pace. The walk is recorded only after the start button.
4. The start button is enabled only while the full body is seen in at least 80 % of the most recent frames.

## Features (per frame, `CameraPoseFeatureExtractor`)

- A core joint (shoulders, hips, ankles) needs visibility >= 0.5 and a position inside the image (2 % margin).
  Otherwise the frame is `INCOMPLETE`. A torso shorter than 60 px is `TOO_SMALL`. No person is `NO_POSE`.
- Ankle separation = (right ankle x - left ankle x) / torso length, signed.
- Trunk inclination = angle of the mid-hip to mid-shoulder line from the image vertical.
- Wrist offset = wrist x - mid-shoulder x, in torso lengths (optional; blank when the wrist is not visible).

## Step detection (`GaitEngine`)

- Valid frames are split into segments at gaps longer than 500 ms (no bridging across a gap).
- |ankle separation| is averaged over 3 consecutive frames, then peak-picked with hysteresis
  (a peak must rise at least 0.15 torso lengths above the preceding trough).
- Peaks closer than 300 ms are not both accepted.
- Each accepted peak is one step event. The two legs alternate, so one |separation| peak corresponds to one step.

## Metrics (null when unavailable, never zero)

| Metric | Definition | Needs |
|---|---|---|
| Steps | count of step events | any |
| Cadence | (steps - 1) x 60 000 / (last step - first step), steps per minute | >= 6 steps |
| Step-interval variability | standard deviation / mean of step intervals, percent | >= 6 steps |
| Trunk lean | mean absolute trunk inclination over valid frames, degrees | valid frames |
| Arm-swing range (each side) | max - min of the wrist offset, torso lengths | >= 10 frames with the wrist |

## Quality gates (ENGINEERING limits, not clinically validated)

| Finding | Condition | Status |
|---|---|---|
| NON_MONOTONIC_TIMESTAMPS | a frame timestamp is earlier than its predecessor | INVALID |
| TOO_FEW_FRAMES | fewer than 30 frames | INSUFFICIENT_DATA |
| LOW_VALID_FRAMES | valid share < 70 % | INSUFFICIENT_DATA |
| RECORDING_TOO_SHORT | duration < 80 % of the planned 20 s | INSUFFICIENT_DATA |
| LONG_GAP | a gap without a valid frame > 1 s | INSUFFICIENT_DATA |
| LOW_FRAME_RATE | median frame rate < 10 Hz | INSUFFICIENT_DATA |
| TOO_FEW_STEPS | fewer than 6 steps | INSUFFICIENT_DATA |
| REDUCED_VALID_FRAMES | valid share < 90 % | LOW_QUALITY |
| FREQUENT_GAPS | more than 3 gaps longer than 500 ms | LOW_QUALITY |

Only VALID and LOW_QUALITY recordings are stored. INSUFFICIENT_DATA and INVALID recordings produce no result, and the
person is told which condition was detected.

## Limits and what is not verified

- The thresholds (30 frames, 70 %, 90 %, 500 ms, 1 s, 10 Hz, 6 steps, 0.15 torso, 300 ms) are engineering choices.
  They are not validated against clinical gait data.
- Step detection is validated only on synthetic pose series with a known stride frequency (unit tests).
- Not verified on a physical device: MediaPipe pose frame rate, the detection rate at 2 to 3 m, lighting, and the
  side-on protocol. Diagnostics (`GaitDiagnostics`, debuggable builds only) log counts and timings per walk.
- Pose timestamps are the time the frame was handed to MediaPipe, not the camera exposure time.
- Error results are stamped with the last submitted timestamp, so the timeline never goes backwards.
