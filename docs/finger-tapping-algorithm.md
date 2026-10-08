# Finger Tapping analysis – algorithm specification

| | |
|---|---|
| Algorithm version | `ft-algo-1.1.0` (`FingerTappingVersions.ALGORITHM_VERSION`) |
| Scoring version | `1.0` (`FingerTappingVersions.SCORING_VERSION`) |
| Scoring reference | `internal-software-reference-ft-1.0`, **not normative** |
| Clinical validation | **None.** Every threshold, weight and range is an engineering value chosen for technical robustness. None has been calibrated against patient or normative data. |
| Device validation | **Pending** (see `finger-tapping-device-test.md`) |

> This software measures finger movement. It does not diagnose Parkinson's disease or any
> other condition, and its index is not a disease probability. Results support monitoring and
> clinician review only.

Bump `ALGORITHM_VERSION` for any change to signal processing, detection, metric formulas or
quality rules. Bump `SCORING_VERSION` for any change to the score. Both versions are stored with
every assessment.

## 0. Root-cause analysis of "insufficient data" / "lighting" (fixed in ft-algo-1.1.0)

The app never measured light. The two "lighting" texts came from:
* the tip `invalid_tip_light`, which was attached **only** to the `INSUFFICIENT_FPS` rejection;
* the camera screen's "کیفیت تصویر" ("image quality") line, which was computed from the
  **handedness score**.

Usable motor data were lost or left unscored at six points:

| # | Where | Defect | Effect |
|---|---|---|---|
| 1 | `HandLandmarkerManager` / `HandednessMapper.decide` | Handedness score < 0.6 → `LowConfidence`; confident other label → `WrongHandDetected`; **both dropped the landmarks**. The handedness score is left/right certainty and drops when the hand is seen edge-on, which is typical while pinching. | Valid landmark frames became invalid, raising valid-frame and dropout failures (`TOO_FEW_VALID_FRAMES` / `EXCESSIVE_DROPOUT` → "insufficient data"). Gaps > 250 ms also split the signal, so taps were discarded (`TOO_FEW_TAPS`). |
| 2 | `setNumHands(2)` | In LIVE_STREAM mode MediaPipe re-runs palm detection on every frame while fewer than numHands hands are tracked. With one hand, every frame pays for palm detection plus landmarks. | A lower result rate led to `INSUFFICIENT_FPS` (< 12 fixed), which showed «نور محیط را بیشتر کنید». Dim light also lowers the camera frame rate, compounding this. |
| 3 | `TapStateMachine.evaluate` | Tap confidence multiplied by the mean **handedness score**. | Correct taps of an edge-on hand scored low, giving `LOW_EVENT_CONFIDENCE` → LOW_QUALITY. |
| 4 | `PerformanceScorer` | Score only for `VALID`. | Every LOW_QUALITY result was saved **without a score** ("score not recorded"). |
| 5 | `QualityAssessor` | Tracking measured as a share of **frames**. Frames without a hand are cheaper to process (palm detection only), so they arrive more often. A fixed fps ≥ 12 rule applied regardless of the tap rate. | Tracking was underestimated. Slow, measurable tapping was rejected at low fps. |
| 6 | Camera screen | "کیفیت تصویر: …" derived from the handedness score; `LowConfidence` shown as poor quality. | Misleading "image/lighting" feedback. |

The fixes, in order:
1. Landmarks are always kept: `HandDetected` carries a `HandSideStatus` (MATCHES / UNCERTAIN / MISMATCH) and the hand is checked over the whole recording.
2. `maxHands = 1`.
3. Tap confidence uses amplitude, continuity and start only.
4. LOW_QUALITY results are scored with reliability LIMITED.
5. Tracking is the **time** share with landmarks, and the frame-rate requirement depends on the measured tap rate.
6. Brightness is measured (mean luma) and is a warning layer only.
7. CameraX RGBA output removes the per-frame YUV conversion.
8. The live counter uses the final pipeline.

No threshold was simply lowered. Every requirement now follows from the 10-second protocol and from sampling theory (sections 7 and 8).

## 1. Pipeline and layers

```
CameraX ImageAnalysis (KEEP_ONLY_LATEST, RGBA_8888)        frames dropped while busy are counted (sensor timestamps)
  -> HandLandmarkerManager (MediaPipe LIVE_STREAM, 1 hand)  FrameInfo per frame: sequence, mean luma, camera frames skipped
  -> HandTrackingResult --resultListener (every result, synchronous)--> FingerTappingSession
       RAW DATA        tapping/raw        TapFrame (landmark validity, side status, luma, sequence)
       SIGNAL          tapping/signal     TapSignalProcessor
       EVENTS          tapping/detection  TapStateMachine / TapDetector  (one source: live count = same pipeline)
       METRICS         tapping/metrics    FrameStatisticsCalculator, MotorMetricsCalculator (+ segments)
       QUALITY         tapping/quality    FingerTappingQualityEngine (camera / hand / tracking / taps / recording)
       SCORE           tapping/scoring    FingerTappingScoreEngine  (+ FingerTappingInterpreter)
       RESULT          tapping/result     FingerTappingAssessment (+ TapPayload) -> Room -> History
```

* **What the UI uses.** The `result` StateFlow (conflated) feeds the overlay and guidance. The
  `liveStatus` StateFlow feeds the rolling camera and tracking status.
* **What the session uses.** The session gets **every** result through `resultListener`; the
  high-frequency pipeline never goes through a conflated StateFlow.
* **Determinism.** All analysis layers are pure functions; the same recording always produces the same output.

## 1a. Handedness (which physical hand is in the frame)

`HandednessMapper` is the only place a MediaPipe label becomes a hand side. Its mapping has not
changed since `ft-algo-1.0.1`: frames are unmirrored, so the label is the physical hand. The old
`ft-algo-1.0.0` flip was wrong and was removed after the first device test.

Since `ft-algo-1.1.0` the per-frame decision only labels the frame:

| Decision | Status | Landmarks |
|---|---|---|
| Label is the selected hand, score ≥ 0.6 | MATCHES | kept |
| Score < 0.6 | UNCERTAIN | kept |
| Label is the other hand, score ≥ 0.6 | MISMATCH | kept |

Only one hand is tracked, so all frames come from the same physical hand. The recording-level
check (section 7) counts the side-confident frames:

| Share of side-confident frames labelled as the other hand | Result |
|---|---|
| ≥ 50 % | `WRONG_HAND` (INVALID) |
| ≥ 20 % | `HAND_SIDE_UNCERTAIN` (LOW_QUALITY) |
| fewer than 10 side-confident frames at all | `HAND_SIDE_UNCERTAIN` (LOW_QUALITY) |

## 1b. Landmark coordinate frame (overlay and distances)

| Step | Coordinate frame |
|---|---|
| `ImageProxy.toBitmap()` | sensor-oriented (unrotated) frame, not mirrored |
| MediaPipe landmarks | normalized to that unrotated input image, origin top-left, y down |
| `HandLandmarkMapper.toHandLandmarks(…, rotationDegrees)` | rotated clockwise into the upright frame: 90° `(1−y, x)`, 180° `(1−x, 1−y)`, 270° `(y, 1−x)` |
| `HandDetected.imageWidth/imageHeight` | upright size of **that** frame (rotation and size are kept per frame until its result arrives) |
| `HandLandmarkMapper.mapToView` | FILL_CENTER scale and offset; the front camera mirrors x only |

## 1c. MediaPipe configuration

All values are engineering defaults.

| Option | Value | Why |
|---|---|---|
| Running mode | LIVE_STREAM | asynchronous; MediaPipe drops frames while busy (measured as pipeline drops) |
| numHands | 1 | palm detection runs only when the hand is lost, not on every frame (section 0, row 2) |
| minHandDetectionConfidence | 0.5 | MediaPipe default. Lower admits false palms; higher loses real hands in dim or blurred frames |
| minHandPresenceConfidence | 0.5 | MediaPipe default; below it the hand is re-detected |
| minTrackingConfidence | 0.5 | MediaPipe default |
| handedness limit | 0.6 | only decides whether the left/right label is trusted; never discards landmarks |
| Timestamps | `SystemClock.uptimeMillis`, strictly increasing | same clock as the session window |
| Rotation | `ImageInfo.rotationDegrees` via `ImageProcessingOptions` | landmarks are rotated to upright afterwards (section 1b) |
| Model | `hand_landmarker.task` asset | the sensor check verifies it exists |

`ImageProxy.close()` is always called, in `CameraAnalyzer`'s `finally` block.

## 2. Session

States: `IDLE → COUNTDOWN (3 s) → RECORDING (10 s) → PROCESSING → DONE | INVALID | ERROR`.

* **Recording window.** A result belongs to the recording if its frame timestamp is in
  `[start, start + 10 000 ms]`. Frames from the countdown are never used. After the window closes,
  the session waits 200 ms for frames that were captured in time but are still inside MediaPipe.
* **Live tap count «ضربه‌ها: X».** Every 300 ms the frames received so far go through
  `FingerTappingAnalyzer.detectTaps()`, which is the **same** signal processing and detection as
  the final result, off the main thread. The old separate causal `LiveTapCounter` was removed, so
  there is one authoritative tap-event source. `liveStats` carries frames, landmark frames,
  tracking, taps, rejected candidates and rate for the debug panel.
* **No partial results.** Leaving the screen, rotation, backgrounding or locking ends the session
  as `INVALID(Interrupted)`.
* **Errors.** Camera error, no result for 2 s, 5 consecutive MediaPipe errors, storage failure and
  any unexpected exception each give `ERROR`.
* **Saving.** A usable result (`VALID` or `LOW_QUALITY`) is saved to Room *before* `DONE` is
  published. `INVALID` and `INSUFFICIENT_DATA` recordings are never stored.

## 3. Raw data (`TapFrame`)

Fields: index, timestamp, status, thumb-index distance (px), palm scale (px), handedness score,
side, side status, mean luma, sequence number, and camera frames skipped.

* **Statuses.** `VALID` (landmarks usable), `NO_HAND`, `MULTIPLE_HANDS`, `OUT_OF_FRAME` and `ERROR`.
  Handedness no longer creates a status.
* **Distance.** `d = |P4 − P8|` in pixels of the upright image.
* **Palm scale.** `s = mean(|P0−P5|, |P0−P9|, |P0−P17|, |P5−P17|)`.
* **OUT_OF_FRAME** means any of:
  * fewer than 21 landmarks;
  * non-finite coordinates;
  * a tip more than 2 % outside the image;
  * a palm scale under 1 px.

## 4. Signal processing (`TapSignalProcessor`, `SignalConfig`)

1. **Normalization.** Opening `o = d / s̃`, where `s̃` is the median palm scale within ±500 ms. The unit is palm sizes.
2. **Exclusions.** Only `VALID` frames are used; an opening above 3.0 is rejected as a glitch.
3. **Gaps.** Gaps of up to 250 ms are bridged; a longer gap starts a new segment.
4. **Smoothing.** Zero-phase exponential smoothing, τ = 25 ms, using the real frame intervals.
5. **Noise.** `σ = 1.4826 · MAD(raw − smoothed)`.
6. **Movement floor.** `min(max(0.15, 6σ), 0.40)`; below it there is no movement.
7. **Adaptive envelope.** P5 / P95 within ±1.5 s.
8. **Hysteresis thresholds.** Close at 0.35 and open at 0.65 of the local range.

## 5. Tap events (`TapStateMachine`, `DetectionConfig`)

```
UNKNOWN --(≤ close)--> CLOSED --(≥ open)--> OPEN --(≤ close)--> [tap candidate] → CLOSED
```

* **Tap.** A closing that follows an opening from a closed position. Its timestamp is the close-threshold crossing.
* **Rejected candidates** (never counted, counted as rejected):
  * debounce < 100 ms after the previous tap (maximum about 10 Hz);
  * event duration < 60 ms;
  * amplitude < max(0.4·range, 0.5·floor).

  Each tap is counted once, on the OPEN→CLOSED transition, never per frame, so jitter cannot
  produce duplicate taps.
* **Dropouts and inactivity** reset the state; a cycle in progress is discarded.
* **First and last events.** If the recording starts with the fingers open, the first closing is
  counted (`startEstimated`). An open cycle at the end is not counted.
* **`TapEvent` fields:** timestamp; start and peak time; amplitude, peak and trough opening;
  opening, closing and total duration; **closing velocity** (amplitude / closing duration, palm
  sizes per second); confidence; `startEstimated`. The hand and finger (thumb–index) are the same
  for the whole test and are stored with the result.
* **Confidence** (0..1) = `√(amplitude/range) × continuity × start`:
  * continuity is 0.6 with a gap > 150 ms inside the cycle;
  * start is 0.7 when the start was estimated.

  The handedness score is **not** part of it (section 0, row 3).

## 6. Metrics (`MotorMetricsCalculator`, `FrameStatisticsCalculator`)

Tap metrics are unchanged from 1.0.x:
* count, rate, taps per 10 s;
* intervals: mean, median, SD, CV, tap-to-tap variability;
* amplitude: mean, median, SD, CV, minimum, maximum;
* tap duration, consistency, pauses;
* amplitude and rate trend, amplitude slope.

New:
* closing velocity: mean and CV;
* **segments**: tap count, rate, mean amplitude, amplitude CV and interval CV for each third.

Frame statistics are all derived from frame timestamps:

| Statistic | Definition |
|---|---|
| frames analyzed | MediaPipe results in the window |
| valid landmark frames | `VALID` results |
| fps | (frames − 1) / (last − first timestamp) |
| dropout | a period without valid landmarks longer than max(150 ms, 3 × median interval), recording edges included |
| usable duration | window − dropout time |
| **tracking rate** | usable / window, i.e. the **time** share with landmarks (used for quality) |
| frame tracking rate | valid / all frames (reported only: it is biased, because frames without a hand arrive faster) |
| observed duration | first to last result plus one median interval |
| camera frames skipped | from sensor timestamps: round(interval / camera period) − 1, where the period is the shortest recent interval |
| pipeline frames dropped | sequence numbers submitted to MediaPipe without a result |
| mean luma | mean of the 0..255 luma sampled on a 32 × 24 grid of each frame |
| side status counts | MATCHES / UNCERTAIN / MISMATCH frames |

## 7. Quality (`FingerTappingQualityEngine`, `QualityThresholds`)

There are separate layers. Camera/image quality is a warning signal: it can make a result
LOW_QUALITY only when tracking is also reduced, and it can never reject one.

| Layer | Measure | GOOD | WARNING | POOR |
|---|---|---|---|---|
| Camera / image (`CameraQuality`) | mean luma | 50–225 | < 50 or > 225 | < 20 |
| Hand detection + landmark tracking (`HandTrackingQuality`) | tracking rate (time) | ≥ 85 % | ≥ 60 % | < 60 % |

| Issue | Severity | Rule | Reasoning |
|---|---|---|---|
| NO_FRAMES | INVALID | < 10 results | nothing was recorded |
| RECORDING_INCOMPLETE | INVALID | observed duration < 90 % of 10 s | frames must really cover the test |
| INSUFFICIENT_FPS | INVALID | fps < max(8, 4 × measured tap rate) | ≥ 2 samples per opening and per closing half-cycle |
| WRONG_HAND | INVALID | ≥ 50 % of side-confident frames show the other hand (≥ 10 frames) | recording-level consensus |
| MULTIPLE_HANDS | INVALID | ≥ 30 % of frames (only with maxHands > 1) | |
| NO_HAND_DETECTED | INSUFFICIENT_DATA | no valid landmark frame | |
| INSUFFICIENT_TRACKING | INSUFFICIENT_DATA | tracking rate < 60 % (< 6 s of landmarks) | rhythm needs most of the 10 s |
| EXCESSIVE_DROPOUT | INSUFFICIENT_DATA | longest gap > 2 s | a fifth of the test missing in one piece |
| NO_TAPPING_DETECTED / TOO_FEW_TAPS | INSUFFICIENT_DATA | 0 / < 4 taps | rhythm needs ≥ 3 intervals |
| LOW_FPS | LOW_QUALITY | fps < 20 | |
| CAMERA_FRAMES_DROPPED | LOW_QUALITY | > 30 % of camera frames dropped | |
| REDUCED_TRACKING | LOW_QUALITY | 60 % ≤ tracking < 85 % | |
| DROPOUTS_PRESENT | LOW_QUALITY | dropout > 10 % or longest > 750 ms | |
| HAND_SIDE_UNCERTAIN | LOW_QUALITY | ≥ 20 % other-hand labels, or < 10 side-confident frames | |
| UNSTABLE_TRACKING | LOW_QUALITY | palm-scale CV > 25 % | |
| LIGHTING_AFFECTED_TRACKING | LOW_QUALITY | camera not GOOD **and** tracking not GOOD | |
| NOISY_SIGNAL | LOW_QUALITY | noise / median amplitude > 0.20 | |
| LOW_EVENT_CONFIDENCE | LOW_QUALITY | mean tap confidence < 0.5 | |

* **Quality score** (0–100, «کیفیت داده»). The mean of four ramps:
  * fps from the required rate to 20;
  * tracking from 60 % to 85 %;
  * dropout share;
  * mean tap confidence.
* **What each status produces:**

  | Status | Result |
  |---|---|
  | VALID | result, score, reliability RELIABLE |
  | LOW_QUALITY | result and score, reliability **LIMITED** («نتیجه با اطمینان محدود قابل تفسیر است.») |
  | INSUFFICIENT_DATA | no result; «داده کافی برای محاسبه نتیجه وجود ندارد.» |
  | INVALID | no result; «نتیجه قابل اعتماد نیست.» |

* **Tips.** The lighting tip («تشخیص دست در این شرایط پایدار نیست. لطفاً نور محیط را بهتر کنید.»)
  appears **only** for insufficient tracking together with a camera warning. The frame-rate tip is
  accurate: close other apps, and note that dim light slows some cameras.

## 8. Motor Performance Index (`FingerTappingScoreEngine`, `ScoringConfig`)

«شاخص عملکرد حرکتی». It uses engineering weights and an internal software reference: NOT
clinically validated, NOT a diagnosis or probability.

| Component | Weight | Normalization |
|---|---|---|
| Speed | 30 % | tap rate, linear from 0.5 Hz (0) to 5 Hz (100) |
| Regularity | 25 % | 100 × (1 − interval CV / 50 %) |
| Amplitude | 20 % | mean opening, linear from 0.15 (movement floor) to 1.0 palm size |
| Consistency | 15 % | movement consistency % |
| Performance trend | 10 % | 100 up to a 5-point decline (late vs early segment score), 0 at 40 points |

* **Segment score.** Speed, regularity and amplitude of one third, which needs ≥ 3 taps.
* **Trend state.** DECLINING at ≤ −10 points, IMPROVING at ≥ +10, otherwise STABLE.
  INSUFFICIENT_DATA when the early or late third cannot be scored.
* **Missing components** are left out and the weights renormalized; everything is clamped to 0–100.
* **Bands** (shared `MotorPerformanceBand`): 0–19, 20–39, 40–59, 60–79, 80–100.
* **Interpretation** (`FingerTappingInterpreter`):
  * speed and rhythm good / lower than the internal reference (with the "not a diagnosis" sentence);
  * amplitude lower;
  * trend stable / declining / improving;
  * quality good / limited;
  * lighting warning;
  * repeat recommended.

## 9. Storage

Room table `finger_tapping_assessments`. **DB v5** adds columns through an automatic migration, so
existing rows are kept (`FingerTappingStorageTest` checks v4 → v5).

* **Stored:**
  * all metrics and the quality status, issues and score;
  * score components and trend state, the early / middle / late segment scores, reliability, interpretation notes and reference name;
  * the camera and tracking layers, tracking rate, usable duration, frames analyzed and valid, mean luma, camera and pipeline drops;
  * closing velocity;
  * a **compact per-tap payload**: tap times (ms from start), amplitudes, closing velocities and durations;
  * the algorithm and scoring versions.
* **Not stored:** frames, images or video, and personal identifiers.
* **Older rows** (no reliability column) read back as RELIABLE if VALID and LIMITED otherwise.
  Unknown enum names degrade safely.
* **History** lists every type, newest first, with hand, date, the «شاخص عملکرد» and quality.
  Tapping an entry opens the stored result.

## 10. Diagnostics (debug builds only)

* **Logcat `FTDiag`:**
  * `FRAME` and `HAND` lines, with label, score, decision, luma and sequence;
  * `FPS`;
  * `TAP` per event;
  * `RESULT` with duration, observed duration, frames analyzed, valid landmark frames, no-hand
    and out-of-frame counts, side counts, camera and pipeline drops, fps, required fps, tracking
    rate, usable duration, luma, camera and tracking layers, taps, rejected candidates, mean
    interval, rate, amplitude, amplitude CV, longest gap, dropouts, noise, quality, issues, score
    and trend.
* **Debug panels:** the camera screen shows live camera, tracking, frame and tap figures; the
  result screen shows the final layers, frames, drops, taps, quality and score.
* No personal data is logged.

## 11. Validation status

* **Verified on synthetic data:**
  * the analyzer, pipeline (all scenarios in section 0), score engine and session (including live = final count);
  * storage, including the v4 → v5 migration;
  * the UI.
* **Pending on real devices.** See `finger-tapping-device-test.md`.
