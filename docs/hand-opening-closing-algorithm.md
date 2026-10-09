# Hand Opening/Closing: algorithm

Status: **engineering version `ho-algo-1.1.0`, not clinically validated, not validated on a physical device.**
The test reports measurements and technical quality only. It has no performance score, and it never
states or implies a diagnosis or a disease severity.

## 1. Pipeline

```
MediaPipe result (21 landmarks, upright image)
  -> OpenCloseFrameExtractor      feature per frame (or OUT_OF_FRAME / NO_HAND / MULTIPLE_HANDS / ERROR)
  -> HandOpenCloseSession         one recording window: COUNTDOWN 3 s -> RECORDING 10 s -> PROCESSING
  -> HandOpenCloseEngine.analyze  window, duplicates, glitches, smoothing, levels, cycle state machine,
                                  metrics, quality
  -> HandOpenCloseResult          stored with the timestamped series (see section 7)
```

The live cycle counter during RECORDING runs the same engine on the frames received so far
(`countCycles`), so there is one source of cycle events.

## 2. Feature (finger extension, version ho-algo-1.1.0)

For each of the index, middle, ring and pinky fingers, with landmarks MCP, PIP, DIP, TIP:

    extension = |MCP -> TIP| / (|MCP -> PIP| + |PIP -> DIP| + |DIP -> TIP|)

The per-frame feature is the mean over the four fingers. Distances are in pixels of the upright image,
so the aspect ratio is respected.

- **Why this and not fingertip-to-wrist / palm size.** That feature is roughly (palm length + finger
  length) / palm size when open, so it depends on finger proportions. A person with short fingers never
  reached a fixed "fully open" value, and every attempt was rejected. The extension is scale-free per
  finger: a straight finger is 1.0 for any length, and a curled finger gives less.
- Unitless, 0..about 1. Values above 1.25 are landmark glitches.
- The thumb is not used.
- A frame is VALID only when the wrist and all 16 finger landmarks are finite and inside the image
  (2 % margin), and each bone is at least 4 px. Otherwise it is OUT_OF_FRAME.
- Known limitation: foreshortening (fingers pointing at the camera) lowers the ratio.

Reference values (engineering, to be confirmed on device): straight hand about 0.95-1.0; slightly bent
hand still counts as open at 0.85; a fist is about 0.4-0.6.

## 3. Signal processing

1. Window: only frames with timestamps in [start, end] (monotonic clock).
2. Duplicates: frames with the same timestamp are counted once (the first wins).
3. Glitches: a VALID frame whose opening is not finite, not positive, or above 1.25 (extension units) is treated
   as OUT_OF_FRAME.
4. Segments: a gap longer than 250 ms between valid frames splits the signal. Smoothing and cycles never
   cross a split.
5. Smoothing: zero-phase exponential (forward + backward), time constant 60 ms, using the real frame
   interval (irregular sampling is handled).
6. Levels: the recording's own envelope, low = 5th and high = 95th percentile of the smoothed signal.
   If high - low < 0.25, there is no movement and no cycle is counted.

## 4. Hysteresis levels and state machine

Levels are fractions of the envelope range above the low level:

| Level        | Fraction | Used for                                  |
|--------------|----------|-------------------------------------------|
| openEnter    | 0.65     | entering OPEN (from OPENING, or at start) |
| openExit     | 0.55     | leaving OPEN                              |
| closeExit    | 0.45     | leaving CLOSED                            |
| closeEnter   | 0.35     | entering CLOSED                           |

The states and the only counted sequence:

```
OPEN --(< openExit)--> CLOSING --(<= closeEnter)--> CLOSED --(> closeExit)--> OPENING --(>= openEnter)--> OPEN
  ^                       |                                                      |
  +----(>= openEnter)-----+  partial closing (not counted)                        |
                                        CLOSED <--(<= closeEnter)-- OPENING      |  partial opening (not counted)
```

- A cycle is counted once, when OPEN is re-entered from OPENING. That OPEN starts the next cycle, so one
  movement is never counted twice.
- The cycle start is an observed opening crossing. An OPEN found at the start of the recording, or after
  a tracking gap, has an unobserved start: it does not start a cycle. The first complete cycle is counted
  at the next opening crossing.
- A segment split resets the machine and discards the cycle in progress (counted as `discardedByDropout`).
- A closing that returns to OPEN, or an opening that returns to CLOSED, is a partial movement: it is
  counted as `partialClosings` / `partialOpenings` and never as a cycle.

### Cycle acceptance

A candidate cycle (start = opening crossing, end = next opening crossing) is counted only if all hold:

| Rule                                   | Value        | Purpose                                       |
|----------------------------------------|--------------|-----------------------------------------------|
| duration >= minCycleMs                 | 400 ms       | a fast spike is not a movement                |
| duration <= maxCycleMs                 | 4000 ms      | a pause is not a repeated movement            |
| amplitude (raw max - min) >= minRange  | 0.25         | tiny motion is never a cycle                  |
| raw max >= minFullOpening              | 0.85         | the hand must really open fully               |
| raw min <= maxFullClosing              | 0.60         | the hand must really close to a fist          |

The last two are absolute references on the extension scale (a geometric reference, not a person-specific one). Without them, a recording of half-openings would make the half-open
level the "open" level and count partial movements as complete cycles (this is covered by a test).
Candidates that fail the duration rules count as `rejectedTooFast` / `rejectedTooSlow`; those that fail
the range rules count as `rejectedNotFullRange`.

The stage floor used in an earlier draft (50 ms for each closing and opening stage) was removed: at
about 30 Hz sampling it rejected real movements. Spikes are handled by smoothing, hysteresis and the
400 ms minimum cycle.

## 5. Quality (technical, never the person)

| Layer          | Issue                                                        | Status            |
|----------------|--------------------------------------------------------------|-------------------|
| Recording      | NO_FRAMES; RECORDING_INCOMPLETE (< 90 % of the window); INSUFFICIENT_FPS (< 8 Hz) | INVALID |
| Hand           | WRONG_HAND (>= 50 % of confident frames); MULTIPLE_HANDS (>= 30 %); NO_HAND_DETECTED | INVALID / INSUFFICIENT |
| Tracking       | INSUFFICIENT_TRACKING (< 60 % of time); EXCESSIVE_DROPOUT (> 2 s); REDUCED_TRACKING; DROPOUTS_PRESENT; OUT_OF_FRAME_FREQUENT (> 10 % of frames) | INSUFFICIENT / LOW |
| Cycles         | NO_CYCLES_DETECTED; TOO_FEW_CYCLES (< 3 completed)           | INSUFFICIENT      |
| Quality        | LOW_FPS (< 20 Hz); HAND_SIDE_UNCERTAIN; LIGHTING_AFFECTED_TRACKING; NOISY_SIGNAL (noise > 20 % of amplitude); PARTIAL_MOVEMENTS_FREQUENT (> 50 % of excursions) | LOW_QUALITY |

The status is the most severe issue. Only VALID and LOW_QUALITY recordings produce a result. The technical
quality score (0-100) is the mean of four factors: frame rate, tracking share, dropout share, and the share
of completed excursions. It is shown as "کیفیت داده (فنی)" and is not a performance score.

## 6. Measurements

All values come from the frame timestamps (monotonic clock), never from frame counts or the UI timer.

- Completed cycles; cycle rate = completed cycles / usable seconds (window minus dropouts), in Hz.
- Cycle duration: mean, SD, CV %.
- Interval between consecutive cycle completions: mean, SD, CV %.
- Amplitude per cycle (raw max - min of the extension, unitless 0..1): mean, SD, CV %.
- Mean opening and closing stage durations.
- Partial openings and closings, rejected candidates, incomplete final cycle flag.
- Measurement duration, usable duration, tracking share, longest dropout, valid frame share, fps.

Not implemented on purpose: a 0-100 clinical score, any interpretation band, any severity estimate.

## 7. Persistence and recalculation

Table `hand_open_close_assessments` (Room, schema v6, automatic migration from v5; no existing row changes).
Each row stores the measurements, the quality, and the timestamped series so the cycles can be re-derived
later without the camera:

- `cycleEndTimesMs`, `cycleDurationsMs`, `cycleAmplitudes`: one entry per completed cycle.
- `sampleTimesMs`, `sampleOpenings`, `sampleStatuses`: one entry per frame in the window
  (openings rounded to 4 decimals; `NaN` for frames without usable landmarks; status letters
  V, N, M, O, E).

No camera image and no personal identifier is stored. `algorithmVersion = ho-algo-1.1.0`;
`scoringVersion = not-scored`.

## 8. Tests

`HandOpenCloseEngineTest` (23 tests), `HandOpenCloseSessionTest` (8), `HandOpenCloseStorageTest` (8, including the 5 to 6 migration), `HandOpenCloseUiTest` (2).
All engine and session tests use SYNTHETIC openings (a 1 Hz open/close waveform). They check the rules
of the algorithm only and are not measurements of any person. They cover: a complete cycle, consecutive
cycles, no movement, partial opening, partial closing, missing landmarks, tracking loss during a cycle,
duplicate timestamps, irregular frame intervals, a hand leaving the frame, reset and finalization, and
the absence of duplicate events.

## 9. Known limitations

- **Timestamps are submission times.** The frame time is the moment the frame is handed to MediaPipe
  (`HandLandmarkerManager.nextTimestamp`), not the camera exposure time. The delay between exposure and
  submission is not measured. The same applies to Finger Tapping; see `TapFrame`.
- **No device validation yet.** The thresholds (levels, 0.85 / 0.60 extension references, 400 ms minimum cycle)
  are engineering choices. They must be checked against recordings from real hands before any use.
- **Stiff or unusual hands.** The extension is independent of finger length, but a stiff hand that cannot
  close below 0.60 or open above 0.85 still gets "not enough complete movement". Such a result must be
  checked on device; the references must not be lowered until every attempt passes.
- **Occlusion.** The shared MediaPipe manager tracks one hand (`maxHands = 1`), so a second hand is not
  reported. A hand partly covered by the other hand loses landmarks: those frames become OUT_OF_FRAME or
  NO_HAND and are handled as tracking loss.
- **Lighting.** Brightness is a warning only; the tracking share decides usability.
