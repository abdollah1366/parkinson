package com.example.parkinson.speech

import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Session flow with a FAKE audio source (SYNTHETIC audio). No microphone is used. */
class SpeechSessionTest {

    /** Scripted audio source: counts calls and returns [behaviour]'s result. */
    private class FakeSource(
        var behaviour: suspend (Long) -> AudioCapture,
    ) : SpeechAudioSource {
        var calls = 0
        override suspend fun capture(durationMs: Long, format: SpeechAudioFormat): AudioCapture {
            calls++
            return behaviour(durationMs)
        }
    }

    private fun vowelCapture(rate: Int = SyntheticSpeech.RATE): AudioCapture =
        SyntheticSpeech.capture(SyntheticSpeech.tone(150.0, 5_000, amp = 0.3, rate = rate), rate = rate)

    private fun TestScope.session(source: SpeechAudioSource, saved: MutableList<SpeechResult>, onSave: (suspend (SpeechResult) -> Unit)? = null) =
        SpeechSession(
            scope = this,
            source = source,
            wallClock = { 1_700_000_000_000L },
            newAssessmentId = { "speech-test" },
            newSessionId = { "visit-test" },
            countdownMs = 1_000L,
            processingDispatcher = StandardTestDispatcher(testScheduler),
            onCompleted = { result -> if (onSave != null) onSave(result) else saved += result },
        )

    @Test
    fun withoutConsentNothingIsRecordedAndTheRunIsAnError() = runTest {
        val source = FakeSource { vowelCapture() }
        val saved = mutableListOf<SpeechResult>()
        val session = session(source, saved)
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = false)
        advanceUntilIdle()
        assertEquals(SpeechState.Error(SpeechError.CONSENT_NOT_GIVEN), session.state.value)
        assertEquals(0, source.calls)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aCompleteRecordingIsAnalysedSavedOnceAndReportsItsActualSampleRate() = runTest {
        // The recorder delivered 8 kHz although 16 kHz was requested: the result must say 8 kHz.
        val source = FakeSource { vowelCapture(rate = 8_000) }
        val saved = mutableListOf<SpeechResult>()
        val session = session(source, saved)
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = true)
        advanceUntilIdle()
        val done = session.state.value as SpeechState.Done
        assertEquals(1, source.calls)
        assertEquals(listOf(done.result), saved)
        assertEquals(8_000, done.result.sampleRateHz)
        assertEquals(16_000, done.result.requestedSampleRateHz)
        assertEquals("visit-test", done.result.sessionId)
        assertTrue(done.result.consentAccepted)
    }

    @Test
    fun aDeniedMicrophonePermissionIsReportedAndNothingIsSaved() = runTest {
        val source = FakeSource { throw SpeechCaptureException(SpeechError.MICROPHONE_PERMISSION_DENIED) }
        val saved = mutableListOf<SpeechResult>()
        val session = session(source, saved)
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = true)
        advanceUntilIdle()
        assertEquals(SpeechState.Error(SpeechError.MICROPHONE_PERMISSION_DENIED), session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aSilentRecordingIsRejectedWithItsQualityReasonAndNotSaved() = runTest {
        val source = FakeSource { AudioCapture(ShortArray(5 * SyntheticSpeech.RATE), SyntheticSpeech.RATE, SyntheticSpeech.RATE) }
        val saved = mutableListOf<SpeechResult>()
        val session = session(source, saved)
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = true)
        advanceUntilIdle()
        val invalid = session.state.value as SpeechState.Invalid
        val reason = invalid.reason as SpeechFailure.Quality
        assertTrue(reason.report.issues.contains(SpeechQualityIssue.SILENCE))
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aFailedSaveIsAStorageErrorNotASavedResult() = runTest {
        val source = FakeSource { vowelCapture() }
        val session = session(source, mutableListOf()) { throw IllegalStateException("disk full") }
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = true)
        advanceUntilIdle()
        assertEquals(SpeechState.Error(SpeechError.STORAGE_FAILURE), session.state.value)
    }

    @Test
    fun cancellingDuringTheRecordingLeavesNoResult() = runTest {
        val source = FakeSource {
            delay(60_000L)
            vowelCapture()
        }
        val saved = mutableListOf<SpeechResult>()
        val session = session(source, saved)
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = true)
        advanceTimeBy(2_000L)
        assertTrue(session.state.value is SpeechState.Recording)
        session.cancel()
        advanceUntilIdle()
        assertEquals(SpeechState.Idle, session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun abortingDuringTheRecordingIsInterruptedNotAResult() = runTest {
        val source = FakeSource {
            delay(60_000L)
            vowelCapture()
        }
        val saved = mutableListOf<SpeechResult>()
        val session = session(source, saved)
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = true)
        advanceTimeBy(2_000L)
        session.abort()
        advanceUntilIdle()
        assertEquals(SpeechState.Invalid(SpeechFailure.Interrupted), session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun aSecondStartWhileARunIsActiveDoesNotStartAnotherRecording() = runTest {
        val source = FakeSource {
            delay(60_000L)
            vowelCapture()
        }
        val session = session(source, mutableListOf())
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = true)
        // Past the 1 s countdown, so the first recording has started.
        advanceTimeBy(1_500L)
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = true)
        advanceTimeBy(500L)
        assertEquals(1, source.calls)
        session.cancel()
        advanceUntilIdle()
    }

    @Test
    fun anUnexpectedFailureIsReportedAsUnexpectedError() = runTest {
        val source = FakeSource { throw IllegalArgumentException("bug") }
        val session = session(source, mutableListOf())
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = true)
        advanceUntilIdle()
        assertEquals(SpeechState.Error(SpeechError.UNEXPECTED), session.state.value)
    }

    @Test
    fun countdownIsShownBeforeTheRecordingStarts() = runTest {
        val source = FakeSource { vowelCapture() }
        val session = session(source, mutableListOf())
        session.start(SpeechTask.SUSTAINED_VOWEL, consentAccepted = true)
        advanceTimeBy(100L)
        assertTrue("state=${session.state.value}", session.state.value is SpeechState.Countdown)
        assertEquals(0, source.calls)
        advanceUntilIdle()
    }
}
