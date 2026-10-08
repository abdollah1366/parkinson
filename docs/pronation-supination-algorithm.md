# Pronation / Supination (آزمون چرخش دست) – algorithm specification

| | |
|---|---|
| Internal name | `PronationSupinationTest` (catalog id `pronation_supination`) |
| Algorithm version | `ps-algo-1.0.0` (`PronationSupinationVersions.ALGORITHM_VERSION`) |
| Scoring version | `ps-score-0.1.0-research` (`PronationSupinationVersions.SCORING_VERSION`) |
| Clinical validation | **None.** All thresholds and normalization ranges are engineering values. No normative or patient dataset was used. |
| Device validation | **None yet.** Verified only on deterministic synthetic sensor data (JVM tests). |

> This is a **motor performance** measurement: repetitive forearm rotation measured with the
> phone's own sensors. It is not a diagnosis and is never presented as a measure of Parkinson's
> disease. The score is labelled «شاخص عملکرد حرکتی» and «نسخه پژوهشی / غیرتشخیصی».

Bump `ALGORITHM_VERSION` for any change to preprocessing, detection, metrics, trend or quality
rules. Bump `SCORING_VERSION` for any change to the score. Both versions are stored with every result.

## 1. Protocol

1. **Instructions:** title «چرخش دست» and the sentence «گوشی را مطابق دستور در دست بگیرید و کف دست را به‌آرامی به بالا و پایین بچرخانید.».
2. **Hand selection:** LEFT or RIGHT is an explicit user choice. It is never inferred from sensor
   data, because a gyroscope cannot tell a left forearm from a right one.
3. **Sensor check:** the gyroscope and the accelerometer must exist. A missing gyroscope shows
   «حسگر ژیروسکوپ در این دستگاه در دسترس نیست.» and the test cannot continue.
4. **PREPARING, 5 s:** the phone is held still. These samples form the baseline (section 3).
5. **COUNTDOWN:** 3, 2, 1. Samples are ignored.
6. **RECORDING, 10 s:** «شروع» is shown during the first second, then the remaining time.
7. **PROCESSING:** analysis and quality control.
8. **DONE:** the result. Otherwise **INVALID** (quality) or **INTERRUPTED** (Back, Home, screen
   lock, background, cancel), each with «تلاش مجدد», or **ERROR** (sensor or storage failure).

The screen orientation is locked while the test screen is shown, because the patient rotates the
phone. A configuration change still does not stop the session (it lives in the ViewModel).
Incomplete recordings are never stored.

## 2. Data path

```
SensorManager (TYPE_GYROSCOPE + TYPE_ACCELEROMETER, 10 ms requested, no batching, own HandlerThread)
  -> AndroidMotionSensorSource (own instance per test ViewModel)
  -> PronationSupinationSession     baseline samples (PREPARING) + recording samples (RECORDING)
  -> PronationSupinationEngine      pure, deterministic
  -> PronationSupinationScorer      separate scoring layer
  -> PronationSupinationResult      Room table pronation_supination_assessments (DB v3)
```

Cleaning and timing statistics are shared with Hand Stability (`sensors/MotionStreams.kt`):
* drop non-finite and physically impossible values, and samples with duplicate or out-of-order timestamps;
* measure the sampling rate as (n − 1) / span;
* a dropout is an interval > max(3 × median interval, 40 ms);
* completeness = (span + median) / 10 s.

The sampling rate is **never assumed**.

## 3. Pipeline

| Step | What it does |
|---|---|
| Timestamp synchronization | Analysis window = overlap of both streams (`SensorEvent.timestamp`, shared clock). Both streams are linearly interpolated onto one uniform grid at fs = min(100 Hz, measured gyroscope rate). Grid points inside a raw dropout are marked. |
| Baseline (preparation) | From the last 2 s of PREPARING: mean accelerometer vector = initial orientation (gravity). If the phone was still (gyro RMS ≤ 10 °/s and mean ≤ 5 °/s), the mean gyroscope vector is the bias estimate (`CALIBRATED`). Otherwise the bias is 0 (`NOT_STILL`) and drift is handled per recording. |
| Preprocessing | Subtract the bias from the angular velocity (°/s). |
| Main rotation axis | Dominant eigenvector of the 3×3 angular-velocity covariance (power iteration, deterministic sign). The signal is the angular velocity **about this axis**. This uses relative motion, so it does not depend on how the person holds the phone. Axis share = λ₁ / trace. |
| Filtering | Zero-phase 2nd-order Butterworth low-pass at min(10 Hz, 0.4 fs) (forward + backward). |
| Rotation angle | Trapezoid integration on the grid, then linear drift removal (residual bias). |
| Linear acceleration | Gravity is tracked in device coordinates: rotated with the gyroscope (dv/dt = −ω × v) and pulled towards the measured acceleration (complementary filter, τ = 1 s). Linear acceleration = measured − gravity. Rotating the phone is therefore not mistaken for translation. |

The **gyroscope is the primary signal**. It measures rotation only, so moving (translating) the
phone produces no cycles. The accelerometer gives the initial orientation, the linear-acceleration
metric and plausibility checks.

## 4. Movement detection

Turning points of the rotation angle are found with **hysteresis**: a maximum is confirmed once
the angle has fallen h below it, and a minimum once it has risen h above it.

* **Normalized threshold, two passes.** Pass 1 uses h = 10° (the minimum movement amplitude) and
  gives the person's median movement amplitude A. Pass 2 uses h = max(10°, 0.3 × A). Small
  reversals inside large movements are then not counted, and people who make small movements are
  still detected. There is no fixed threshold in device units.
* **Debounce / maximum plausible frequency.** A reversal less than 1 / (2 × 6 Hz) ≈ 83 ms after
  the previous turning point is implausible for forearm rotation. That pair of turning points is
  merged (the earlier one is dropped; the more extreme of the two same-type turning points is kept).
* **Incomplete movements.** The movement before the first turning point and after the last one
  is excluded.
* **Half-cycle:** one pronation or one supination, from turning point to turning point.
  **Cycle:** two consecutive half-cycles.
* **Dropout handling.** A half-cycle is excluded from the timing and amplitude metrics when a
  sensor dropout covers more than 25 % of it. A cycle is used only if both of its halves are valid.

## 5. Metrics

| # | Metric | Definition |
|---|---|---|
| 1 | Total rotation cycles | ⌊half-cycles / 2⌋ |
| 2 | Cycles per second | (half-cycles / 2) / analyzed duration |
| 3, 4 | Mean / median cycle duration | valid cycles |
| 5 | Cycle-time variability | CV % of cycle durations (≥ 3 cycles) |
| 6 | Angular velocity mean | mean \|ω\| about the main axis, first to last turning point, dropouts excluded |
| 7 | Angular velocity peak | median of the per-movement peak \|ω\| |
| 8 | Angular velocity variability | CV % of the per-movement peaks |
| 9 | Movement amplitude | median rotation angle per movement (degrees) |
| 10 | Amplitude variability | CV % of the per-movement angles |
| 11 | Movement consistency | % of movements whose amplitude **and** duration are within ±30 % of the person's medians |
| 12, 13 | Pauses / pause duration | \|ω\| below max(5 °/s, 15 % of the median peak) for ≥ max(400 ms, 30 % of the median cycle), between the first and last turning point; a dropout breaks a pause |
| 14 | Effective sampling rate | measured gyroscope rate |
| 15 | Valid sample percentage | valid / received (both sensors) |
| 16 | Sensor dropout count | both sensors |
| 17 | Recording completeness | the weaker stream |
| – | Angular velocity RMS | about the main axis |
| – | Acceleration RMS | of the linear (gravity-removed) acceleration |
| – | Dominant movement frequency | Hann-windowed DFT of ω from 0.2 to 6 Hz in 0.05 Hz steps. Reported only with ≥ 3 valid cycles and ≥ 40 % of the power within ±0.25 Hz of the peak |

"Frequency stability" was not added: the cycle-time CV already measures rhythm stability more
directly over only 10 s.

## 6. «روند عملکرد حرکتی» (performance trend)

The analysis window is split into **early / middle / late** thirds. Each valid half-cycle is
assigned by its midpoint. For each third the app takes:
* the median amplitude;
* the median peak angular velocity;
* the median cycle duration (2 × median half-cycle duration).

A third with fewer than 2 movements is not computed. The change is (late − early) / early,
reported as STABLE (|change| < 15 %), INCREASED or DECREASED. It describes the change within this
10-second recording only. It is **not** disease progression.

## 7. Quality control

| Issue | Severity | Rule |
|---|---|---|
| NO_ACCELEROMETER_DATA / NO_GYROSCOPE_DATA | INVALID | no valid sample |
| SAMPLING_RATE_TOO_LOW | INVALID | < 25 Hz (turning points of up to 6 Hz movement cannot be resolved) |
| RECORDING_INCOMPLETE | INVALID | completeness < 90 % |
| EXCESSIVE_GAPS | INVALID | dropout share > 20 % or longest gap > 1 s |
| TOO_MANY_INVALID_SAMPLES | INVALID | valid < 90 % |
| TOO_FEW_SAMPLES | INSUFFICIENT_DATA | < 100 valid samples per stream |
| NO_MOVEMENT_DETECTED | INSUFFICIENT_DATA | < 2 cycles |
| TOO_FEW_VALID_CYCLES | INSUFFICIENT_DATA | < 2 cycles that are not affected by dropouts |
| LOW_SAMPLING_RATE | LOW_QUALITY | < 50 Hz |
| GAPS_PRESENT | LOW_QUALITY | dropout share > 2 % or longest gap > 250 ms |
| INVALID_SAMPLES_PRESENT | LOW_QUALITY | valid < 99 % |
| SENSOR_UNRELIABLE | LOW_QUALITY | > 10 % of events flagged unreliable |
| STREAMS_MISALIGNED | LOW_QUALITY | start or end differs by > 500 ms |
| ROTATION_AXIS_UNSTABLE | LOW_QUALITY | < 60 % of the rotational variance about one axis (not a consistent rotation) |
| GRAVITY_IMPLAUSIBLE | LOW_QUALITY | mean \|a\| outside 6–16 m/s² |

The status is the most severe issue.
* **INVALID / INSUFFICIENT_DATA:** no result is stored. The patient sees «کیفیت ثبت اطلاعات برای
  محاسبه نتیجه کافی نبود.», a reason, and «تلاش مجدد».
* **LOW_QUALITY:** the result is stored **without a score**.

The quality score (0–100) is computed as in Hand Stability: rate 30 %, completeness 30 %, validity
20 %, gaps 20 %, taken from the weaker stream.

## 8. Performance score – `PronationSupinationPerformanceScore`

The score is computed only for **VALID** recordings. The raw metrics are stored separately from
the score. Each component is linear between 0 and a normalization reference, clamped to 0..100:

| Component | Weight | Reference (= 100) |
|---|---|---|
| Cycle rate | 0.25 | 3.0 cycles/s |
| Rhythm (1 − CV / 50 %) | 0.20 | CV 0 % |
| Amplitude | 0.20 | 150° |
| Angular velocity (median peak) | 0.15 | 600 °/s |
| Movement consistency | 0.15 | 100 % |
| Data quality | 0.05 | quality score |

A missing component (fewer than 3 cycles or movements) is left out and the other weights are
renormalized. The references only span the range the detector is designed for. They are **not
normative values and not clinical cut-offs**. The score is meant for following one person over
time. `isClinicallyValidated = false`.

## 9. Diagnostics (debug builds only)

Logcat tag `PSDiag` (`SensorDiagnostics`, tag chosen per session). Patients never see it.

* `RATE`: per sensor per second, with timestamp, count, mean and maximum interval, and the latest x/y/z (gyroscope and accelerometer).
* `GAP`: any interval > 100 ms.
* `STATE`: transitions.
* `CYCLE`: each movement, with start, duration, amplitude, peak velocity and validity.
* `RESULT`: rates, dropouts, baseline status, hysteresis, cycles, amplitude, peak, axis share, pauses, quality and score.

No identifiers are logged.

## 10. Verification status

* **Verified (synthetic):**
  * `PronationSupinationEngineTest` (24 tests), covering:
    * no movement;
    * slow (0.5 Hz), normal (1.5 Hz) and fast (4 Hz) rotation;
    * small (20°), below-minimum (6°) and large (170°) amplitude;
    * regular vs irregular rhythm, a 2 s pause, sensor noise with a 7 Hz wobble;
    * short and long dropouts, 20 Hz and 40 Hz sampling, an incomplete recording;
    * duplicate-cycle prevention (5 Hz reversals, duplicate events), quality rejection;
    * reproducibility and versioning;
    * three different holding axes, translation without rotation, gyroscope bias calibration, the decreasing-amplitude trend, score ordering.
  * `PronationSupinationSessionTest` (12 tests), covering:
    * phase timing (5 s / 3-2-1 / 10 s with the «شروع» cue);
    * interruption in every phase, including during processing;
    * quality rejection and movement before recording;
    * missing gyroscope, registration failure, sensor stop and storage failure;
    * retry and reset.
  * Storage round-trip and the v2 → v3 migration; UI and navigation.
* **Not verified yet:** real phones, in particular:
  * real gyroscope rates and noise;
  * whether 10° / 0.3 × A hysteresis and the 6 Hz limit fit real patients;
  * the 60 % axis-share rule while the phone is held in a fist;
  * the pause threshold;
  * whether the normalization references give a useful spread.

  Collect `PSDiag` logs, with manually counted cycles, before tuning, and bump the versions on any change.
