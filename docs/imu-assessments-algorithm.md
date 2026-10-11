# Sensor-based (IMU) Sit-to-Stand and Walking: algorithm and limits

Version tags: `imu-algo-1.0.0` (algorithm), `not-scored` (no score is produced).
Protocol ids: `five_times_sit_to_stand_imu`, `timed_walk_imu`.

## Status and limits (read first)

- These two assessments use **only the phone's accelerometer and gyroscope**. No camera and no MediaPipe pose model is used.
- Validation so far is **synthetic only**: the unit tests generate signals with known timing and step patterns. No
  physical-device run and no comparison with a reference measurement have been done. Hardware validation is
  **outstanding**.
- Thresholds are engineering values chosen for the synthetic signals. They are not clinically validated and are not a
  diagnostic criterion. Results describe the movement measured by the phone; they are not a score.
- Plantar pressure, stride length, walking speed, left/right asymmetry and turning-based gait measures are **not**
  measured.

## Placement

The phone is in the **front trouser pocket, close to the hip**, and stays there for the whole test. The stored
placement value is `FRONT_TROUSER_POCKET`. Other placements are not analysed.

## Sensor acquisition

- Both sensors are requested through the shared `MotionSensorSource` (`startRaw`, required = accelerometer and
  gyroscope) on a dedicated thread. Sensor event timestamps are used for all timing; wall-clock time is not used for
  measurements.
- `TYPE_GRAVITY` and `TYPE_LINEAR_ACCELERATION` are **not** used. The gravity direction is estimated from the
  accelerometer with a one-pole low-pass filter (time constant 0.3 s, step from the real sample timestamps). Using the
  platform gravity sensor would change the sensor set shared with the hand-stability and pronation tests.
- Samples are resampled to a uniform 50 Hz grid by linear interpolation of each track. A gap longer than 200 ms is
  marked invalid and is not interpolated across.
- Each stream is cleaned on its own (duplicate or out-of-order timestamps are removed and counted). The cleaning is not
  shared between streams, because the two sensors can report identical timestamps.
- The stored data facts are: measured rates, dropouts, timestamp issues, received events and the share flagged
  unreliable. Raw samples are never stored.

## Calibration (sit-to-stand and walking)

Before a test starts, the phone must be still for the calibration period (sit-to-stand 2.5 s; walking 3 s):

- gyroscope RMS ≤ 8 deg/s;
- gravity tilt standard deviation ≤ 3°;
- gravity magnitude between 7 and 12 m/s²;
- at least 1000 ms of valid grid samples.

The sit-to-stand reference posture is the normalised mean gravity direction during this window. If the phone moved,
the calibration is rejected and the user is asked to repeat it.

## Sit-to-stand (five repetitions)

- **Posture angle θ**: the angle between the current gravity direction and the seated reference, smoothed over ±50 ms.
- **Hysteresis thresholds**: a repetition starts when θ rises above 10° for 150 ms (onset), reaches standing at 35° for
  200 ms, starts sitting down when θ falls below 25° for 200 ms (leave), and is complete when θ is back under 12° for
  300 ms (seated).
- **Rotation evidence**: each transfer must include gyroscope rotation of at least 25 deg/s. Without it the transfer is
  rejected as `NO_ROTATION_EVIDENCE`.
- **Partial rises**: a rise that returns to seated without reaching the standing threshold is rejected as
  `PARTIAL_RISE`.
- **Sensor gaps**: a gap longer than 300 ms inside a transfer discards it as `SENSOR_GAP`. A gap inside the timed span
  (from the first repetition's start to the last seated posture) invalidates the whole result.
- **Timing convention**: total time = first repetition's sustained onset → last repetition's sustained seated posture,
  in sensor-timestamp milliseconds. Phases: standing-up = onset → standing; sitting-down = leave → seated.
- **Completion**: five complete repetitions are required. A 60 s time limit applies; the session is then invalid with
  the reason `TimeLimit` or `Incomplete`.

## Walking (30 s timed walk)

- **Vertical acceleration**: `dot(a, ĝ) − |g|`, where ĝ is the low-pass gravity direction.
- **Band-pass**: one-pole filter, 0.5–5 Hz.
- **Peak detection**: local maxima of |band-passed signal| above `max(0.35 m/s², 0.6 × 95th percentile)`. The
  amplitude threshold is fixed, not a median/MAD estimate, which was tested and rejected because it sat above the
  periodic peak amplitude.
- **Refractory period**: 250 ms; greedy selection by amplitude.
- **Plausible intervals**: 300–1500 ms between consecutive steps. Other intervals are counted as implausible peaks.
- **Bouts**: at least 4 consecutive plausible steps. A data gap breaks a bout.
- **Turns**: yaw about gravity ≥ 40 deg/s for at least 300 ms. Steps within 500 ms of a turn are excluded and counted.
- **Metrics**: cadence (steps per minute) and step-interval variability are reported only with at least 6 steps that
  passed all checks. Otherwise they are null and shown as "not computed".

## Quality and storage

- Coverage = share of the planned span covered by valid grid samples. Status: VALID at ≥ 90 %, LOW_QUALITY at ≥ 70 %,
  otherwise INSUFFICIENT_DATA. More than 5 % timestamp issues gives INVALID.
- Only VALID and LOW_QUALITY results are stored. Failures are shown with a reason and are not stored.
- Room tables `imu_sit_to_stand_assessments` and `imu_gait_assessments` (database version 11, auto-migration from 10).
  Only results and data facts are stored; raw samples are not.
- Legacy camera results remain readable in their own read-only screens.

## Tests

Synthetic-signal tests in `app/src/test/java/com/example/parkinson/imu/` cover the sit-to-stand and walking detectors,
the session lifecycle (sensor unavailable, pause/resume, starvation, pause inside the timed span, abort) and the
calibration gate. Storage tests in `data/ImuStorageTest.kt` cover round trips, replacement by id, history order and the
10 → 11 migration. These tests show that the code behaves as designed on known input; they do not show accuracy on a
person.

## Outstanding

- Device run with a real phone in the trouser pocket, including people with and without movement impairment.
- Comparison against a reference method (for example video or a stopwatch protocol) to estimate agreement and error.
- Threshold review against the real data.
