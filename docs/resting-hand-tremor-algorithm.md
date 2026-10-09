# Resting Hand Tremor: algorithm and limits

**Algorithm version:** `rt-algo-1.0.0` · **Scoring version:** `not-scored` (no tremor score)
**Code:** `tremor/` (config, frame extractor, engine, session, result), `data/RestingTremorEntity.kt`

> Observational motor measurement. It is not a diagnostic tool, it does not classify disease or
> severity, and it has no clinically validated tremor grade. Every threshold below is an
> **engineering** value and has not been validated on patients.

## Protocol

- Participant sits, rests the forearm and hand on a stable surface, and relaxes the hand.
- Hand selection (left/right) is explicit; MediaPipe handedness is only checked against it.
- 3 s preparation countdown, then a **15 s** recording (`RestingTremorSessionConfig`).
- Recording starts only after the participant presses **شروع آزمون**. A stop control returns to
  the start state without producing a result.

## 1. Feature: palm position from MediaPipe landmarks

Per camera frame (`RestingTremorFrameExtractor`):

- Palm centre = mean of landmarks **0** (wrist), **5, 9, 13, 17** (knuckles), in upright-image pixels.
  Fingertips and the thumb are not used.
- Hand size reference `handScalePx` = pixel distance wrist (0) → middle knuckle (9).
- A frame is `VALID` only if all five palm landmarks are finite and inside the image (2 % margin)
  and the hand size is at least 8 px. Otherwise `OUT_OF_FRAME` (no position).
- `NO_HAND`, `MULTIPLE_HANDS` and `ERROR` come from the tracking result.

## 2. Units

The recording's **median hand size** `H` (px) converts positions to hand lengths:
`p = palm_px / H`. All displacement values below are **image-plane fractions of the participant's
own hand size** (shown as "% of hand length"). They are not millimetres: a single RGB camera
without calibration does not give physical displacement.

## 3. Quality checks (`RestingTremorEngine.analyze`)

| Check | Rule | Status |
|---|---|---|
| Timestamps | any timestamp earlier than its predecessor | INVALID (`NON_MONOTONIC_TIMESTAMPS`), no metrics |
| Frame count | < 30 frames | INSUFFICIENT_DATA, no metrics |
| Valid frames | < 2 valid frames | INSUFFICIENT_DATA, no metrics |
| Frame rate | 1000 / median frame interval < 10 Hz | INSUFFICIENT_DATA |
| Sampling | > 5 % of intervals outside [0.5, 2] × median | INSUFFICIENT_DATA |
| Duration | actual < 80 % of planned | INSUFFICIENT_DATA |
| Valid share | < 70 % → insufficient; 70–90 % → LOW_QUALITY | |
| Interruptions | gap between valid frames (incl. head/tail) > 250 ms; longest > 1 s → insufficient; > 3 → LOW_QUALITY | |
| Gross movement | range of the 1 s local mean of the palm > 0.5 hand lengths | INVALID (`GROSS_MOVEMENT`) |
| Multiple hands | > 30 % of frames | INVALID |
| Hand identity | > 50 % of valid frames labelled as the other hand | INVALID |

Status priority: INVALID > INSUFFICIENT_DATA > LOW_QUALITY > VALID. Only VALID and LOW_QUALITY
results are stored as results. Others show the invalid-result screen with a repeat instruction.

## 4. Signal processing

1. **Slow drift:** centred 1 s local mean of the valid palm positions (x and y). Drift = larger of the two
   ranges (max − min). It is reported and used for the gross-movement check.
2. **Resampling:** valid positions are linearly interpolated onto a uniform **20 Hz** grid. Grid points
   inside an interruption longer than 250 ms are left empty and are not bridged.
3. **Detrending:** the centred **1 s moving average** (over non-empty samples) is subtracted from x and y.
   This removes the slow component (posture and drift) and keeps the faster movement.
4. **Amplitude:** RMS of the radial residual, `sqrt(mean(rx² + ry²))`, × 100 → % of hand length.
5. **Frequency:** Hann window over the detrended series, direct DFT (periodogram) on the grid
   0.5 → 9.0 Hz in 0.05 Hz steps, x and y power summed. The analysis band is limited by the 20 Hz
   grid (Nyquist 10 Hz) and by the 15 s recording. It is an engineering band, not a clinical tremor band.
   - Coverage ≥ 90 % of the grid, a peak with ≥ 3 cycles in the recording, and a peak prominence
     (peak power / mean band power) ≥ 6. Otherwise **the frequency is reported as unavailable** (null).
   - Periodicity = share of band power within ±0.5 Hz of the peak, in %.

## 5. Reported metrics

| Metric | Unit | Notes |
|---|---|---|
| Actual duration | ms | last − first frame timestamp |
| Planned duration | ms | 15 000 |
| Valid-frame share | % | valid / all frames |
| Interruptions: count, total, longest | count, ms, ms | gaps > 250 ms between valid frames |
| Median frame interval, frame rate | ms, Hz | all frames |
| Irregular intervals | % | outside [0.5, 2] × median |
| Hand size (median) | px | reference only |
| Signal coverage | % | grid samples with data |
| Slow drift | % of hand length | range of the 1 s local mean |
| Amplitude (RMS, radial) | % of hand length | null when not computable |
| Dominant frequency | Hz | null = unavailable (never shown as 0) |
| Spectral prominence | ratio | peak power / mean band power |
| Periodicity | % | band power within ±0.5 Hz of the peak |

The quality score shown is the **technical valid-frame share** (rounded). It is not a tremor score.

## 6. Storage

`resting_tremor_assessments` (Room v7, automatic migration from v6): all metrics above (nullable where
unavailable), quality status and issues, start/end wall-clock times, algorithm version and the signal
configuration summary, and the timestamped palm series (per-frame time, status letter, palm x/y in pixels,
hand size in pixels). No camera images or video are stored.

## 7. Known limitations

- Image-plane motion of a 2D landmark model is not calibrated distance. Depth changes (the hand moving
  toward or away from the camera) appear as scale changes, not as displacement.
- Voluntary movement cannot be separated from tremor with one RGB camera. Gross movement is detected only
  when it is large enough to move the 1 s local mean. Slow voluntary movement below 0.5 hand lengths is
  not rejected.
- MediaPipe landmark jitter adds noise at high frequencies. The prominence threshold reduces, but does not
  remove, false peaks from noise.
- The 1 s moving average also removes slow components that may be part of the phenomenon of interest.
- Thresholds (interruption 250 ms / 1 s, valid share 70 % / 90 %, drift 0.5 hand lengths, prominence 6,
  frame rate 10 Hz) are engineering values. They need validation on real hands and cameras.
- The frequency estimate uses 15 s, so its resolution is about 0.07 Hz; it is not a precise rate.
- No lighting, skin-tone or camera-model validation has been performed.

## 8. Validation status

- Synthetic unit tests (`RestingTremorEngineTest`) cover the known-frequency case, stationary noise,
  missing samples, tracking loss, repeated interruptions, irregular timing, non-monotonic timestamps,
  short recordings, gross movement, multiple hands, and no-data cases.
- Storage round-trip and the 6 → 7 migration are covered by Robolectric tests.
- **Not yet verified on a physical device.** Camera behaviour, MediaPipe output and the thresholds above
  have not been tested with real hands.
