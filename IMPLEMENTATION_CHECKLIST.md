# Implementation checklist

Statuses: NOT_STARTED, IN_PROGRESS, PASS, FAIL, BLOCKED, NOT_APPLICABLE.
"PASS" here means JVM-verified only, unless a device column says otherwise.

| ID | Requirement | Status | Files | Defect / missing behavior | Correction | Verification | Result | Limitation |
|----|-------------|--------|-------|---------------------------|-----------|--------------|--------|------------|
| P0-1 | Baseline build and unit tests | PASS | — | none | — | `:app:testDebugUnitTest` (before changes) | 569 tests, 0 failures | — |
| P1-1 | Sit-to-Stand navigation to result after persistence | PASS | `imu/ImuSitToStandSession.kt`, `ui/screens/imu/ImuAssessmentScreens.kt` | D2, D3 | `Saving` state; `claimOutcome()` | `ImuSessionTest` (14 tests) | PASS | JVM only |
| P1-2 | Screen stays on during a run | NOT_VERIFIED (code only) | `ui/screens/imu/ImuAssessmentScreens.kt` | D1 | `keepScreenOn` | code review | NOT_VERIFIED on device | UI behavior not unit-tested |
| P1-3 | Gait lifecycle parity with STS | PASS | `imu/ImuGaitSession.kt`, `ui/screens/imu/ImuAssessmentScreens.kt` | D4 | Same as P1-1, P1-2 | `ImuSessionTest.anAbortDuringTheGaitWriteIsRefusedAndTheWalkIsSavedOnceAsDone` | PASS (JVM) | — |
| P1-4 | Full navigation audit of all assessments | NOT_STARTED | — | — | — | — | — | — |
| P2-1 | Sensor listener release on completion, abort, reset | PASS (JVM) | `sensors/MotionSensorRepository.kt` | none found | — | `ImuSessionTest` asserts `!source.running` after Done/Invalid | PASS (JVM) | Fake source only |
| P2-2 | Gravity, filter and sampling review per test | NOT_STARTED | `imu/*` | — | — | — | — | — |
| P3-1 | Reproduce the original Sit-to-Stand crash | BLOCKED | — | — | — | needs physical phone + logcat | BLOCKED | No device connected |
| P3-2 | Detector scientific assumptions reviewed | NOT_STARTED | `imu/SitToStandImuDetector.kt` | — | — | — | — | — |
| P4-1 | Gait step detection false-positive review | NOT_STARTED | `imu/GaitStepDetector.kt` | — | — | — | — | — |
| P4-2 | Gait: camera dependency removed from primary measurement | NOT_STARTED | `AndroidManifest.xml`, catalog | manifest still requests CAMERA | — | — | — | — |
| P5-1 | Speech: four subtests preserved and reachable | NOT_STARTED | `speech/*` | — | — | — | — | — |
| P5-2 | Speech parent overview workflow | NOT_STARTED | — | — | — | — | — | — |
| P5-3 | Speech scoring engine (versioned, labeled experimental) | NOT_STARTED | — | — | — | — | — | — |
| P6-1 | Cognitive-motor dual-task assessment | BLOCKED | — | placeholder (`DUAL_TASK`, `IN_DEVELOPMENT`) | — | — | BLOCKED | Protocol, cognitive task and response input not decided |
| P7-1 | Per-assessment audit (9 tests) | NOT_STARTED | — | — | — | — | — | — |
| P8-1 | Metric specifications and formulas | NOT_STARTED | — | — | — | — | — | — |
| P9-1 | Overall scoring engine | BLOCKED | — | none exists | — | — | BLOCKED | No validated model; numeric overall score must not be invented |
| P10-1 | Migrations and data integrity | NOT_STARTED | `data/*` | — | — | — | — | — |
| P11-1 | RTL/accessibility/UI review | NOT_STARTED | — | — | — | — | — | — |
| P12-1 | Test quality review | NOT_STARTED | — | — | — | — | — | — |
| P13-1 | assembleDebug | PASS | — | — | — | — | — | — |
| P13-2 | testDebugUnitTest | PASS (575 tests) | — | — | — | — | — | — |
| P13-3 | compileDebugAndroidTestKotlin | PASS | — | — | — | — | — | — |
| P13-4 | connectedDebugAndroidTest | BLOCKED | — | no authorized device connected | — | — | BLOCKED | — |
