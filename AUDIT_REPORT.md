# Audit Report — Parkinson assessment app

Branch: `fix/sit-to-stand-completion` (worktree `parkinson-sts`), based on `feature/gait-pose-and-sensors`.
Commits: `ee2e381` (Sit-to-Stand fix, pushed, PR #3 targets the gait branch). Gait fix below is uncommitted.

## Phase 0 — Baseline and inventory

- Application module: `:app`. `applicationId com.example.parkinson`, `minSdk 24`, `targetSdk 37`, Kotlin `2.2.10`.
- Manifest permissions: `CAMERA`, `RECORD_AUDIO`. `CAMERA` is still needed by Finger Tapping and Hand Opening/Closing (MediaPipe). Whether it is needed by the sensor-based tests is re-checked in Phase 4.
- Catalog (`assessment/AssessmentCatalog.kt`) on this branch:
  - AVAILABLE: Finger Tapping, Hand Stability, Pronation/Supination, Hand Opening/Closing, Resting Tremor, Gait, Sit-to-Stand, Speech.
  - IN_DEVELOPMENT: Rapid Alternating Movements, Dual-Task / Cognitive-Motor (placeholder, no route, no engine).
- Unit tests: 54 suites, 569 tests before this audit pass (0 failures). Per-suite counts were read from `app/build/test-results/testDebugUnitTest`.
- Historical count "429" from the earlier report is out of date. The current baseline is 569.
- Documentation exists under `docs/` for each algorithm (`sit-to-stand-algorithm.md`, `gait-assessment-algorithm.md`, `speech-assessment-algorithm.md`, `imu-assessments-algorithm.md`, and others).
- Build baseline: `:app:testDebugUnitTest` passes at 569 tests. `local.properties` was copied into the worktree (git-ignored, not committed).

## Defects found and fixed in this pass

| ID | Area | Defect | Evidence | Fix | Status |
|----|------|--------|----------|-----|--------|
| D1 | Sit-to-Stand screen | Display not kept on during the run. Phone in a pocket → screen timeout → `ON_STOP` → `session.abort()` → `Invalid(Interrupted)`, navigates to the invalid screen. | Code reading: `ImuSitToStandScreen` lacked `keepScreenOn`; the pronation and stability screens set it. | `keepScreenOn` while the run is active | FIXED (committed `ee2e381`) — NOT VERIFIED ON DEVICE |
| D2 | Sit-to-Stand session | Result written after an abortable state; a stop during the write cancelled the coroutine after the record was saved, so the UI showed "interrupted" for a saved result. | Code reading of `finish()`. | `Saving` state; `abort()` refused during write; write in `NonCancellable`; failure → `STORAGE_FAILURE` | FIXED (committed) |
| D3 | Sit-to-Stand screen | `LaunchedEffect(state)` re-navigated whenever the screen was recreated while the state was Done/Invalid/Error. | Code reading. | `claimOutcome()` — one navigation per finished run | FIXED (committed) |
| D4 | Gait session + screen | Same as D1, D2, D3 in the walking test. | Code reading of `ImuGaitSession.finish()`, `ImuGaitScreen`. | Same design as STS: `Saving`, `NonCancellable` write, `claimOutcome()`, `keepScreenOn` | FIXED (uncommitted) |

No crash was reproduced. D1 is the leading explanation for "navigates away at the end"; it has not been confirmed in logcat on a device.

Checked and not a reachable crash: `ImuGrid.kt:171 gravity!!` (set before use), `SensorCalibration.kt:65-66 normalized()!!` (valid indices guarantee non-zero vectors; a zero mean is possible only with opposing gravity directions, and it is caught by the surrounding `try`).

## Phase status summary

- Phase 0: DONE (this document, checklist, test results).
- Phase 1 (navigation audit): PARTIAL. Sit-to-Stand and Gait routes traced. Other assessments not traced end to end in this pass.
- Phase 2 (sensor infrastructure): PARTIAL. `MotionSensorRepository` and `MotionSensors.kt` reviewed; listener start/stop is idempotent; no integration test on a device. Not yet reviewed: gravity compensation and filter parameters for each test.
- Phase 3 (Sit-to-Stand): fix applied (D1–D3), regression tests added. Device reproduction pending.
- Phase 4 (Gait): lifecycle fix applied (D4). Step-detector review, false-positive analysis and phone-placement documentation NOT STARTED.
- Phase 5 (Speech): NOT STARTED in this pass. Four subtests exist (`speech/SpeechTask.kt`: sustained vowel, repeated syllable, reading, spontaneous).
- Phase 6 (Cognitive-motor): NOT IMPLEMENTED. `DUAL_TASK` is a placeholder (`IN_DEVELOPMENT`, no route). Blocked on a validated cognitive-task design and on speech-response decisions.
- Phase 7 (Audit of every other assessment): NOT STARTED.
- Phase 8 (Clinical/mathematical criteria): NOT STARTED. Metric specifications not written.
- Phase 9 (Unified scoring): NOT STARTED. No overall scoring engine exists. Per the spec, a numeric overall score should not be produced without a validated model.
- Phase 10 (Database): NOT STARTED. No migration changes were made in this pass.
- Phase 11 (UI/accessibility): NOT STARTED beyond the STS/gait screens.
- Phase 12 (Test quality): NOT STARTED. Test count is 569 (baseline), not 429.
- Phase 13 (Final acceptance): see `TEST_RESULTS.md`.

## Remaining blockers

- No physical device is connected. No `connectedDebugAndroidTest` and no on-device sensor run.
- Clinical validation of any metric is absent. Nothing in this app is clinically validated.
- Dual-task and overall scoring require design decisions on protocol, safety and scoring policy that are not in the repository.
