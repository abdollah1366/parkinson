# Pronation / Supination (آزمون چرخش دست) – algorithm specification

| | |
|---|---|
| Internal ID | `PRONATION_SUPINATION` (catalog id `pronation_supination`) |
| Algorithm version | `1.0` (`PronationSupinationVersions.ALGORITHM_VERSION`) |
| Scoring version | `1.0` (`PronationSupinationVersions.SCORING_VERSION`) |
| Scoring reference | `internal-software-reference-1.0` (`InternalSoftwareReference`, **not normative**) |
| Clinical validation | **None.** Every threshold, weight and range is an ENGINEERING value. No normative or patient dataset was used. |
| Device validation | **Pending.** Verified only on deterministic synthetic sensor data. See `pronation-supination-test.md`. |

> A **motor performance** assessment, not a diagnosis. The index is called «شاخص عملکرد حرکتی»
> (Motor Performance Index). It is an internal software index. Results never mention a disease,
> "bradykinesia" or "tremor".

Pre-release development builds stored `ps-algo-1.0.0` / `ps-score-0.1.0-research`. Those rows are
kept and still readable (DB v4 migration). Bump `ALGORITHM_VERSION` for any change to processing,
detection, metrics, trend or quality. Bump `SCORING_VERSION` for any change to normalization,
weights or bands.

All parameters live in `pronation/PronationSupinationConfig.kt`:

| Class | Covers |
|---|---|
| `PronationSessionConfig` | timing, preflight |
| `PronationDetectionConfig` | signal processing, detection |
| `PronationQualityThresholds` | quality thresholds |
| `PronationScoringConfig` | weights, normalization |

The reference ranges are in `ScoringReference.kt`.

## 1. Architecture

```
UI (Compose)          PronationSupinationIntroScreen  -> SensorCheckScreen -> PronationSupinationTestScreen -> PronationSupinationResultScreen
ViewModel             PronationSupinationViewModel (own AndroidMotionSensorSource, selected hand, session)
Session               PronationSupinationSession     IDLE/PREPARING/COUNTDOWN/RECORDING/PROCESSING/DONE/INVALID/INTERRUPTED/ERROR
Engine (pure Kotlin)  PronationSupinationEngine      signal processing, cycle detection, metrics
                      PronationSupinationQualityEngine
                      PronationSupinationScoreEngine (+ ScoringReference)
                      PronationSupinationInterpreter (language-neutral codes)
Storage               PronationSupinationResult -> PronationSupinationEntity (Room, DB v4) -> History
```

Shared infrastructure that is reused, not duplicated:
* `sensors/MotionSensors.kt`, `MotionStreams.kt` (cleaning, timing statistics), `MotionSampleBuffer.kt`;
* the `AssessmentCatalog`, `SensorCheckScreen`, `AssessmentResult`, Room database and repository, and History;
* `SelectedHand` (the app's hand-selection model; `mediapipe.HandSide` belongs to camera handedness).

## 2. Session and lifecycle

| Phase | Duration | What happens |
|---|---|---|
| PREPARING | 5 s | Phone held still. Samples go to the baseline buffer. «گوشی را ثابت نگه دارید و برای شروع آماده شوید.» |
| Preflight | end of preparation | Gyroscope rate ≥ 25 Hz and ≥ 90 % increasing timestamps. Otherwise ERROR before anything is recorded. |
| COUNTDOWN | 3 s | 3, 2, 1 (TalkBack: assertive live region). Samples ignored. |
| RECORDING | 10 s | «شروع کنید» for 1 s, then the remaining time. Samples go to the recording buffer. |
| PROCESSING | – | Copies the buffers and analyzes on `Dispatchers.Default`. |
| DONE / INVALID / INTERRUPTED / ERROR | – | Sensors stopped. Only DONE stores a result. |

* **Sensors.**
  * The gyroscope is required; the accelerometer is used when present (`startRaw(required, optional)`).
  * Events arrive through an allocation-free `MotionSampleSink` into pre-sized primitive buffers on a dedicated `HandlerThread`. Nothing runs on the UI thread.
* **Interruptions.**
  * Home, background or screen lock (ON_STOP without a configuration change), or leaving the screen → INTERRUPTED. Nothing is saved.
  * Back or Cancel during the test → «آیا می‌خواهید آزمون را متوقف کنید؟» with «ادامه آزمون» / «خروج».
  * A stalled gyroscope (no event for 1.5 s) → ERROR(SENSOR_STOPPED).
  * Motion sensors need no runtime permission (requested rate ≤ 200 Hz).
* **Rotation.** The screen orientation is locked while the test screen is shown. A configuration change would not stop the session anyway, because it lives in the ViewModel.
* **After the test ends.** A run-id guard discards the result of an analysis that finishes after the session ended.

## 3. Sensor pipeline

| Step | Implementation |
|---|---|
| Raw sensor data | `MotionSample` (type, `SensorEvent.timestamp`, x/y/z, unreliable flag) |
| Timestamp validation | `MotionStreams.clean`: drop non-finite values, values outside the physical range (16 g, 2000 °/s), and duplicate or out-of-order timestamps |
| Synchronization | Window = overlap of gyroscope and accelerometer (gyroscope span when there is no accelerometer). Linear interpolation onto a uniform grid at min(100 Hz, measured gyroscope rate). Grid points inside raw gaps (> max(3 × median interval, 40 ms)) are marked |
| Baseline correction | Last 2 s of preparation. If still (gyro RMS ≤ 10 °/s and \|mean\| ≤ 5 °/s): bias = mean angular velocity, noise = RMS around it, gravity = mean acceleration. Otherwise status NOT_STILL and bias 0 |
| Noise estimation | Baseline noise (still phone) and the recording noise level = RMS(raw − filtered rotation signal) |
| Filtering | Zero-phase 2nd-order Butterworth low-pass, min(10 Hz, 0.4 × grid rate), forward and backward |
| Rotational signal | See section 4 |
| Cycle detection and validation | See section 5 |
| Metrics | See section 6 |
| Quality | `PronationSupinationQualityEngine` (section 8) |
| Scoring | `PronationSupinationScoreEngine` (section 9) |
| Interpretation | `PronationSupinationInterpreter` (section 10) |

## 4. Rotational signal: why the main axis, and how the hand is used

* **Relative motion, not absolute orientation.**
  * The angular velocity (bias removed) is projected on the **main rotation axis**: the dominant eigenvector of its 3×3 covariance, by power iteration.
  * Pronation/supination is a rotation about one axis (the forearm). The projection therefore does not depend on how the phone sits in the hand.
  * No fixed device axis is assumed.
* **Why the axis cannot come from preparation.**
  * The phone is still during preparation, so there is no rotation to learn an axis from.
  * Preparation instead gives the orientation reference (gravity), the bias and the noise. The axis is then estimated from the movement and **validated** by its share of the rotational variance (`ROTATION_AXIS_UNSTABLE` if it is below 60 %).
* **Angle.** Trapezoid integration of the filtered angular velocity, then linear detrending (residual bias).
* **Selected hand and direction.**
  * Under the standard grip (screen up, top of the phone toward the fingers), the forearm axis is the phone's long y axis.
  * When the main axis lies within cos ≥ 0.7 of y, it is oriented toward +y and each movement is labeled:
    * RIGHT hand: a decreasing angle = PRONATION (the thumb, on +x, turns up toward +z, which is a rotation about −y);
    * LEFT hand: mirrored.
  * Otherwise the direction is UNKNOWN.
  * Directions only label movements. **All metrics and scores are direction-agnostic and identical for both hands.**
  * The hand is always the user's explicit choice.
* **Translation.** The gyroscope does not respond to translation. Gravity is tracked with the gyroscope (dv/dt = −ω × v, complementary filter τ = 1 s), so the linear acceleration excludes rotation of gravity. Pure translation produces no cycles; this is tested.

## 5. Adaptive cycle detection

* **Turning points.** A maximum of the angle is confirmed once the angle has fallen h below it, and a minimum once it has risen h above it. The movement direction must really reverse.
* **Adaptive hysteresis.** h is the largest of:
  * 10° (minimum amplitude: tiny movements are not movements);
  * a noise term, 3 × baseline noise × 0.25 s, so random shaking does not create reversals;
  * 0.3 × the person's median amplitude from a first pass. Small reversals inside large movements are therefore not extra cycles, and small-amplitude movers are still detected.
* **Minimum peak distance and debounce.** A reversal less than 1 / (2 × 6 Hz) ≈ 83 ms after the previous turning point is implausible. The pair is merged, which also prevents duplicate peaks.
* **Incomplete movements.** The movement before the first turning point and after the last one is not used.
* **Validation (`RotationMovement.rejection`).**
  * `SENSOR_GAP`: a dropout covers more than 25 % of the movement.
  * `TOO_SLOW`: the movement takes longer than half the maximum cycle duration (2.5 s).
* **Cycle (`RotationCycle`).**
  * Movements 2k and 2k + 1. Cycles never overlap.
  * A cycle is valid only if both movements are.
  * Fields: start / peak / end time, duration, amplitude, peak and mean angular velocity, first direction, validity.

## 6. Metrics (`PronationMetrics`)

| # | Metric | Definition |
|---|---|---|
| 1 | Total cycle count | number of cycles (pairs of movements) |
| 2, 3 | Cycles per second / per minute | (movements / 2) / window duration |
| 4, 5 | Mean / median cycle duration | valid cycles |
| 6 | Cycle-duration variability | CV % (≥ 3 values) |
| 7, 8 | Mean / median angular amplitude | valid movements (turning point to turning point) |
| 9 | Amplitude variability | CV % |
| 10 | Mean angular velocity | mean \|ω\| from the first to the last turning point, dropouts excluded |
| 11 | Peak angular velocity | median of the per-movement peaks (and the maximum) |
| 12 | Velocity variability | CV % of the peaks |
| 13–15 | Pauses: count / total / longest | \|ω\| < max(5 °/s, 15 % of the median peak) for ≥ max(400 ms, 30 % of the median cycle), inside the active period |
| 16 | Movement regularity | rhythm score (section 9) from the cycle-duration CV |
| 17 | Movement consistency | consistency score from the share of movements within ±30 % of the typical amplitude and duration, and the peak-velocity CV |
| 18–20 | Early / middle / late performance | segment scores (section 7) |
| 21 | Sampling rate | measured gyroscope rate (and accelerometer rate) |
| 22 | Valid sample % | valid / received |
| 23 | Dropout count | plus estimated missing samples (dropout time / median interval) and the longest gap |
| 24 | Signal quality | data-quality percentage, noise level, axis share |

Also stored:
* movement coverage;
* RMS angular velocity;
* the linear-acceleration RMS (accelerometer only);
* the dominant frequency (reported only with ≥ 3 cycles and ≥ 40 % of the power within ±0.25 Hz);
* the angular-velocity trace at 20 Hz (for the chart).

## 7. Performance trend («روند عملکرد در طول آزمون»)

* **Segments.** The window is split into EARLY (0–33 %), MIDDLE (33–66 %) and LATE (66–100 %). Each valid movement is assigned by its midpoint.
* **Segment measures.** For each segment with ≥ 3 movements:
  * speed (cycle rate);
  * amplitude;
  * regularity (CV of movement durations);
  * consistency.
* **Segment score.** Computed with the same normalization and weights as the main score, without the trend component.
* **State.** From late − early: ≤ −10 points → DECLINING, ≥ +10 → IMPROVING, otherwise STABLE. A missing early or late segment gives INSUFFICIENT_DATA.
* **Per-measure changes.** Amplitude, peak velocity and cycle duration changes (%) are also stored.
* **Wording.** This is a performance trend within 10 seconds, never "bradykinesia" or disease progression.

## 8. Quality (`PronationSupinationQualityEngine`, ENGINEERING thresholds)

| Issue | Status | Rule |
|---|---|---|
| NO_GYROSCOPE_DATA | INVALID | no valid gyroscope sample |
| SAMPLING_RATE_TOO_LOW | INVALID | < 25 Hz |
| RECORDING_INCOMPLETE | INVALID | completeness < 90 % |
| EXCESSIVE_GAPS | INVALID | longest gap > 1000 ms (MAX_ALLOWED_GAP) or > 20 % of the time lost |
| TOO_MANY_INVALID_SAMPLES | INVALID | valid < 90 % (MIN_VALID_SAMPLE_PERCENTAGE) |
| TOO_FEW_SAMPLES | INSUFFICIENT_DATA | < 100 per stream (MIN_VALID_SAMPLES) |
| USABLE_DURATION_TOO_SHORT | INSUFFICIENT_DATA | window − gap time < 7 s (MIN_USABLE_DURATION) |
| NO_MOVEMENT_DETECTED | INSUFFICIENT_DATA | < 2 cycles (MIN_VALID_CYCLES) |
| TOO_FEW_VALID_CYCLES | INSUFFICIENT_DATA | < 2 valid cycles |
| ACCELEROMETER_UNAVAILABLE | LOW_QUALITY | gyroscope-only run (no accelerometer support checks) |
| LOW_SAMPLING_RATE | LOW_QUALITY | < 50 Hz |
| GAPS_PRESENT | LOW_QUALITY | longest gap > 250 ms or > 2 % of the time lost |
| INVALID_SAMPLES_PRESENT | LOW_QUALITY | valid < 99 % |
| SENSOR_UNRELIABLE | LOW_QUALITY | > 10 % of events flagged unreliable |
| STREAMS_MISALIGNED | LOW_QUALITY | start or end differs by > 500 ms |
| HIGH_NOISE | LOW_QUALITY | noise level > 40 °/s (MAX_NOISE_LEVEL) |
| LOW_VALID_CYCLE_PERCENTAGE | LOW_QUALITY | < 70 % of cycles valid |
| LOW_MOVEMENT_COVERAGE | LOW_QUALITY | movement covers < 50 % of the window |
| ROTATION_AXIS_UNSTABLE | LOW_QUALITY | < 60 % of the variance about the main axis |
| GRAVITY_IMPLAUSIBLE | LOW_QUALITY | mean \|a\| outside 6–16 m/s² |

* **Status.** The most severe issue decides the status.
* **Data quality percentage.** From the weakest stream: rate 30, completeness 30, valid samples 20, gaps 20. That is then blended 80/20 with the valid-cycle share.
* **What is stored.**

  | Status | Result |
  |---|---|
  | INVALID, INSUFFICIENT_DATA | **No score and nothing stored.** The screen shows «نتیجه قابل اعتماد نیست.», the reason, «لطفاً آزمون را مجدداً انجام دهید.» and a prominent «تکرار آزمون». |
  | LOW_QUALITY | Stored and scored, with reliability **LIMITED** («نتیجه با اطمینان محدود قابل تفسیر است.»). |

## 9. Motor Performance Index (`PronationSupinationScoreEngine`)

Raw metric → normalization → component 0–100 → weighted combination → index 0–100. Missing
components (fewer than 3 cycles or movements, or no trend) are left out and the weights are
renormalized. Everything is clamped to 0–100.

| Component | Weight | Normalization (internal software reference) |
|---|---|---|
| Speed | 25 % | cycles/s linear from 0.3 (→0) to 2.5 (→100) |
| Rhythm (= regularity) | 25 % | 100 × (1 − cycle CV / 40 %) |
| Amplitude | 20 % | size: median amplitude linear 10° → 90°; × (0.7 + 0.3 × stability), stability = 1 − amplitude CV / 50 % |
| Consistency | 15 % | mean of (share within ±30 %) and (1 − peak-velocity CV / 50 %) |
| Performance trend | 15 % | 100 up to a 5-point decline (late vs early segment score), 0 at a 40-point decline |

The reference ranges cover what the detector is designed to measure. The low end of 0.3 cycles/s
is the slowest rhythm that still gives ≥ 2 cycles in 10 s; 10° is the detection minimum. **They
are not clinical normal values.**

`ScoringReference` receives a `ReferenceContext` (hand, and later age, sex, dominant hand and
device). A future validated population reference can replace `InternalSoftwareReference` without
changing the engines. The reference name is stored with every result.

## 10. Interpretation (`PronationSupinationInterpreter`, software bands)

| Index | Band | Persian |
|---|---|---|
| 80–100 | GOOD | عملکرد حرکتی خوب |
| 60–79 | ACCEPTABLE | عملکرد حرکتی قابل قبول |
| 40–59 | REDUCED | کاهش نسبی عملکرد حرکتی |
| 20–39 | SIGNIFICANTLY_REDUCED | کاهش قابل توجه عملکرد حرکتی |
| 0–19 | VERY_LOW | عملکرد حرکتی بسیار پایین |

The boundaries are exact; tests check that the bands have no gaps or overlaps.

Notes are language-neutral codes, which the UI turns into Persian sentences:
* speed and rhythm good, or lower than the internal reference (with "not a sign of disease by itself");
* amplitude lower;
* pauses;
* declining or improving trend;
* quality good or limited;
* gyroscope only;
* repeat recommended.

Each result shows «این نمره یک شاخص داخلی نرم‌افزار است و هنوز بر اساس داده هنجاری جمعیت بزرگ اعتبارسنجی نشده است.».

## 11. Diagnostics (debug builds only)

Logcat tag `PSDiag`. Patients never see it and no personal data is logged.

| Line | Content |
|---|---|
| `RATE` | per sensor per second: timestamp, count, intervals, latest x/y/z |
| `GAP` | any interval > 100 ms |
| `STATE` | transitions |
| `PREFLIGHT` | gyroscope samples and rate, timestamp validity, accelerometer presence |
| `CYCLE` | each movement: start, duration, amplitude, peak and mean velocity, direction, rejection |
| `RESULT` | rates, dropouts, baseline status and noise, hysteresis, cycles, rate, durations, amplitude, velocity, pauses, noise, axis share, quality, score, components, trend, band |

## 12. Verification status

* **Synthetic (JVM).**
  * `PronationSupinationEngineTest`: the 24 required scenarios plus shaking, orientation independence, translation, baseline calibration, gyroscope-only, cycle ordering and reproducibility.
  * `PronationSupinationScoringTest`: bounds, normalization, weights, band boundaries and interpretation.
  * `PronationSupinationSessionTest`: phases, interruptions, preflight, gyroscope-only, storage failure and retry.
  * Storage tests, including v3 → v4 migration and legacy rows.
  * UI tests and app navigation tests.
* **Pending: physical devices** (`pronation-supination-test.md`).
