# Pronation / Supination test – physical-device test protocol

This protocol calibrates and checks **algorithm 1.0 / scoring 1.0** on real phones. All thresholds
are engineering values that have only been verified on synthetic data. These sessions are technical
checks with healthy volunteers, not clinical studies. Do not record names or other personal data.

## 1. Device setup

1. Install a **debug** build (`./gradlew :app:assembleDebug`); `PSDiag` logging exists only in debug builds.
2. Connect with USB debugging and run `adb logcat -s PSDiag` (adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`).
3. Turn off battery saver. In Developer options, make sure the "Sensors off" quick-setting tile is not active.
4. Note for each run: phone model, Android version, hand, condition (slow, normal, …), and your manual count.

## 2. Sensor requirements

* **Gyroscope: required.** Without it the sensor check shows «سنسور ژیروسکوپ در این دستگاه در دسترس نیست.» and the test cannot start.
* **Accelerometer: optional.** Without it the sensor check warns, the test runs on the gyroscope only, and the result is LOW_QUALITY («نتیجه با اطمینان محدود قابل تفسیر است.»).
* **Sampling rate.** At least 25 Hz measured during preparation (`PREFLIGHT` line); otherwise the test stops before recording. Below 50 Hz the result is LOW_QUALITY.

## 3. How to hold the phone (standard grip)

* Sit, upper arm against the body, elbow bent at about 90°, forearm horizontal and pointing forward.
* Phone in the palm, **screen facing up, top of the phone toward the fingertips**, held securely by the fingers and thumb.
* This grip puts the forearm axis along the phone's long axis. Direction labels (pronation/supination) are only produced when the detected axis matches it (`forearmAxis=true` in `RESULT`).

## 4. Hand selection

On the instruction screen, choose «دست راست» or «دست چپ» before «شروع آزمون». The result and history
show the selected hand. Run every condition below with **both hands**.

## 5. Performing the test

1. Instruction screen → hand → «شروع آزمون» → sensor check → «ادامه» → «شروع آزمون».
2. **Preparation, 5 s:** hold the phone still («گوشی را ثابت نگه دارید و برای شروع آماده شوید.»).
3. **Countdown:** 3, 2, 1, then «شروع کنید».
4. **10 s:** turn the palm up and down repeatedly, keeping the elbow still.
5. Wait for the result. Note the score, band and cycle count.

## 6. Manual counting method

* Count **full cycles**: palm down → palm up → palm down = 1. Count only after «شروع کنید» and stop at the end of the 10 s.
* Best: film the hand at 30 fps or more next to the screen and count frame by frame afterwards.
* Compare with `cycles=` in `RESULT` (the app does not count the incomplete movement at the start or end, so expect the app to be equal to your count or 1 lower).
* Also compare the rhythm with `medianCycleMs=`. For example, 10 cycles in 10 s should give about 1000 ms.

## 7–14. Conditions

| # | Condition | How | Expected algorithm behavior |
|---|---|---|---|
| 7 | Slow | about 1 cycle every 2 s (0.5 Hz), 90–120° | 4–5 cycles, medianCycleMs ≈ 2000, VALID; speed component low |
| 8 | Normal | comfortable, about 1–1.5 cycles/s, 90° | count matches ±1, VALID, rhythm ≥ 80 |
| 9 | Fast | as fast as comfortable (2–4 Hz), 45–90° | count matches ±1; no merged or missed cycles; check `CYCLE` durations ≥ 125 ms |
| 10 | Small amplitude | about 20–30° of rotation, normal speed | still counted; below about 10° nothing is counted (by design) |
| 11 | Large amplitude | full range (≥ 150°), normal speed | counted; amplitude component 100; check axisShare > 90 % |
| 12 | Irregular | change speed and size deliberately | count matches; cycle CV high; rhythm and consistency lower than in test 8 |
| 13 | Pause | rotate 4 s, hold still 2 s, rotate 4 s | `pauses=1`, about 2000 ms; «در طول حرکت، توقف‌های کوتاهی ثبت شد.» |
| 14 | No movement | hold still for the whole test | INSUFFICIENT_DATA: «نتیجه قابل اعتماد نیست.», reason «حرکت چرخشی کافی ثبت نشد…», nothing in History |

Additional checks:
* Shake the phone without rotating it. Expect no or few cycles; check that the noise and hysteresis values make sense.
* Move the phone back and forth (translation). Expect no cycles.
* Press Back during recording. Expect the dialog; «ادامه آزمون» continues, «خروج» returns to the test list and nothing is saved.
* Press Home or lock the screen during recording. Expect INTERRUPTED and nothing saved.
* Rotate the device to landscape before starting. The screen orientation stays locked during the test.
* TalkBack: the countdown is announced, and the index card reads as «شاخص عملکرد حرکتی … از ۱۰۰، …».

## 15. Expected algorithm behavior (summary)

* **Hysteresis:** `hysteresisDeg` ≈ max(10°, 0.3 × median amplitude), larger only with a very noisy baseline.
* **Baseline:** `baseline=CALIBRATED` when the phone was still in preparation, with `baselineNoise` usually below 3 °/s for a hand-held phone.
* **Quality:** VALID for clean runs. LOW_QUALITY for gyroscope-only runs, < 50 Hz sampling, small gaps, or an unstable axis. INVALID / INSUFFICIENT_DATA give no score and are not saved.
* **Trend:** STABLE for steady movement. DECLINING when the late third is ≥ 10 points below the early third.

## 16. What to inspect in the logs

* `PREFLIGHT`: `gyroRateHz` (real device rate), `monotonic` (should be 100 %).
* `RATE`: intervals per second per sensor (jitter), `GAP`: any interval > 100 ms.
* `CYCLE`: one line per movement, with duration, amplitude, peak and mean velocity, direction (check that it alternates PRONATION/SUPINATION) and rejection.
* `RESULT`: cycles vs manual count, `hysteresisDeg`, `noiseDegS`, `axisShare`, `forearmAxis`, `pauses`, `quality` and `issues`, `score`, `components`, `trend`, `band`.

## 17. Thresholds that may require calibration

Collect the logs and manual counts above on several devices before changing anything, and bump the
versions on any change.

| Parameter (config class) | Value | Why it may need calibration |
|---|---|---|
| `minAmplitudeDeg` (Detection) | 10° | smallest real movements of impaired users |
| `relativeHysteresis` | 0.3 | merges or splits of irregular movements |
| `noiseHysteresisFactor`, `noiseIntegrationS` | 3, 0.25 s | real baseline noise and tremor |
| `maxCycleFrequencyHz` | 6 Hz | fastest real movers |
| `maxCycleDurationS` | 5 s | very slow movers vs pauses |
| `lowPassHz` | 10 Hz | sharp turning points vs noise |
| pause rules | 15 % of peak, ≥ 400 ms | false pauses at turning points of slow movements |
| `minAxisSharePercent` (Quality) | 60 % | real grips (fist, loose hold) |
| `maxNoiseLevelDegS` | 40 °/s | real noise levels |
| `minSamplingRateHz` / `goodSamplingRateHz` | 25 / 50 Hz | real device rates |
| `forearmAxisMinCosine` | 0.7 | how often the grip matches the standard |
| Speed range (`InternalSoftwareReference`) | 0.3–2.5 cycles/s | spread of real scores |
| Amplitude range | 10–90° | spread of real scores |
| Weights / CV limits (`PronationScoringConfig`) | 25/25/20/15/15; CV 40/50/50 % | need validation data before any interpretation |
| Trend thresholds | ±10 points, tolerance 5, maximum 40 | natural variation over 10 s |
