# Speech assessment: algorithm, metrics and limits

Version: `speech-algo-1.0.0`, scoring `not-scored`. Source: `app/src/main/java/com/example/parkinson/speech/`.

## Privacy and retention

- Audio is recorded with `AudioRecord` (VOICE_RECOGNITION source, 16-bit mono PCM) into memory only. Nothing is
  written to storage, nothing is uploaded, and no transcript is produced.
- Consent is explicit and per visit (`SpeechViewModel.consent`). The recorder cannot start without it (the session
  returns CONSENT_NOT_GIVEN).
- The stored row holds measurements, quality findings, the actual sample rate and the versions. It has no audio
  field (a unit test checks this).

## Capture

- Requested rate 16 kHz; the rate the recorder actually delivers is stored and used for analysis. Recordings below
  8 kHz are rejected. Duration is computed from the delivered samples, never from the requested rate.
- Integrity before analysis: samples present, rate supported, and at least 80 % of the planned duration delivered.
- The recorder is stopped and released in a `finally` block on every path, including cancellation.

## Signal processing

| Stage | Method | Parameters (engineering, not validated) |
|---|---|---|
| Framing | 40 ms frames, 10 ms hop | `SpeechSignalConfig` |
| Level | RMS in dBFS per frame (relative to full scale, not calibrated SPL) | silence floor -120 dBFS |
| Noise floor | 10th percentile of frame levels | robust to speech occupying most of the time |
| Speech activity | frame level >= noise floor + 10 dB, majority filter over 5 frames | `activityMarginDb` |
| Pitch (F0) | normalized autocorrelation over lags for 60-400 Hz; first local maximum reaching 85 % of the global maximum (octave guard); parabolic lag refinement | voicing threshold 0.6 |
| HNR | Boersma (1993): 10 log10(r / (1 - r)) on the periodicity r of voiced frames | r clamped to [1e-4, 0.9999] |
| Pauses | inactive runs of at least 250 ms strictly between the first and last active frame; long pause >= 1 s | `minPauseMs`, `longPauseMs` |
| Acoustic energy events | local peaks of the 3-frame smoothed level, >= noise + 8 dB, prominence >= 3 dB, spacing >= 120 ms | `eventMarginDb`, `minEventSpacingMs` |

Assumptions and limits:
- Pitch assumes quasi-stationary voicing within a 40 ms frame. At 16 kHz the lag resolution is one sample; F0 is
  refined by interpolation only.
- HNR is an estimate from the autocorrelation peak. It is not a measurement of noise power, and it depends on the
  periodicity of the frame.
- Speech activity includes unvoiced speech. It is an energy decision, not a voicing decision.

## Metrics per task

Each metric is one of: measured (read directly from the signal), derived (computed from measured quantities), or
not computed. An unavailable value is stored with its reason, never as zero.

- **Sustained vowel** (`SUSTAINED_VOWEL`, 5 s): duration, voiced duration, voiced share, F0 median and SD (semitones),
  intensity median and SD (dBFS, relative), HNR median. Pitch and intensity statistics need at least 2 s of voiced
  frames.
- **Repeated syllable** (`REPEATED_SYLLABLE`, 8 s): speech span, pauses, energy-event count, event rate and interval
  variability. These are acoustic energy events, not syllable identities. The rate needs at least 5 events.
- **Reading** (`READING`, fixed 30 s recording; the passage has about 27 words, roughly 10 s of speech): speech span, pauses (count, mean,
  long-pause ratio), voiced share of active frames, F0 and intensity statistics, HNR. Speaking rate and articulation
  rate are reported as unavailable: they need a word or syllable count, and no Persian transcription is provided.
- **Spontaneous speech** (`SPONTANEOUS`, optional, 20-30 s, not standardized): the same acoustic set as reading.
  Speaking rate is unavailable for the same reason. Its results are labelled as not standardized.

Not computed in this version:
- **Jitter and shimmer**: cycle-to-cycle perturbation needs reliable cycle-level periods. At 16 kHz the period
  resolution (62.5 microseconds) is too coarse for jitter of the order of a percent, so no value is reported.
- **Speaking rate and articulation rate**: need reliable word and syllable counts.

## Quality

Findings (`SpeechQualityIssue`), each with a status:
- Invalid: no samples, unsupported sample rate, clipping above 1 % of samples.
- Insufficient: truncated capture, no speech-level signal (overall RMS at or below -50 dBFS), signal-to-noise ratio
  below 3 dB.
- Low quality (result saved with a warning): clipping above 0.1 %, signal-to-noise ratio below 10 dB.

A low-volume voice is not rejected by level alone. The level is reported, and the signal-to-noise ratio decides.

## Result model

- Measured values, derived values and unavailable values with reasons are stored separately in one metric map.
- Derived values are computed from the measured quantities by the documented formulas.
- The report states that interpretation is descriptive. No normative reference values, clinical cut-offs or severity
  scores exist in this version.
- Comparison: the previous result of the same task only. Differences describe two recordings; they are not evidence
  of treatment effect or disease change.

## Limits and what is not verified

- All thresholds are engineering choices. They are not validated against clinical or normative data.
- Unit tests use synthetic audio only (pure tones, bursts, white noise, silence). They verify the algorithms on known
  signals. They do not show that real speech, real phones or real rooms give valid results.
- Not verified on a physical device: microphone capture, the actual sample rate on common hardware, the effect of
  device audio processing, permission and interruption behaviour, and the full UI flow.
- Persian speech transcription is not implemented. Any future word-based metric needs a validated Persian recognizer
  and its own validation study.
