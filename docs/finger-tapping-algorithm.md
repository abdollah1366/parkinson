# Finger Tapping analysis – algorithm specification

| | |
|---|---|
| Algorithm version | `ft-algo-1.0.0` (`FingerTappingVersions.ALGORITHM_VERSION`) |
| Scoring version | `ft-score-0.1.0-preliminary` (`FingerTappingVersions.SCORING_VERSION`) |
| Clinical validation | **None.** All thresholds are engineering values chosen for technical robustness. They have not been calibrated against patient or normative data. |

> This software measures finger movement. It does not diagnose Parkinson's disease or any
> other condition, and its score is not a disease probability. Results are meant to support
> monitoring and clinician review only.

Bump `ALGORITHM_VERSION` for any change to signal processing, detection, metric formulas or
quality rules, and `SCORING_VERSION` for any change to the score. Both versions are stored with
every assessment.

## 1. Pipeline and layers

```
CameraX ImageAnalysis (KEEP_ONLY_LATEST)
  -> HandLandmarkerManager (MediaPipe LIVE_STREAM)
  -> HandTrackingResult  --resultListener (synchronous, every result)-->  FingerTappingSession
       RAW DATA          tapping/raw        TapFrame per result inside the recording window
       SIGNAL            tapping/signal     TapSignalProcessor
       EVENTS            tapping/detection  TapStateMachine / TapDetector (+ LiveTapCounter, display only)
       METRICS           tapping/metrics    FrameStatisticsCalculator, MotorMetricsCalculator
       QUALITY CONTROL   tapping/quality    QualityAssessor
       SCORING           tapping/scoring    PerformanceScorer
       RESULT            tapping/result     FingerTappingAssessment (stored in Room)
       INTERPRETATION    ui/                Persian wording only; no clinical labels
```

The `HandLandmarkerManager.result` StateFlow conflates values and is used **only** by the UI
(overlay, guidance). The session receives every result through `resultListener`, called on the
MediaPipe result thread. Frames, the recording window and the live counter are guarded by one
lock in the session.

All analysis layers are pure functions of their inputs. The same recording always produces the
same events, metrics, quality report and score.

## 2. Session

States: `IDLE → COUNTDOWN (3 s) → RECORDING (10 s) → PROCESSING → DONE | INVALID | ERROR`.

* **Recording window.** A result belongs to the recording if its camera timestamp
  `t ∈ [start, start + 10 000 ms]`. Frames from the countdown are never used. After the
  window closes, the session waits 200 ms for frames captured in time but still inside MediaPipe.
* **No partial results.** If the session is interrupted it ends as `INVALID(Interrupted)`, and
  no metrics are computed. Interruptions are: leaving the screen, rotation, the app going to the
  background, or the screen locking.
* **Errors** produce `ERROR` and no result:
  * a camera error reported by the screen;
  * no MediaPipe result for 2 s (frame starvation);
  * 5 consecutive MediaPipe errors;
  * a storage failure;
  * any unexpected analysis exception.
* **Saving.** A usable result (quality `VALID` or `LOW_QUALITY`) is saved to Room *before*
  `DONE` is published. `INVALID` and `INSUFFICIENT_DATA` recordings are not stored.

## 3. Raw data (`TapFrame`)

Each MediaPipe result becomes a `TapFrame` with these fields:

* `index`, `timestampMs`, `status`;
* thumb-index distance (px);
* palm scale (px);
* handedness confidence and side.

The possible statuses are `VALID`, `NO_HAND`, `WRONG_HAND`, `MULTIPLE_HANDS`, `LOW_CONFIDENCE`,
`OUT_OF_FRAME` and `ERROR`.

* Distance `d = |P4 − P8|`, where P4 is the thumb tip and P8 the index tip. Coordinates are
  converted to pixels of the upright image (x·width, y·height), because normalized x and y have
  different scales.
* Palm scale `s = mean(|P0−P5|, |P0−P9|, |P0−P17|, |P5−P17|)`, using the wrist and the
  index/middle/pinky MCP joints. The mean of four segments changes less than any single segment
  when the hand rotates.
* `OUT_OF_FRAME`: a frame gets this status if any of these holds:
  * fewer than 21 landmarks;
  * non-finite coordinates;
  * the thumb or index tip lies more than 2 % outside the image;
  * the palm scale is under 1 px.

## 4. Signal processing (`TapSignalProcessor`, `SignalConfig`)

1. **Normalization.** The opening is `o = d / s̃`, where `s̃` is the median palm scale of the valid
   frames within ±500 ms. The unit is "palm sizes", so the signal does not depend on the distance
   to the camera, and no parameter is a pixel value.
2. **Invalid frames** (any status other than `VALID`) are excluded. An opening above 3.0 palm
   sizes is rejected as a landmark glitch.
3. **Dropout segmentation.** Gaps of up to 250 ms between valid frames are bridged. A longer gap
   starts a new segment, and the detector resets at segment starts.
4. **Smoothing.** A zero-phase exponential filter (forward then backward) with τ = 25 ms, where
   `α = 1 − exp(−Δt/τ)` uses the real frame interval. It restarts in every segment.
5. **Noise estimate.** `σ = 1.4826 · MAD(raw − smoothed)`.
6. **Movement floor.** `floor = min(max(0.15, 6σ), max(0.40, 0.15))`. A local range below the
   floor is treated as "no movement" (`active = false`), and no tap can be produced there.
7. **Adaptive envelope.** For each sample, the baseline `low` = P5 and the peak level `high` = P95
   of the smoothed signal within a centered ±1.5 s window. The whole recording is used when the
   window has fewer than 8 samples, which happens at the edges and after dropouts.
8. **Adaptive hysteresis thresholds.** `close = low + 0.35·(high − low)` and
   `open = low + 0.65·(high − low)`.

## 5. Tap event detection (`TapStateMachine`, `DetectionConfig`)

State machine on the smoothed signal:

```
UNKNOWN --(≤ close)--> CLOSED --(≥ open)--> OPEN --(≤ close)--> [tap candidate] → CLOSED
```

* **Tap definition.** A closing (OPEN → CLOSED) that follows an opening from a closed position.
  The tap timestamp is the moment the signal crosses the close threshold, and inter-tap intervals
  use these timestamps.
* **Amplitude.** Measured on the raw signal: the raw peak in the OPEN phase minus the raw minimum
  in the preceding CLOSED phase, in palm sizes. Smoothing attenuates peaks more at higher rates
  (about 8 % at 2 Hz and 34 % at 5 Hz for 30 fps), so measuring amplitude on the smoothed signal
  would make amplitude depend on speed.
* **A candidate is rejected when:**
  * *debounce*: the closing comes less than 100 ms after the previous accepted tap;
  * *minimum event duration*: less than 60 ms passed from the closed minimum to the closing;
  * *minimum amplitude*: the amplitude is below `max(0.4·range, 0.5·floor)`.
* **Dropout recovery.** At a segment start (gap > 250 ms) the state resets to UNKNOWN. A cycle
  in progress is discarded and counted as `discardedByDropout`, so a dropout can never be counted
  as a tap. When `active = false` the state also resets, and the discarded cycle is counted as
  `discardedInactive`.
* **First event.** If the recording starts with the fingers open, the first closing is counted.
  Its start level is the local baseline (`startEstimated = true`), its confidence is multiplied by
  0.7, and the duration check is skipped.
* **Final incomplete event.** If the recording ends in OPEN, that cycle is not counted and
  `incompleteFinalCycle = true` is set.
* **`TapEvent` fields:**
  * index and timestamp;
  * start (closed minimum) and peak time;
  * amplitude, peak opening and trough opening;
  * opening duration (start → peak) and closing duration (peak → closing);
  * total duration (start → closing);
  * confidence and `startEstimated`.
* **Confidence** (0..1) is `√(amplitude/range) × continuity × tracking × start`:
  * continuity is 0.6 if a frame gap inside the cycle exceeds 150 ms, otherwise 1;
  * tracking is the mean MediaPipe handedness confidence over the cycle;
  * start is 0.7 when the start was estimated, otherwise 1.
* **Live counter.** The same state machine and parameters, with a causal EMA and a trailing 3 s
  envelope. It is shown during RECORDING only and never stored. The stored count comes from the
  offline analysis and may differ by about one tap.

## 6. Metrics (`MotorMetricsCalculator`)

`D` is the recording duration (end − start, normally 10 s). `Iₖ` are the intervals between
consecutive tap timestamps, and `Aₖ` are the tap amplitudes.

| Metric | Formula |
|---|---|
| Tap count | number of accepted taps |
| Taps per second | count / D |
| Taps per 10 s | taps per second × 10 |
| Mean / median interval | mean(I), median(I) |
| Interval SD | sample SD (n−1) of I |
| Interval CV % | SD(I) / mean(I) × 100 |
| Tap-to-tap variability % | mean(\|Iₖ₊₁ − Iₖ\|) / mean(I) × 100 |
| Mean / median / min / max amplitude | of A (palm sizes) |
| Amplitude SD, CV % | sample SD(A), SD/mean × 100 |
| Tap duration | mean / median of event duration |
| Movement consistency % | clamp(100 − (interval CV + amplitude CV)/2, 0, 100). Engineering composite. |
| Pause count | intervals > 2 × median interval (needs ≥ 3 intervals) |
| Amplitude trend | mean amplitude in the early / middle / late third of D (a third needs ≥ 2 taps); relative change = (late − early)/early × 100 |
| Rate trend | taps per second in each third; relative change late vs early |
| Amplitude slope | least-squares slope of A over time, as % of mean amplitude per second (needs ≥ 4 taps) |
| FPS | (frames − 1) / (last − first frame timestamp) |
| Valid frame % | VALID frames / all frames |
| Dropout | a period without valid hand data longer than max(150 ms, 3 × median frame interval). Includes start→first valid and last valid→end. Count, total and longest duration are reported. |
| Recording completeness % | D / planned duration × 100 (max 100) |

A metric that needs more taps than were recorded is `null` ("not measurable"). It is never 0.

**Amplitude trend** is reported as a measured trend only. A negative value means the late taps
were smaller than the early ones. The app does not label it pathological.

## 7. Quality control (`QualityAssessor`, `QualityThresholds`)

Statuses, from best to worst: `VALID`, `LOW_QUALITY`, `INSUFFICIENT_DATA`, `INVALID`. The
status is the worst severity among the issues found.

| Issue | Severity | Rule |
|---|---|---|
| NO_FRAMES | INVALID | < 10 frames |
| RECORDING_INCOMPLETE | INVALID | completeness < 95 % |
| INSUFFICIENT_FPS | INVALID | fps < 12 |
| LOW_FPS | LOW_QUALITY | 12 ≤ fps < 20 |
| WRONG_HAND | INVALID | ≥ 30 % of frames show the other hand |
| MULTIPLE_HANDS | INVALID | ≥ 30 % of frames show several hands |
| NO_HAND_DETECTED | INVALID | valid < 60 % and (no valid frame or ≥ 50 % no-hand) |
| TOO_FEW_VALID_FRAMES | INVALID | valid < 60 % (other cause) |
| REDUCED_VALID_FRAMES | LOW_QUALITY | 60 % ≤ valid < 85 % |
| EXCESSIVE_DROPOUT | INVALID | dropout > 30 % of D or longest > 2 s |
| DROPOUTS_PRESENT | LOW_QUALITY | dropout > 10 % of D or longest > 750 ms |
| UNSTABLE_TRACKING | LOW_QUALITY | palm-scale CV > 25 % (hand moving towards or away from the camera) |
| NO_TAPPING_DETECTED | INSUFFICIENT_DATA | 0 taps |
| TOO_FEW_TAPS | INSUFFICIENT_DATA | < 4 taps (rhythm needs ≥ 3 intervals) |
| NOISY_SIGNAL | LOW_QUALITY | noise σ / median amplitude > 0.20 |
| LOW_EVENT_CONFIDENCE | LOW_QUALITY | mean tap confidence < 0.5 |

* **Quality score** (0–100) is the mean of four factors, each ramping linearly between its
  "unusable" and "good" limits:
  * fps from 12 to 20;
  * valid fraction from 60 % to 85 %;
  * dropout share from 30 % down to 10 %;
  * mean tap confidence.
* **What each status produces:**
  * `VALID`: result and score.
  * `LOW_QUALITY`: result **without** a score, plus a caution message.
  * `INSUFFICIENT_DATA` / `INVALID`: no result. The user sees a simple explanation and a retry.

## 8. Finger Tapping Performance Score (`PerformanceScorer`, `ScoringConfig`)

**Preliminary engineering index, NOT clinically validated, NOT a diagnosis or probability.**
Its only intended use is to compare a person with their own earlier tests. It is computed only
when quality is `VALID`, and otherwise it is `null`.

Components (0–100, linear and clamped). The reference values are engineering anchors, not
clinical cutoffs:

| Component | Mapping | Weight |
|---|---|---|
| Rate | taps per second / 5.0 | 0.30 |
| Rhythm | 1 − interval CV / 50 % | 0.20 |
| Amplitude | mean amplitude / 1.0 palm size | 0.20 |
| Amplitude trend | 1 + min(change, 0) / 50 % (no decrease or an increase = 100) | 0.15 |
| Consistency | movement consistency % / 100 | 0.10 |
| Data quality | quality score / 100 | 0.05 |

`total = round(Σ wᵢ·cᵢ / Σ wᵢ)`. A component that cannot be measured (the trend, when a third of
the recording has fewer than 2 taps) is left out, and the remaining weights are renormalized.

## 9. Storage

Room table `finger_tapping_assessments`, schema version 1. The schema is exported to
`app/schemas/`.

* **Stored:** one row per usable assessment, with the measured values, quality status and
  issues, quality score, score components, and the algorithm and scoring versions.
* **Not stored:** raw frames, images or video, and personal identifiers.
* **Name-based fields.** Enums are stored by name. Unknown names read back safely: an unknown
  quality status reads as `INVALID`, and unknown issues are dropped.
* **Backup.** Disabled (`allowBackup="false"`).

## 10. Validation status

* **Verified by deterministic unit tests** on synthetic signals:
  * slow, normal, fast and very fast tapping;
  * small and large amplitudes;
  * irregular rhythm, noise and dropouts;
  * wrong hand, no hand and low fps;
  * incomplete recordings;
  * debounce, hysteresis, first and final events;
  * amplitude decrement;
  * reproducibility of results and scores.
* **Not yet verified:** behavior on real devices with real hands. In particular:
  * the thresholds in sections 4, 5 and 7;
  * MediaPipe handedness mapping;
  * real frame rates;
  * how close the live count is to the final count.

  See the device validation checklist in the project report. Thresholds must be revisited with
  real recordings, and any change must bump the version.
