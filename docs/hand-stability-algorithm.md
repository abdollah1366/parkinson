# Hand Stability (نگه‌داشتن دست ثابت) – algorithm specification

| | |
|---|---|
| Algorithm version | `hs-algo-1.0.0` (`HandStabilityVersions.ALGORITHM_VERSION`) |
| Scoring version | `hs-score-0.1.0-preliminary` (`HandStabilityVersions.SCORING_VERSION`) |
| Clinical validation | **None.** Every threshold and reference value is an engineering value chosen for technical robustness. None has been calibrated against patient or normative data. |
| Device validation | **None yet.** Verified only on deterministic synthetic sensor data (JVM tests). |

> The output is called **"شاخص ثبات دست"** (hand stability index). It describes how still the phone
> was held, as measured by the phone's own sensors. It is **not** a tremor diagnosis and not a
> Parkinson's disease measure, and it must never be presented as one.

Bump `ALGORITHM_VERSION` for any change to cleaning, metrics, frequency analysis or quality rules.
Bump `SCORING_VERSION` for any change to the index. Both versions are stored with every result.

## 1. Protocol

1. The patient sits with the elbow free and holds the phone face-up in the selected hand.
2. Sensor check: the accelerometer and gyroscope must exist (`SensorCheckScreen`).
3. **Preparation: 5 s.** The sensors are already running so they are settled. Samples are discarded.
4. **Recording: 15 s.** Every accelerometer and gyroscope event is kept.
5. Processing, then quality control, then the result (or a Persian explanation with Retry/Home).

## 2. Data path and layers

```
SensorManager (TYPE_ACCELEROMETER + TYPE_GYROSCOPE, 10 ms requested, no batching, own HandlerThread)
  -> AndroidMotionSensorSource (sensors/)            MotionSample(type, timestampNs, x, y, z, unreliable)
  -> HandStabilitySession (stability/)               state machine; keeps samples during RECORDING only
  -> HandStabilityEngine.analyze (stability/)        pure, deterministic
       CLEANING  -> STREAM STATS -> METRICS -> FREQUENCY -> QUALITY -> INDEX
  -> HandStabilityResult (stored in Room table hand_stability_assessments, DB v2)
  -> HandStabilityResultScreen (Persian wording, no clinical labels)
```

* **Timestamps:** only `SensorEvent.timestamp` is used for analysis. Both sensors share that clock
  base, so the two streams are synchronized on it. The analysis window is the overlap of the two
  streams. `SystemClock.elapsedRealtime` only paces the countdown and detects a stalled sensor.
* **Sampling rate is never assumed.** 100 Hz is requested (≤ 200 Hz, so Android 12+ needs no
  `HIGH_SAMPLING_RATE_SENSORS` permission). The actual rate and intervals are measured per stream.
* **No raw stream is stored.** Only derived measurements are stored, with no personal identifier.

## 3. Cleaning (per stream)

* Drop non-finite values and physically impossible values: |a| > 16 g per axis, |ω| > 2000 °/s per axis.
* Sort by timestamp. Drop samples whose timestamp is not strictly increasing (duplicates).
* Dropped samples count as invalid: `validPercent = valid / received`.

## 4. Stream statistics (per sensor)

| Metric | Definition |
|---|---|
| sampling rate | (n − 1) / span |
| median interval, interval SD | from consecutive timestamps |
| dropout | interval > max(3 × median, 40 ms) |
| dropout time | Σ (gap − median) over dropouts |
| longest gap | maximum interval |
| completeness | (span + median) / 15 s, capped at 100 % |
| unreliable share | events flagged `SENSOR_STATUS_UNRELIABLE` |

## 5. Motion metrics

Units: m/s², °/s, degrees. A **centered 1 s moving average** (±0.5 s, on real timestamps)
estimates the slowly varying part: gravity, gyroscope bias and posture drift. Subtracting it
leaves the *movement* content above ~1 Hz ("dynamic").

| Metric | Definition |
|---|---|
| acceleration magnitude mean / SD / variance / range | of \|a\| (includes gravity, independent of orientation) |
| **acceleration RMS** (`accDynamicRms`) | RMS of \|a − MA(a)\|: hand movement with gravity removed |
| angular velocity magnitude mean / RMS / SD / variance / max | of \|ω\| |
| **angular velocity RMS** (`gyroDynamicRms`) | RMS of \|ω − MA(ω)\| |
| movement range (`rotationRangeDeg`) | per axis: integrate (ω − mean ω) with the trapezoid rule on real timestamps, remove the linear trend, take peak-to-peak; report the largest axis |
| tilt change (`tiltChangeDeg`) | largest angle between the smoothed gravity vector and its starting direction |

## 6. Frequency-domain features (only when justified)

A dominant oscillation frequency is reported only if all three conditions hold.

1. The measured gyroscope rate is at least 30 Hz, so 12 Hz lies safely below Nyquist.
   Otherwise the status is `SAMPLING_TOO_LOW`.
2. `gyroDynamicRms` ≥ 0.5 °/s, which is above typical MEMS gyroscope noise (~0.1–0.2 °/s).
   Otherwise the status is `MOVEMENT_TOO_SMALL`, because the frequency of noise is meaningless.
3. Power within ±0.5 Hz of the 3–12 Hz peak is at least 25 % of the 1–20 Hz power.
   A pure oscillation scores ~100 % and white noise ~6 %. Otherwise the status is `NO_CLEAR_PEAK`.

Method: the dynamic gyroscope axes are linearly resampled to a uniform grid of min(50 Hz, measured
rate). A Hann window is applied. The DFT power is evaluated every 0.1 Hz from 1 to 20 Hz and summed
over the three axes. The 3–12 Hz band share is stored as `oscillationBandPowerPercent`.
The UI calls the result "بسامد غالب نوسان" and states that it has no clinical meaning by itself.

## 7. Quality control

| Issue | Severity | Rule (either stream) |
|---|---|---|
| NO_ACCELEROMETER_DATA / NO_GYROSCOPE_DATA | INVALID | no valid sample |
| SAMPLING_RATE_TOO_LOW | INVALID | < 15 Hz |
| RECORDING_INCOMPLETE | INVALID | completeness < 90 % |
| EXCESSIVE_GAPS | INVALID | dropout share > 20 % or longest gap > 1 s |
| TOO_MANY_INVALID_SAMPLES | INVALID | valid < 90 % |
| TOO_FEW_SAMPLES | INSUFFICIENT_DATA | < 150 valid samples |
| LOW_SAMPLING_RATE | LOW_QUALITY | < 40 Hz |
| GAPS_PRESENT | LOW_QUALITY | dropout share > 2 % or longest gap > 250 ms |
| INVALID_SAMPLES_PRESENT | LOW_QUALITY | valid < 99 % |
| SENSOR_UNRELIABLE | LOW_QUALITY | > 10 % unreliable events |
| STREAMS_MISALIGNED | LOW_QUALITY | start or end differs by > 500 ms |
| PHONE_REORIENTED | LOW_QUALITY | tilt change > 30° (the phone was turned) |
| GRAVITY_IMPLAUSIBLE | LOW_QUALITY | mean \|a\| outside 7–12.5 m/s² |

The status is the most severe issue. INVALID and INSUFFICIENT_DATA produce **no result**: the
patient sees a Persian explanation and Retry. LOW_QUALITY produces a stored result **without an
index**.

Quality score (0–100) comes from the weaker stream: 30 % rate (vs 40 Hz), 30 % completeness,
20 % valid share and 20 % gap share (vs the 20 % limit).

## 8. Index – "شاخص ثبات دست" / "شاخص عملکرد" (preliminary)

Computed only for VALID recordings:

```
component(x, still, moving) = 100 × clamp( (ln moving − ln max(x, still)) / (ln moving − ln still), 0, 1 )
rotation     = component(gyroDynamicRms, 0.5 °/s,  30 °/s)
acceleration = component(accDynamicRms,  0.02 m/s², 1.5 m/s²)
index        = round((rotation + acceleration) / 2)
```

"still" is roughly sensor noise on a resting phone. "moving" is large deliberate movement. These are
transparent engineering anchors, **not clinical cut-offs**. A higher index means a steadier phone.
Use it to compare a person with their own earlier recordings, not with other people.

## 9. Session robustness

| Situation | Behavior |
|---|---|
| Sensor missing / registration fails | ERROR(SENSOR_UNAVAILABLE) before any countdown |
| No event for 1.5 s (sensors disabled, throttling) | ERROR(SENSOR_STOPPED) |
| Leaving the screen, backgrounding, locking | INVALID(Interrupted); nothing is stored |
| Screen rotation (configuration change) | recording continues: session and sensors live in the ViewModel; a real reorientation is caught by PHONE_REORIENTED |
| Storage failure | ERROR(STORAGE_FAILURE); no result shown |
| Analysis exception | ERROR(UNEXPECTED); no crash |

The display is kept on while a test runs. Sensors are unregistered on every terminal state.

## 10. Diagnostics (debug builds only)

Logcat tag `HSDiag` (`SensorDiagnostics`). Patients never see it.

* `RATE`: per sensor per second, with sample count, mean/max interval and the latest x/y/z.
* `GAP`: any interval > 100 ms.
* `STATE`: session transitions.
* `RESULT`: rates, dropouts, quality, metrics and index.

## 11. Verification status

* **Verified (synthetic, `HandStabilityEngineTest`, `HandStabilitySessionTest`):**
  * stable phone, slow sway, 3.5–11 Hz oscillations, broadband noise;
  * short and long gaps, 8 / 16 / 25 / 97.3 / 203 Hz rates;
  * 60° reorientation, a truncated recording, a missing gyroscope, an empty recording;
  * NaN samples, shuffled and duplicate events, a late stream start;
  * determinism, index anchors and monotonicity;
  * session timing, interruption, sensor stop, storage failure and retry.
* **Not yet verified:** real phones. In particular:
  * the real sampling rates and jitter on target devices;
  * the noise floor of a phone resting on a table (index ≈ 100 expected);
  * typical values while holding the phone still;
  * whether 30° is the right reorientation limit.

  Collect `HSDiag` logs on several devices before tuning, and bump the versions on any change.
