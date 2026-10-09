package com.example.parkinson.openclose

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.model.SelectedHand
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session state machine on virtual time (kotlinx-coroutines-test). Frames are SYNTHETIC MediaPipe
 * results (see [SyntheticHandResult]) pushed every 33 ms of virtual time; no real sleeps.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HandOpenCloseSessionTest {

    private val saved = mutableListOf<HandOpenCloseResult>()

    private fun TestScope.newSession(onCompleted: suspend (HandOpenCloseResult) -> Unit = { saved += it }) =
        HandOpenCloseSession(
            scope = backgroundScope,
            clock = { testScheduler.currentTime },
            wallClock = { 1_700_000_000_000L },
            newAssessmentId = { "assessment-1" },
            processingDispatcher = StandardTestDispatcher(testScheduler),
            onCompleted = onCompleted
        )

    private fun openingResult(ts: Long): HandTrackingResult = SyntheticHandResult.hand(ts, SyntheticOpening.opening(ts))

    /** Pushes a result every 33 ms of virtual time until [untilMs]. */
    private fun TestScope.feed(
        session: HandOpenCloseSession,
        untilMs: Long,
        result: (Long) -> HandTrackingResult = ::openingResult
    ) {
        backgroundScope.launch {
            while (testScheduler.currentTime <= untilMs) {
                session.onTrackingResult(result(testScheduler.currentTime))
                delay(33)
            }
        }
    }

    @Test
    fun completedRecordingIsFinalizedAndSaved() = runTest {
        val session = newSession()
        feed(session, untilMs = 14_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(20_000)
        runCurrent()

        val done = session.state.value as? OpenCloseState.Done
        assertTrue("expected DONE, was ${session.state.value}", done != null)
        val result = done!!.result
        assertEquals(listOf(result), saved)
        assertEquals("assessment-1", result.assessmentId)
        assertEquals(QualityStatus.VALID, result.qualityStatus)
        // Recording 3 s..13 s: the first opening starts the measurement, then 9 completed cycles.
        assertEquals(9, result.metrics.completedCycles)
        assertEquals(10_000L, result.metrics.measurementDurationMs)
        assertEquals(HandOpenCloseVersions.ALGORITHM_VERSION, result.algorithmVersion)
        assertEquals(null, result.performanceIndex)
        // The timestamped series keeps one entry per frame, so the result can be recalculated later.
        assertEquals(result.series.sampleTimesMs.size, result.series.sampleStatuses.length)
        assertEquals(result.series.sampleTimesMs.size, result.series.sampleOpenings.size)
        // Only frames of the measurement window are kept: countdown frames are excluded, and the late-frame grace (200 ms) is the only margin.
        assertTrue(result.series.sampleTimesMs.all { it in 0L..10_200L })
    }

    @Test
    fun liveCycleCountRunsDuringRecording() = runTest {
        val session = newSession()
        feed(session, untilMs = 14_000)
        session.start(SelectedHand.RIGHT)
        // Countdown 3 s, then recording: by 8 s about 5 cycles have completed.
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue("live count was ${session.liveCycles.value}", session.liveCycles.value >= 3)
        assertTrue(session.state.value is OpenCloseState.Recording)
    }

    @Test
    fun abortDuringRecordingNeverProducesResult() = runTest {
        val session = newSession()
        feed(session, untilMs = 14_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(6_000)
        runCurrent()
        session.abort()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(OpenCloseState.Invalid(OpenCloseInvalidReason.Interrupted), session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun resetReturnsToIdleAndDiscardsTheRun() = runTest {
        val session = newSession()
        feed(session, untilMs = 14_000)
        session.start(SelectedHand.LEFT)
        advanceTimeBy(4_000)
        runCurrent()
        session.reset()
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(OpenCloseState.Idle, session.state.value)
        assertTrue(saved.isEmpty())
        assertEquals(0, session.liveCycles.value)
    }

    @Test
    fun noHandIsRejectedByQualityControl() = runTest {
        val session = newSession()
        feed(session, untilMs = 14_000) { ts -> HandTrackingResult.NoHandDetected(ts) }
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(20_000)
        runCurrent()
        val invalid = session.state.value as? OpenCloseState.Invalid
        assertTrue("expected INVALID, was ${session.state.value}", invalid != null)
        val reason = invalid!!.reason as OpenCloseInvalidReason.QualityRejected
        assertEquals(OpenCloseQualityIssue.NO_HAND_DETECTED, reason.report.primaryIssue)
        assertFalse(reason.report.isUsable)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun storageFailureIsAnErrorNotAResult() = runTest {
        val session = newSession { throw IllegalStateException("disk full") }
        feed(session, untilMs = 14_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(OpenCloseState.Error(OpenCloseError.STORAGE_FAILURE), session.state.value)
    }

    @Test
    fun cameraFailureEndsTheRunWithAnError() = runTest {
        val session = newSession()
        feed(session, untilMs = 14_000)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(5_000)
        runCurrent()
        session.fail(OpenCloseError.CAMERA_FAILURE)
        advanceTimeBy(20_000)
        runCurrent()
        assertEquals(OpenCloseState.Error(OpenCloseError.CAMERA_FAILURE), session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun noResultsAtAllIsFrameStarvation() = runTest {
        val session = newSession()
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(OpenCloseState.Error(OpenCloseError.FRAME_STARVATION), session.state.value)
    }
}
