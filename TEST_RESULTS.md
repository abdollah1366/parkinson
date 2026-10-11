# Test results

All commands run from the worktree root `C:\Users\Administrator\AndroidStudioProjects\parkinson-sts` on branch `fix/sit-to-stand-completion`.

## Run 1 — baseline (before this audit pass), commit `ee2e381`
Command: `gradlew.bat :app:testDebugUnitTest --console=plain` (after copying `local.properties`)
- Result: BUILD SUCCESSFUL. 569 tests, 0 skipped, 0 failures, 0 errors (JVM, Robolectric).

## Run 2 — after Sit-to-Stand fix (`ee2e381`)
Command: `gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain`
- Result: BUILD SUCCESSFUL. 574 tests, 0 failures.
- Note: `ImuSessionTest` alone: 14 tests, all pass, including the 5 new regression tests.

## Run 3 — after gait lifecycle parity (uncommitted, D4)
Command: removed `app/build/test-results/testDebugUnitTest` first, then
`gradlew.bat :app:testDebugUnitTest :app:assembleDebug :app:compileDebugAndroidTestKotlin --console=plain`
- First attempt FAILED to compile (`Unresolved reference 'Saving'` in `ImuGaitSession.kt` and `ImuAssessmentScreens.kt`). Cause: the `Saving` state declaration was not applied because the first batch of edits errored on a missing read. Fixed by adding `data object Saving : ImuGaitState`. This failed run is recorded here; its XML was not used.
- Result: BUILD SUCCESSFUL in 45 s. 575 tests, 0 skipped, 0 failures, 0 errors.
- `anAbortDuringTheGaitWriteIsRefusedAndTheWalkIsSavedOnceAsDone` present in the report and passing.
- `:app:assembleDebug` produced `app/build/outputs/apk/debug/app-debug.apk`.
- `:app:compileDebugAndroidTestKotlin` completed within the same successful build.

## Not run
- `:app:connectedDebugAndroidTest`: BLOCKED. No authorized Android device is connected. The emulator is not usable on this 8 GB machine.
- Lint (`:app:lintDebug`): not run in this pass.

## What the JVM results do and do not show
- They show the state machine ordering, abort refusal during the write, failed-write handling, once-only navigation claim, sensor release after completion, and restart behavior, against a fake sensor source and synthetic signals.
- They do not show that the Android screen stays on, that the phone's sensors deliver data at the expected rate, that the original crash is gone, or that any clinical measurement is valid.
