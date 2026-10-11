# Five Times Sit-to-Stand (5xSTS): algorithm and limits

Version: `sts-algo-1.0.0`, scoring `not-scored`. Source: `app/src/main/java/com/example/parkinson/sts/`.

## Scope

- Camera pose only (MediaPipe Pose Landmarker Lite, bundled), back camera, side view. No phone sensor is used.
- A protocol object (`SitToStandProtocol.FiveTimesSitToStand`) holds the repetition target and time limit. A
  30-second chair-stand test would be a separate protocol object with its own rules; no scoring is shared.
- No score, severity, clinical cut-off or diagnosis. All values are image-plane and uncalibrated.

## Protocol (as shown to the person)

1. Stable chair, non-slip floor, back supported; the app asks for a checklist before the camera opens.
2. The phone stands 2 to 3 m away, at the side, so the hip, knee, ankle and shoulder of one side are visible.
3. Seated calibration: sit still for 2 s. The calibration must find the person seated and steady.
4. Three-second countdown, then the attempt: stand up and sit down five times as safely as possible.
5. The attempt stops at five completed repetitions or after 60 s (incomplete).

## Features (per frame, `SitToStandPoseExtractor`)

- The side is the one whose hip, knee and ankle are more visible (chosen per frame during calibration, then
  fixed by the calibration).
- A joint with visibility below 0.5, or outside the image (2 % margin), makes the frame invalid. Invalid frames
  are never filled in.
- Knee angle = angle hip-knee-ankle (180 = straight leg). Hip y = image y of the hip. Shin = knee-to-ankle
  length in pixels. Trunk lean = angle of the hip-to-shoulder line from the image vertical.

## Calibration (`SeatedCalibrator`)

Accepted only when all hold: at least 15 valid frames on the majority side; median knee angle at most 130
degrees (a person standing is not seated); knee-angle standard deviation at most 6 degrees (not moving). The
baseline is the median knee angle, hip y and shin length of the window.

## Movement score (`SitToStandEngine`)

Relative to the person's own seated baseline, so no fixed joint angle is assumed:

    score = max(hipRise / 0.6, kneeExtension / 40)

- `hipRise` = (seated hip y - hip y) / shin length (upward positive, in shin lengths).
- `kneeExtension` = knee angle - seated knee angle (degrees).
- Score 0 = seated baseline; score 1 = a full stand by the two reference amounts.

Hysteresis thresholds: start a movement at 0.2; standing at 1.0; leave standing below 0.8; seated at 0.35 or less.
Every transition must hold for 200 ms.

Limitation: a person whose movement reaches less than the reference amounts never reaches score 1 and is not
counted. This is recorded (partial attempts) and not hidden. The reference amounts are engineering values, not
clinically validated.

## State machine

READY -> SEATED -> STANDING_UP (score >= 0.2 held) -> STANDING (score >= 1 held) -> SITTING_DOWN (score < 0.8
held) -> REPETITION_COMPLETED (score <= 0.35 held) -> SEATED ... -> TEST_COMPLETED after the fifth repetition.

- A rise that returns to seated (score <= 0.35) before standing is a **partial attempt** and is not counted.
- Standing up again before reaching the seated band cancels the sitting-down phase; the repetition continues.
- Duplicate timestamps are ignored. A timestamp that goes backwards invalidates the attempt.
- Invalid frames: a tracking loss longer than 1 s invalidates the attempt. Shorter losses are kept as invalid
  frames and the state is not changed.

## Timing convention

- Repetition: from the sustained start of standing up (score first held >= 0.2) to the sustained seated posture
  (score first held <= 0.35).
- Standing-up phase: sustained start of movement to the first sample of the sustained standing posture.
- Sitting-down phase: sustained start of sitting down (score first held < 0.8) to the first sample of the
  sustained seated posture.
- Total time: start of the first repetition to the end of the fifth.
- All times are sample timestamps (the monotonic clock used by the pose pipeline), not wall-clock time.

## Measured, derived and unavailable values

- Measured and stored: repetition times (start, standing reached, sit start, seated reached), peak hip rise
  (shin lengths) and peak knee extension (degrees) per repetition, the seated baseline, the per-frame series,
  invalid-frame counts by reason, partial attempts.
- Derived (computed from the stored measurements, labelled as derived in the UI): mean repetition time,
  repetition-time variability (standard deviation / mean, percent), mean standing-up and sitting-down phases,
  mean pause between repetitions, mean peaks.
- Unavailable values are null, never zero. Variability and the pause need at least two repetitions.

## Quality

- Valid-frame share at least 90 %: VALID. From 70 % to below 90 %: LOW_QUALITY (saved, with a warning). Below
  70 %, or not five repetitions: not saved; the person sees the reason on the invalid screen.

## Limits and what is not verified

- Thresholds (0.6 shin lengths, 40 degrees, 0.2 / 1.0 / 0.8 / 0.35 scores, 200 ms holds, 1 s tracking loss,
  130 degree seated limit, 6 degree calibration variability) are engineering choices. They are not validated
  against clinical data, and no clinical cut-off is implied.
- Synthetic movements (unit tests) prove the state machine and the timing rules. They do not prove that real
  pose tracking works.
- Not verified on a device: pose tracking from the side at 2 to 3 m, lighting, chairs of different heights, the
  effect of arm position, and whether the seated calibration passes for real people.
- The comparison with the previous attempt describes the difference between two sessions. It is not evidence of
  a treatment effect.
