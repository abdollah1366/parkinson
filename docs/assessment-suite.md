# Motor assessment suite

Single source of truth: `assessment/AssessmentCatalog.kt`. Every planned test is listed, so the
patient sees the complete roadmap. Only `AVAILABLE` tests have a route and can start. All other
tests show their status and **never produce a measurement**.

| # | ID | Title (fa) | Sensors | Duration | Status |
|---|---|---|---|---|---|
| 1 | `finger_tapping` | ضربه زدن با انگشتان | Camera, MediaPipe hand model | 10 s | **Available** |
| 2 | `hand_stability` | نگه‌داشتن دست ثابت | Accelerometer, gyroscope | 5 s + 15 s | **Available** |
| 3 | `pronation_supination` | آزمون چرخش دست | Gyroscope (required), accelerometer (optional) | 5 s + 3-2-1 + 10 s | **Available** |
| 4 | `hand_open_close` | آزمون باز و بسته کردن دست | Camera, MediaPipe hand model | 3 s + 10 s | **Available** (see `hand-opening-closing-algorithm.md`) |
| 5 | `resting_hand_tremor` | لرزش دست در حالت استراحت | Camera, hand model | 3 s + 15 s | **Available** (see `resting-hand-tremor-algorithm.md`) |
| 6 | `gait` | راه رفتن | Accelerometer, gyroscope | not defined | در حال توسعه (no safe protocol yet) |
| 7 | `sit_to_stand` | بلند شدن از صندلی | Accelerometer, gyroscope | not defined | در حال توسعه (no safe, validated protocol) |
| 8 | `speech` | ارزیابی گفتار | Microphone | not defined | در حال توسعه (no recording/privacy handling yet) |
| 9 | `dual_task` | آزمون حرکتی-شناختی | Accelerometer, gyroscope | not defined | در حال توسعه |

## Flow

```
Home ("ارزیابی‌های حرکتی" + "آخرین ارزیابی")
  -> Test selection (AssessmentCatalogScreen; unavailable cards are disabled)
  -> Instructions (FingerTappingIntro / HandStabilityIntro / PronationSupinationIntro)
  -> Hand selection (Pronation/Supination: on its instruction screen)
  -> Sensor/camera check (SensorCheckScreen; missing sensor = Persian message, cannot continue)
  -> [Finger Tapping: Preparation -> Ready -> camera permission]
  -> [Pronation/Supination: Preparing 5 s (baseline) -> Countdown 3-2-1 -> «شروع»]
  -> Countdown -> Recording -> Processing -> Quality check
  -> Result (Retry / Next test / Home)  or  Invalid explanation (Retry / Home)
  -> History (all types, newest first)
```

## Shared model (`assessment/`)

* `AssessmentType`: stable string IDs.
* `AssessmentStatus`: `AVAILABLE`, `IN_DEVELOPMENT`, `RESEARCH`.
* `SensorRequirement`: camera, hand model, accelerometer, gyroscope, microphone.
* `DeviceCapabilities` + `SensorCheckResult`: the sensor check.
* `QualityStatus` + `AssessmentQuality`: shared quality vocabulary.
* `AssessmentResult`: the summary every stored result exposes to Home and History.
  * Contains the type, time, hand, quality and versioned `performanceIndex`.
  * Also contains `algorithmVersion` and `scoringVersion`.

Each implemented test keeps its own engine and detailed result type:

* Finger Tapping: `tapping/` (see `finger-tapping-algorithm.md`).
* Hand Stability: `stability/` (see `hand-stability-algorithm.md`).
* Pronation/Supination: `pronation/` (see `pronation-supination-algorithm.md` and the device protocol `pronation-supination-test.md`).

Cleaning and sampling statistics for the IMU tests are shared in `sensors/MotionStreams.kt`.

Every implemented test produces three things:

* raw metrics;
* quality metrics;
* a versioned, clearly labelled **preliminary** performance index ("شاخص عملکرد"). It is computed
  only for VALID recordings. Exception: Pronation/Supination also scores LOW_QUALITY recordings,
  marked "limited reliability" («نتیجه با اطمینان محدود قابل تفسیر است.»).

## Storage

Room `assessments.db`:

* **v1:** `finger_tapping_assessments`.
* **v2:** adds `hand_stability_assessments` through an auto-migration. Existing rows are kept;
  `HandStabilityStorageTest` checks this against the exported `schemas/…/1.json`.
* **v3:** adds `pronation_supination_assessments` through an auto-migration
  (`PronationSupinationStorageTest` checks v2 → v3).
* **v4:** adds columns to `pronation_supination_assessments` (algorithm 1.0: scores, trend, interpretation,
  velocity trace). Additive auto-migration; v3 rows are kept and read with derived defaults.

`AssessmentRepository.observeHistory()` merges all types, newest first.
