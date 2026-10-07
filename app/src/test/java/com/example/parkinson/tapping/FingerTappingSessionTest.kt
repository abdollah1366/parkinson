package com.example.parkinson.tapping

import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.HandSide
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.quality.QualityIssue
import com.example.parkinson.tapping.quality.QualityStatus
import com.example.parkinson.tapping.result.FingerTappingAssessment
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos

/**
 * Session state machine on virtual time (kotlinx-coroutines-test): no real sleeps, deterministic.
 * Frames are synthetic MediaPipe results pushed every 33 ms of virtual time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FingerTappingSessionTest {

    private val saved = mutableListOf<FingerTappingAssessment>()

    private fun TestScope.newSession(onCompleted: suspend (FingerTappingAssessment) -> Unit = { saved += it }) =
        FingerTappingSession(
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            wallClock = { 1_700_000_000_000L },
            newAssessmentId = { "assessment-1" },
            processingDispatcher = StandardTestDispatcher(testScheduler),
            onCompleted = onCompleted
        )

    /** Thumb-index opening of [ratio] palm sizes in a 100 x 100 image. */
    private fun hand(ts: Long, ratio: Double): HandTrackingResult {
        val lm = MutableList(HandLandmarkIndex.COUNT) { HandLandmark(it, 0.5f, 0.5f, 0f) }
        lm[HandLandmarkIndex.WRIST] = HandLandmark(0, 0.5f, 0.9f, 0f)
        lm[HandLandmarkIndex.INDEX_FINGER_MCP] = HandLandmark(5, 0.45f, 0.6f, 0f)
        lm[HandLandmarkIndex.MIDDLE_FINGER_MCP] = HandLandmark(9, 0.5f, 0.6f, 0f)
        lm[HandLandmarkIndex.PINKY_MCP] = HandLandmark(17, 0.55f, 0.6f, 0f)
        // Palm scale for these points is ~25.2 px.
        val half = (ratio * 25.2 / 100.0 / 2.0).toFloat()
        lm[HandLandmarkIndex.THUMB_TIP] = HandLandmark(4, 0.5f - half, 0.4f, 0f)
        lm[HandLandmarkIndex.INDEX_FINGER_TIP] = HandLandmark(8, 0.5f + half, 0.4f, 0f)
        return HandTrackingResult.HandDetected(ts, lm, HandSide.RIGHT, 0.95f, 100, 100)
    }

    private fun tapping3Hz(ts: Long) = hand(ts, 0.08 + 0.9 * (1 - cos(2 * PI * 3.0 * ts / 1000.0)) / 2)

    /** Pushes a result every 33 ms of virtual time until [untilMs]. */
    private fun TestScope.feed(
        session: FingerTappingSession,
        untilMs: Long,
        result: (Long) -> HandTrackingResult = ::tapping3Hz
    ) {
        backgroundScope.launch {
            while (testScheduler.currentTime <= untilMs) {
                session.onTrackingResult(result(testScheduler.currentTime))
                delay(33)
            }
        }
    }

    @Test
    fun completedRecordingIsAnalyzedSavedAndDone() = runTest {
        val session = newSession()
        feed(session, untilMs = 14_000)
        session.start(SelectedHand.LEFT)
        runCurrent()
        assertTrue(session.state.value is SessionState.Countdown)

        advanceTimeBy(3_100)
        assertTrue(session.state.value is SessionState.Recording)

        advanceTimeBy(11_000)
        runCurrent()
        val state = session.state.value
        assertTrue("expected Done, was $state", state is SessionState.Done)
        val a = (state as SessionState.Done).assessment
        assertTrue("taps ${a.tapCount}", abs(a.tapCount - 30) <= 1)
        assertEquals(QualityStatus.VALID, a.qualityStatus)
        assertEquals("assessment-1", a.assessmentId)
        assertEquals(1_700_000_000_000L, a.timestampEpochMs)
        assertEquals(SelectedHand.LEFT, a.hand)
        assertEquals(10_000L, a.recordingDurationMs)
        assertEquals(listOf(a), saved)
    }

    @Test
    fun countdownFramesAreNotRecordedAndLiveCountRuns() = runTest {
        val session = newSession()
        feed(session, untilMs = 14_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(2_900)
        assertTrue(session.state.value is SessionState.Countdown)
        assertEquals(0, session.liveTapCount.value)

        advanceTimeBy(5_100)
        assertTrue(session.state.value is SessionState.Recording)
        assertTrue("live ${session.liveTapCount.value}", session.liveTapCount.value in 10..17)
        session.reset()
    }

    @Test
    fun abortDuringRecordingNeverProducesResult() = runTest {
        val session = newSession()
        feed(session, untilMs = 20_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(6_000)
        assertTrue(session.state.value is SessionState.Recording)

        session.abort()
        val interrupted = SessionState.Invalid(SessionInvalidReason.Interrupted)
        assertEquals(interrupted, session.state.value)

        advanceTimeBy(15_000)
        assertEquals(interrupted, session.state.value)
        assertEquals(0, session.liveTapCount.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun noFramesIsFrameStarvation() = runTest {
        val session = newSession()
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(5_000)
        assertEquals(SessionState.Error(SessionError.FRAME_STARVATION), session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun cameraStallDuringRecordingIsFrameStarvation() = runTest {
        val session = newSession()
        feed(session, untilMs = 6_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(9_000)
        assertEquals(SessionState.Error(SessionError.FRAME_STARVATION), session.state.value)
    }

    @Test
    fun repeatedMediaPipeErrorsAreTrackingFailure() = runTest {
        val session = newSession()
        feed(session, untilMs = 20_000) { HandTrackingResult.Error(it, "model failed") }
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(2_000)
        assertEquals(SessionState.Error(SessionError.TRACKING_FAILURE), session.state.value)
    }

    @Test
    fun noHandIsRejectedByQualityControl() = runTest {
        val session = newSession()
        feed(session, untilMs = 14_000) { HandTrackingResult.NoHandDetected(it) }
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(14_000)
        runCurrent()
        val state = session.state.value
        assertTrue("was $state", state is SessionState.Invalid)
        val reason = (state as SessionState.Invalid).reason as SessionInvalidReason.QualityRejected
        assertEquals(QualityIssue.NO_HAND_DETECTED, reason.report.primaryIssue)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun wrongHandIsRejectedByQualityControl() = runTest {
        val session = newSession()
        feed(session, untilMs = 14_000) { HandTrackingResult.WrongHandDetected(it, HandSide.RIGHT, HandSide.LEFT) }
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(14_000)
        runCurrent()
        val reason = (session.state.value as SessionState.Invalid).reason as SessionInvalidReason.QualityRejected
        assertEquals(QualityIssue.WRONG_HAND, reason.report.primaryIssue)
    }

    @Test
    fun storageFailureIsAnErrorNotAResult() = runTest {
        val session = newSession(onCompleted = { throw IllegalStateException("disk full") })
        feed(session, untilMs = 14_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(14_000)
        runCurrent()
        assertEquals(SessionState.Error(SessionError.STORAGE_FAILURE), session.state.value)
    }

    @Test
    fun cameraFailureDuringRecordingEndsSession() = runTest {
        val session = newSession()
        feed(session, untilMs = 20_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(5_000)
        session.fail(SessionError.CAMERA_FAILURE)
        assertEquals(SessionState.Error(SessionError.CAMERA_FAILURE), session.state.value)
        advanceTimeBy(15_000)
        assertEquals(SessionState.Error(SessionError.CAMERA_FAILURE), session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun startWhileActiveIsIgnoredAndResetReturnsToIdle() = runTest {
        val session = newSession()
        feed(session, untilMs = 20_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(4_000)
        val before = session.state.value
        session.start(SelectedHand.LEFT)
        runCurrent()
        assertTrue(before is SessionState.Recording)
        assertTrue(session.state.value is SessionState.Recording)

        session.reset()
        assertEquals(SessionState.Idle, session.state.value)
        assertEquals(0, session.liveTapCount.value)
        session.abort()
        assertEquals("abort is a no-op when idle", SessionState.Idle, session.state.value)
    }

    @Test
    fun sessionCanBeRepeatedAfterAResult() = runTest {
        val session = newSession()
        feed(session, untilMs = 30_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(14_000)
        runCurrent()
        assertTrue(session.state.value is SessionState.Done)

        session.reset()
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(14_000)
        runCurrent()
        assertTrue(session.state.value is SessionState.Done)
        assertEquals(2, saved.size)
    }
}
