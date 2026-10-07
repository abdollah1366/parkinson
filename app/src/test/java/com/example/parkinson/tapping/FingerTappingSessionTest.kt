package com.example.parkinson.tapping

import com.example.parkinson.diagnostics.TapDiagnostics
import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandSide
import com.example.parkinson.mediapipe.HandTrackingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FingerTappingSessionTest {

    @Before
    fun disableDiagnostics() {
        // android.util.Log is not available in JVM unit tests.
        TapDiagnostics.enabled = false
    }

    private val startNs = System.nanoTime()
    private fun now(): Long = (System.nanoTime() - startNs) / 1_000_000

    private fun session(scope: CoroutineScope) = FingerTappingSession(
        scope = scope,
        clock = ::now,
        countdownMs = 100L,
        recordingMs = 1_500L,
        lateFrameGraceMs = 20L,
        tickMs = 10L
    )

    /** opening = thumb-index distance / wrist-middleMcp distance (both 40 px here when open). */
    private fun frame(ts: Long, open: Boolean): HandTrackingResult {
        val landmarks = List(21) { HandLandmark(it, 0.5f, 0.5f, 0f) }.toMutableList()
        landmarks[0] = HandLandmark(0, 0.5f, 0.9f, 0f)                      // wrist
        landmarks[4] = HandLandmark(4, if (open) 0.3f else 0.5f, 0.5f, 0f)  // thumb tip
        landmarks[8] = HandLandmark(8, if (open) 0.7f else 0.5f, 0.5f, 0f)  // index tip
        return HandTrackingResult.HandDetected(ts, landmarks, HandSide.RIGHT, 0.9f, 100, 100)
    }

    /** Feeds open/closed cycles (one tap per cycle) until [until] returns true. */
    private suspend fun tapUntil(session: FingerTappingSession, until: (SessionState) -> Boolean) {
        var i = 0
        while (!until(session.state.value)) {
            session.onTrackingResult(frame(now(), open = (i / 3) % 2 == 0))
            i++
            delay(5)
        }
    }

    @Test
    fun completedRecordingProducesResult() = runBlocking {
        val s = session(this)
        s.start()
        yield()
        assertTrue(s.state.value is SessionState.Countdown)

        tapUntil(s) { it is SessionState.Processing || !it.isActive }
        while (s.state.value.isActive) delay(5)

        val state = s.state.value
        assertTrue("expected Done, was $state", state is SessionState.Done)
        assertTrue((state as SessionState.Done).outcome.metrics.tapCount >= 5)
    }

    @Test
    fun framesDuringCountdownAreIgnored() = runBlocking {
        val s = session(this)
        s.start()
        yield()
        tapUntil(s) { it !is SessionState.Countdown }
        assertEquals(0, s.tapCount.value)
        s.reset()
    }

    @Test
    fun abortDuringRecordingNeverProducesResult() = runBlocking {
        val s = session(this)
        s.start()
        yield()
        tapUntil(s) { it is SessionState.Recording && s.tapCount.value >= 3 }

        s.abort()
        assertEquals(SessionState.Invalid(SessionInvalidReason.Interrupted, null), s.state.value)

        // Late frames and the cancelled timer must not change the outcome.
        repeat(10) { s.onTrackingResult(frame(now(), open = it % 2 == 0)) }
        delay(2_000)
        assertEquals(SessionState.Invalid(SessionInvalidReason.Interrupted, null), s.state.value)
        assertEquals(0, s.tapCount.value)
    }

    @Test
    fun noFramesIsInvalid() = runBlocking {
        val s = session(this)
        s.start()
        while (s.state.value == SessionState.Idle || s.state.value.isActive) delay(5)
        val state = s.state.value
        assertTrue("expected Invalid, was $state", state is SessionState.Invalid)
    }
}
