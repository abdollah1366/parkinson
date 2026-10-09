package com.example.parkinson.speech

import com.example.parkinson.assessment.QualityStatus
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Task-level analysis on SYNTHETIC audio. Algorithm checks only; not clinical evidence. */
class SpeechTaskEngineTest {

    private val sr = SyntheticSpeech.RATE

    private fun vowel(ms: Int = 5_000, hz: Double = 150.0) = SyntheticSpeech.capture(SyntheticSpeech.tone(hz, ms, amp = 0.3))

    @Test
    fun sustainedVowelReportsF0IntensityAndHnrButNotCycleMeasures() {
        val analysis = SpeechTaskEngine().analyze(SpeechTask.SUSTAINED_VOWEL, vowel())
        assertEquals(QualityStatus.VALID, analysis.quality.status)
        assertEquals(150.0, analysis.measured(SpeechMetric.F0_MEDIAN_HZ)!!, 2.0)
        assertTrue(analysis.measured(SpeechMetric.F0_SD_SEMITONES)!! < 0.5)
        assertNotNull(analysis.measured(SpeechMetric.INTENSITY_MEDIAN_DBFS))
        assertTrue(analysis.measured(SpeechMetric.HNR_DB)!! > 20.0)
        assertTrue(analysis.measured(SpeechMetric.VOICED_PERCENT)!! > 90.0)
        // Jitter and shimmer are never invented: they carry the reason, not a value.
        assertEquals(SpeechValue.Unavailable(UnavailableReason.CYCLE_MEASUREMENT_NOT_RELIABLE), analysis.metrics[SpeechMetric.JITTER_PERCENT])
        assertEquals(SpeechValue.Unavailable(UnavailableReason.CYCLE_MEASUREMENT_NOT_RELIABLE), analysis.metrics[SpeechMetric.SHIMMER_PERCENT])
        // A sustained vowel has no speech span, pauses or events.
        assertNull(analysis.pauses)
        assertEquals(SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK), analysis.metrics[SpeechMetric.PAUSE_COUNT])
        assertEquals(SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK), analysis.metrics[SpeechMetric.EVENT_COUNT])
    }

    @Test
    fun aVeryShortVoicedSegmentGivesNoPitchStatisticsRatherThanAZero() {
        // 1 s of phonation is below the 2 s minimum for pitch and intensity statistics.
        val analysis = SpeechTaskEngine().analyze(SpeechTask.SUSTAINED_VOWEL, vowel(ms = 1_000), plannedDurationMs = 1_000L)
        assertEquals(SpeechValue.Unavailable(UnavailableReason.NOT_ENOUGH_VOICED_FRAMES), analysis.metrics[SpeechMetric.F0_MEDIAN_HZ])
        assertEquals(SpeechValue.Unavailable(UnavailableReason.NOT_ENOUGH_VOICED_FRAMES), analysis.metrics[SpeechMetric.HNR_DB])
    }

    @Test
    fun readingReportsPausesButNeverASpeakingRateWithoutTranscription() {
        val x = SyntheticSpeech.concat(
            SyntheticSpeech.tone(180.0, 2_000, amp = 0.3),
            SyntheticSpeech.silence(700),
            SyntheticSpeech.tone(180.0, 2_000, amp = 0.3),
        )
        // 4.7 s captured against a 5 s plan: above the 80 % integrity share.
        val analysis = SpeechTaskEngine().analyze(SpeechTask.READING, SyntheticSpeech.capture(x), plannedDurationMs = 5_000L)
        assertEquals(QualityStatus.VALID, analysis.quality.status)
        assertEquals(1.0, analysis.measured(SpeechMetric.PAUSE_COUNT)!!, 1e-9)
        assertEquals(700.0, analysis.measured(SpeechMetric.PAUSE_MEAN_MS)!!, 40.0)
        assertEquals(SpeechValue.Unavailable(UnavailableReason.NO_TRANSCRIPTION_SUPPORT), analysis.metrics[SpeechMetric.SPEAKING_RATE_WPM])
        assertEquals(SpeechValue.Unavailable(UnavailableReason.NO_TRANSCRIPTION_SUPPORT), analysis.metrics[SpeechMetric.ARTICULATION_RATE])
        assertTrue(analysis.measured(SpeechMetric.F0_SD_SEMITONES) != null)
    }

    @Test
    fun repeatedSyllableReportsAcousticEventRateAndVariability() {
        val x = SyntheticSpeech.bursts(count = 20, burstMs = 150, periodMs = 400)
        val analysis = SpeechTaskEngine().analyze(SpeechTask.REPEATED_SYLLABLE, SyntheticSpeech.capture(x), plannedDurationMs = 8_000L)
        assertEquals(2.5, analysis.measured(SpeechMetric.EVENT_RATE_HZ)!!, 0.15)
        assertTrue(analysis.measured(SpeechMetric.EVENT_COUNT)!! >= 18.0)
        assertTrue(analysis.measured(SpeechMetric.EVENT_INTERVAL_CV_PERCENT)!! < 10.0)
        // Pitch statistics do not apply to the syllable task.
        assertEquals(SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK), analysis.metrics[SpeechMetric.F0_MEDIAN_HZ])
    }

    @Test
    fun silenceIsUnusableAndReportsNoMeasuredValueAtAll() {
        val analysis = SpeechTaskEngine().analyze(SpeechTask.SUSTAINED_VOWEL, SyntheticSpeech.capture(SyntheticSpeech.silence(5_000)))
        assertFalse(analysis.quality.isUsable)
        assertTrue(analysis.metrics.values.none { it is SpeechValue.Measured })
        assertNull(analysis.pauses)
    }

    @Test
    fun anEmptyCaptureIsInvalidWithoutAnyValue() {
        val analysis = SpeechTaskEngine().analyze(SpeechTask.SUSTAINED_VOWEL, AudioCapture(ShortArray(0), sr, sr))
        assertEquals(QualityStatus.INVALID, analysis.quality.status)
        assertTrue(analysis.metrics.values.none { it is SpeechValue.Measured })
        assertEquals(SpeechMetric.entries.size, analysis.metrics.size)
    }

    @Test
    fun everyMetricHasAnEntryForEveryTask() {
        for (task in SpeechTask.entries) {
            val analysis = SpeechTaskEngine().analyze(task, vowel(ms = 3_000))
            assertEquals(task.name, SpeechMetric.entries.size, analysis.metrics.size)
        }
    }

    @Test
    fun aCancelledAnalysisStopsBetweenStagesAndPropagatesTheCancellation() {
        var calls = 0
        val engine = SpeechTaskEngine(checkCancelled = {
            calls++
            if (calls >= 2) throw CancellationException("cancelled")
        })
        try {
            engine.analyze(SpeechTask.SUSTAINED_VOWEL, vowel())
            fail("expected cancellation")
        } catch (e: CancellationException) {
            assertEquals(2, calls)
        }
    }

    @Test
    fun aRecordingAtTheEightKilohertzFallbackRateIsAnalysedAtThatRate() {
        val capture = SyntheticSpeech.capture(SyntheticSpeech.tone(150.0, 5_000, amp = 0.3, rate = 8_000), rate = 8_000, requested = 16_000)
        val analysis = SpeechTaskEngine().analyze(SpeechTask.SUSTAINED_VOWEL, capture, plannedDurationMs = 5_000L)
        assertEquals(8_000, analysis.quality.sampleRateHz)
        assertEquals(QualityStatus.VALID, analysis.quality.status)
        assertEquals(150.0, analysis.measured(SpeechMetric.F0_MEDIAN_HZ)!!, 2.0)
    }

    @Test
    fun aTruncatedCaptureIsInsufficientAndNotAnalysed() {
        val capture = SyntheticSpeech.capture(SyntheticSpeech.tone(150.0, 2_000, amp = 0.3))
        val analysis = SpeechTaskEngine().analyze(SpeechTask.SUSTAINED_VOWEL, capture, plannedDurationMs = 5_000L)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertTrue(analysis.metrics.values.none { it is SpeechValue.Measured })
    }
}
