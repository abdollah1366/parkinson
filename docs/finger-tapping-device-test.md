# Finger Tapping – physical-device test protocol (ft-algo-1.1.0 / scoring 1.0)

This protocol checks the corrected data pipeline on real phones and collects the numbers needed
to calibrate the engineering thresholds. Use healthy volunteers and record no names or other
personal data.

## Setup

1. Install a **debug** build (`.\gradlew.bat :app:assembleDebug`). The debug panels and the `FTDiag` log exist only in debug builds.
2. Run `adb logcat -s FTDiag` (adb: `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`).
3. Put the phone on a stand at a fixed distance (about 30–50 cm) with the hand fully inside the frame.
4. During each run, watch the **DEBUG** panel under the camera preview:
   * camera quality and luma;
   * tracking %;
   * results per second;
   * frames received, submitted and results;
   * dropped frames;
   * tap events.
5. After each run, open the result. Expand «نمایش جزئیات فنی» and look at the DEBUG table at the bottom.

## What to record for every run

Record these from the result's DEBUG table and the `RESULT` log line:

| Field | Source |
|---|---|
| Condition (A–K), phone model, hand | you |
| Manual tap count (count from a video of the hand) | you |
| App tap count / tap rate | result |
| Tracking rate %, valid landmark frames, frames analyzed | DEBUG / `RESULT` |
| fps / required fps, camera and pipeline drops | `RESULT` |
| Mean luma, camera quality, tracking quality | DEBUG |
| Quality status and issues | result details |
| Index (score) and band | result |
| Saved? (entry in History opens the same result) | History |

## Conditions

| | Condition | How | Expected |
|---|---|---|---|
| A | Good lighting | daylight or bright room | camera GOOD, tracking ≥ 85 %, VALID, scored, saved |
| B | Normal indoor lighting | ordinary room light | as A; this is the main target condition |
| C | Slightly dark | one dim lamp | luma below 50 means camera WARNING. If tracking stays ≥ 85 %: **still VALID, scored and saved**, with the note «شرایط نور می‌تواند کیفیت تشخیص را کاهش دهد.» and the live text «نور محیط ایده‌آل نیست، اما تشخیص دست در حال انجام است.» |
| D | Bright lighting / backlight | window behind the hand | luma above 225 may give a WARNING; the same rule as C |
| E | Slow tapping | about 1 tap/s | ~10 taps; slow speed component; LOW_FPS only if fps < 20 |
| F | Normal tapping | comfortable | count within ±1 of the manual count |
| G | Fast tapping | as fast as possible | check required fps = 4 × rate is met; count within ±2 |
| H | Small finger movement | small opening | still counted down to about 0.15 palm sizes; low amplitude component |
| I | Large finger movement | full opening | high amplitude component |
| J | Temporary hand loss | pull the hand out for about 1 s mid-test | DROPOUTS_PRESENT / REDUCED_TRACKING (LOW_QUALITY, scored). Over 2 s: «داده کافی برای محاسبه نتیجه وجود ندارد.» |
| K | Hand reappears | as J, then continue | taps before and after the gap are counted; no tap is invented across the gap |

Additional checks:
* **Hand turned edge-on while tapping.** The HAND log shows UNCERTAIN decisions. The landmarks must
  stay in use (tracking stays high) and the result is at most LOW_QUALITY (HAND_SIDE_UNCERTAIN).
* **The other hand held up for the whole test.** WRONG_HAND, «نتیجه قابل اعتماد نیست.».
* **Very dark room.** If tracking falls below 60 %, the result is INSUFFICIENT_DATA with the
  lighting tip. If tracking stays high, it must NOT be rejected.

## Before vs after (what to compare with the old build)

The old build (ft-algo-1.0.2) under conditions B and C typically showed one of these:
* «سرعت پردازش تصویر…» + «نور محیط را بیشتر کنید…» (fps < 12);
* «کیفیت ثبت اطلاعات برای محاسبه نتیجه کافی نبود.» (too few valid frames / dropouts);
* a stored result without a score (LOW_QUALITY).

With ft-algo-1.1.0, compare results per second (numHands 1, RGBA output) and the tracking rate
(edge-on frames are kept) for the same conditions.

## Thresholds that may need calibration

These are engineering values; change them only with the data above, and bump the versions.

| Parameter | Value | Location |
|---|---|---|
| Luma warning / poor | < 50 or > 225 / < 20 | `VisionQualityConfig` |
| Tracking GOOD / minimum | 85 % / 60 % | `VisionQualityConfig` |
| Absolute minimum fps; samples per tap cycle; good fps | 8; 4; 20 | `QualityThresholds` |
| Longest gap allowed | 2 s | `QualityThresholds` |
| Wrong-hand share; uncertain share | 50 %; 20 % | `QualityThresholds` |
| Handedness label limit | 0.6 | `HandLandmarkerManager` |
| MediaPipe confidences | 0.5 / 0.5 / 0.5 | `HandLandmarkerManager` |
| Detection: debounce, minimum duration, amplitude fraction | 100 ms, 60 ms, 0.4 | `DetectionConfig` |
| Score ranges: speed; amplitude | 0.5–5 Hz; 0.15–1.0 palm | `ScoringConfig` |
