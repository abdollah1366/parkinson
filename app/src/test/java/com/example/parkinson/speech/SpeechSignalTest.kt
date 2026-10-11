package com.example.parkinson.speech

import com.example.parkinson.assessment.QualityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.log10
import kotlin.math.sqrt

/** Deterministic DSP tests on SYNTHETIC signals (see [SyntheticSpeech]). */
class SpeechSignalTest {

    private val sr = SyntheticSpeech.RATE

    @Test
    fun rmsLevelOfASineMatchesItsAmplitude() {
        val x = SyntheticSpeech.tone(200.0, 1000, amp = 0.5)
        val db = SignalFrames.rmsDb(x, 0, x.size)
        // RMS of a sine with peak 0.5 is 0.5 / sqrt(2): 20 log10(0.3536) = -9.03 dB.
        assertEquals(20 * log10(0.5 / sqrt(2.0)), db, 0.05)
    }

    @Test
    fun silenceGivesTheSilenceFloorNotAMeasuredLevel() {
        val x = SyntheticSpeech.silence(500)
        assertEquals(SignalFrames.SILENCE_FLOOR_DB, SignalFrames.rmsDb(x, 0, x.size), 1e-9)
    }

    @Test
    fun clippedSamplesAreCountedAgainstTheFullScale() {
        val pcm = ShortArray(1000) { if (it < 20) 32767 else 1000 }
        assertEquals(2.0, SignalFrames.clippedPercent(pcm, 0.98), 1e-9)
        assertEquals(0.0, SignalFrames.clippedPercent(ShortArray(0), 0.98), 1e-9)
    }

    @Test
    fun negativeFullScaleSamplesAreCountedAsClipping() {
        val pcm = ShortArray(100) { if (it % 10 == 0) (-32768).toShort() else 0 }
        assertEquals(10.0, SignalFrames.clippedPercent(pcm, 0.98), 1e-9)
    }

    @Test
    fun frameCountFollowsTheHopAndTheFrameLength() {
        assertEquals(0, SignalFrames.frameCount(100, 640, 160))
        assertEquals(1, SignalFrames.frameCount(640, 640, 160))
        assertEquals(3, SignalFrames.frameCount(640 + 2 * 160, 640, 160))
    }

    @Test
    fun percentileAndMedianIgnoreNonFiniteValuesAndHandleEmptyInput() {
        assertNull(SignalFrames.median(emptyList()))
        assertNull(SignalFrames.percentile(listOf(Double.NaN, Double.POSITIVE_INFINITY), 10.0))
        assertEquals(2.0, requireNotNull(SignalFrames.percentile(listOf(1.0, Double.NaN, 2.0, 3.0), 50.0)), 1e-9)
        assertNull(SignalFrames.sd(listOf(1.0)))
    }

    @Test
    fun pitchOfASineIsRecoveredAtItsKnownFrequency() {
        val x = SyntheticSpeech.tone(120.0, 2000)
        val track = PitchEstimator().estimate(x, sr)
        val voiced = (0 until track.frameCount).filter { track.voiced[it] }
        assertTrue("voiced=${voiced.size} of ${track.frameCount}", voiced.size > track.frameCount * 0.95)
        val f0 = SignalFrames.median(voiced.map { track.f0Hz[it] })!!
        // 16 kHz lag resolution is one sample; parabolic interpolation keeps the error well below 1 %.
        assertEquals(120.0, f0, 1.2)
    }

    @Test
    fun pitchOfAHigherToneIsRecoveredToo() {
        val x = SyntheticSpeech.tone(220.0, 2000)
        val f0 = SignalFrames.median(
            PitchEstimator().estimate(x, sr).let { t -> (0 until t.frameCount).filter { t.voiced[it] }.map { t.f0Hz[it] } }
        )!!
        assertEquals(220.0, f0, 2.0)
    }

    @Test
    fun aPureToneHasAHighHarmonicToNoiseRatio() {
        val track = PitchEstimator().estimate(SyntheticSpeech.tone(150.0, 2000), sr)
        val hnr = (0 until track.frameCount).filter { track.voiced[it] }.map { track.hnrDb[it] }
        // Boersma: 10 log10(r / (1 - r)); a periodicity near 1 gives a large value (clamped at 0.9999 = 40 dB).
        assertTrue("median HNR ${SignalFrames.median(hnr)}", SignalFrames.median(hnr)!! > 20.0)
    }

    @Test
    fun whiteNoiseIsUnvoiced() {
        val track = PitchEstimator().estimate(SyntheticSpeech.noise(2000, 0.3), sr)
        val voiced = (0 until track.frameCount).count { track.voiced[it] }
        assertTrue("voiced=$voiced of ${track.frameCount}", voiced < track.frameCount * 0.10)
        assertTrue("no F0 for unvoiced frames", (0 until track.frameCount).none { !track.voiced[it] && track.f0Hz[it].isFinite() })
    }

    @Test
    fun tooShortInputGivesAnEmptyPitchTrackNotACrash() {
        val track = PitchEstimator().estimate(SyntheticSpeech.tone(120.0, 10), sr)
        assertEquals(0, track.frameCount)
    }

    @Test
    fun pitchWorksAtTheEightKilohertzFallbackRate() {
        val rate = 8_000
        val f0 = SignalFrames.median(
            PitchEstimator().estimate(SyntheticSpeech.tone(150.0, 2000, rate = rate), rate).let { t ->
                (0 until t.frameCount).filter { t.voiced[it] }.map { t.f0Hz[it] }
            }
        )!!
        assertEquals(150.0, f0, 2.0)
    }

    @Test
    fun activityFollowsTheLevelAboveTheNoiseFloor() {
        val x = SyntheticSpeech.concat(
            SyntheticSpeech.silence(500),
            SyntheticSpeech.tone(200.0, 1000, amp = 0.3),
            SyntheticSpeech.silence(500),
        )
        val levels = SignalFrames.frameLevelsDb(x, sr, 40.0, 10.0)
        val activity = VoiceActivityDetector.detect(levels, 10.0)
        val activeFrames = activity.active.count { it }
        // About 1 s of speech = 100 frames of 10 ms hop (edges smoothed by the majority filter).
        assertTrue("active=$activeFrames", activeFrames in 90..110)
        assertTrue(activity.noiseFloorDb!! < -60.0)
    }

    @Test
    fun pausesAreMeasuredBetweenSpeechNotAtTheEdges() {
        val x = SyntheticSpeech.concat(
            SyntheticSpeech.silence(800),          // leading silence: not a pause
            SyntheticSpeech.tone(200.0, 1000),
            SyntheticSpeech.silence(600),          // a pause of 600 ms
            SyntheticSpeech.tone(200.0, 1000),
            SyntheticSpeech.silence(800),          // trailing silence: not a pause
        )
        val activity = VoiceActivityDetector.detect(SignalFrames.frameLevelsDb(x, sr, 40.0, 10.0), 10.0)
        val pauses = PauseDetector.summarize(activity)
        assertEquals(1, pauses.pauseCount)
        assertEquals(600.0, requireNotNull(pauses.meanPauseMs), 40.0)
        assertEquals(0, pauses.longPauseCount)
        assertEquals(0.0, requireNotNull(pauses.longPauseRatioPercent), 1e-9)
    }

    @Test
    fun aLongPauseIsCountedAndItsRatioUsesTheSpeechSpan() {
        val x = SyntheticSpeech.concat(
            SyntheticSpeech.tone(200.0, 1000),
            SyntheticSpeech.silence(1500),
            SyntheticSpeech.tone(200.0, 1000),
        )
        val activity = VoiceActivityDetector.detect(SignalFrames.frameLevelsDb(x, sr, 40.0, 10.0), 10.0)
        val pauses = PauseDetector.summarize(activity)
        assertEquals(1, pauses.longPauseCount)
        val span = requireNotNull(pauses.speechSpanMs)
        assertEquals(1500.0 / span * 100.0, requireNotNull(pauses.longPauseRatioPercent), 2.0)
    }

    @Test
    fun noSpeechMeansNoSpanAndNoPauseStatistics() {
        val activity = VoiceActivityDetector.detect(SignalFrames.frameLevelsDb(SyntheticSpeech.silence(2000), sr, 40.0, 10.0), 10.0)
        val pauses = PauseDetector.summarize(activity)
        assertNull(pauses.speechSpanMs)
        assertNull(pauses.meanPauseMs)
        assertNull(pauses.longPauseRatioPercent)
        assertEquals(0, pauses.pauseCount)
    }

    @Test
    fun syllableEventsOfARegularBurstTrainGiveItsRate() {
        // 20 bursts of 150 ms every 400 ms = 2.5 per second.
        val x = SyntheticSpeech.bursts(count = 20, burstMs = 150, periodMs = 400)
        val activity = VoiceActivityDetector.detect(SignalFrames.frameLevelsDb(x, sr, 40.0, 10.0), 10.0)
        val events = SyllableEventDetector.detect(activity)
        assertTrue("events=${events.count}", events.count in 18..22)
        assertEquals(2.5, requireNotNull(events.rateHz), 0.15)
        assertTrue(requireNotNull(events.intervalCvPercent) < 10.0)
    }

    @Test
    fun tooFewEventsGiveNoRateAndNoVariability() {
        val x = SyntheticSpeech.bursts(count = 3, burstMs = 150, periodMs = 400)
        val activity = VoiceActivityDetector.detect(SignalFrames.frameLevelsDb(x, sr, 40.0, 10.0), 10.0)
        val events = SyllableEventDetector.detect(activity)
        assertNull(events.rateHz)
        assertNull(events.intervalCvPercent)
    }

    @Test
    fun captureIntegrityRejectsEmptyTruncatedAndUnsupportedCaptures() {
        val planned = 5_000L
        assertEquals(CaptureIntegrityIssue.NO_SAMPLES, CaptureIntegrity.check(AudioCapture(ShortArray(0), sr, sr), planned))
        assertEquals(CaptureIntegrityIssue.INVALID_SAMPLE_RATE, CaptureIntegrity.check(AudioCapture(ShortArray(10), 0, sr), planned))
        assertEquals(
            CaptureIntegrityIssue.SAMPLE_RATE_BELOW_MINIMUM,
            CaptureIntegrity.check(AudioCapture(ShortArray(4000), 4_000, sr), planned),
        )
        // 2 s delivered of 5 s planned: truncated.
        assertEquals(CaptureIntegrityIssue.TRUNCATED, CaptureIntegrity.check(AudioCapture(ShortArray(2 * sr), sr, sr), planned))
        assertNull(CaptureIntegrity.check(AudioCapture(ShortArray(5 * sr), sr, sr), planned))
    }

    @Test
    fun captureDurationFollowsTheActualSampleRate() {
        // The duration is computed from the delivered rate, never from the requested one.
        val capture = AudioCapture(ShortArray(8_000), sampleRateHz = 8_000, requestedSampleRateHz = 16_000)
        assertEquals(1000.0, capture.durationMs, 1e-9)
        assertEquals(16_000, capture.requestedSampleRateHz)
        assertFalse(capture.sampleRateHz == capture.requestedSampleRateHz)
    }

    @Test
    fun qualityStatusOfAFineSignalIsValid() {
        val cap = SyntheticSpeech.capture(SyntheticSpeech.tone(180.0, 5_000, amp = 0.3))
        val activity = VoiceActivityDetector.detect(SignalFrames.frameLevelsDb(SignalFrames.toUnit(cap.samples), sr, 40.0, 10.0), 10.0)
        val report = AudioQualityAnalyzer.analyze(cap, 5_000L, activity)
        assertEquals(QualityStatus.VALID, report.status)
        assertTrue(report.isUsable)
    }

    @Test
    fun clippedRecordingIsInvalidAndNamesClipping() {
        val x = SyntheticSpeech.tone(180.0, 5_000, amp = 1.0)
        val cap = AudioCapture(ShortArray(x.size) { 32767.toShort() }, sr, sr)
        val activity = VoiceActivityDetector.detect(SignalFrames.frameLevelsDb(SignalFrames.toUnit(cap.samples), sr, 40.0, 10.0), 10.0)
        val report = AudioQualityAnalyzer.analyze(cap, 5_000L, activity)
        assertEquals(QualityStatus.INVALID, report.status)
        assertTrue(report.issues.contains(SpeechQualityIssue.CLIPPING))
        assertFalse(report.isUsable)
    }

    @Test
    fun aMildlyClippedRecordingIsFlaggedNotRejected() {
        // About 0.3 % of samples at full scale: above the warning limit, below the invalid limit.
        val x = SyntheticSpeech.tone(180.0, 5_000, amp = 0.3)
        val pcm = SyntheticSpeech.toPcm(x)
        for (i in pcm.indices step 333) pcm[i] = 32767
        val cap = AudioCapture(pcm, sr, sr)
        val activity = VoiceActivityDetector.detect(SignalFrames.frameLevelsDb(SignalFrames.toUnit(pcm), sr, 40.0, 10.0), 10.0)
        val report = AudioQualityAnalyzer.analyze(cap, 5_000L, activity)
        assertTrue(report.issues.contains(SpeechQualityIssue.CLIPPING_WARNING))
        assertEquals(QualityStatus.LOW_QUALITY, report.status)
        assertTrue(report.isUsable)
    }

    @Test
    fun noiseOnlyRecordingIsInsufficientAsSilenceOrNoSpeech() {
        val cap = SyntheticSpeech.capture(SyntheticSpeech.noise(5_000, amp = 0.0005))
        val activity = VoiceActivityDetector.detect(SignalFrames.frameLevelsDb(SignalFrames.toUnit(cap.samples), sr, 40.0, 10.0), 10.0)
        val report = AudioQualityAnalyzer.analyze(cap, 5_000L, activity)
        assertTrue(report.issues.contains(SpeechQualityIssue.SILENCE))
        assertEquals(QualityStatus.INSUFFICIENT_DATA, report.status)
        assertFalse(report.isUsable)
    }
}
