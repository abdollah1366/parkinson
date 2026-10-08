package com.example.parkinson.tapping

import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.quality.QualityIssue
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.tapping.raw.FrameStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/** End-to-end tests of the analysis pipeline on deterministic synthetic recordings. */
class FingerTappingAnalyzerTest {

    private val analyzer = FingerTappingAnalyzer()

    private fun analyze(s: SyntheticTapping) = analyzer.analyze(s.recording())

    private fun assertTaps(expected: Int, actual: Int, tolerance: Int = 0) {
        assertTrue("expected $expected +-$tolerance taps, got $actual", abs(expected - actual) <= tolerance)
    }

    // 1
    @Test
    fun noMovementGivesNoTapsAndNoScore() {
        val s = SyntheticTapping(noiseSigma = 0.01).apply { constantOpening = 0.6 }
        val a = analyze(s)
        assertEquals(0, a.metrics.tapCount)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertTrue(QualityIssue.NO_TAPPING_DETECTED in a.quality.issues)
        assertNull(a.score)
    }

    // 2
    @Test
    fun singleTapIsCountedOnceButIsInsufficient() {
        val s = SyntheticTapping().apply {
            // Closed for 4.5 s, one 1 s tap, closed again.
            periodsMs = listOf(4_500.0, 1_000.0, 4_600.0)
            amplitudes = listOf(0.0, 0.9, 0.0)
        }
        val a = analyze(s)
        assertEquals(1, a.metrics.tapCount)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertTrue(QualityIssue.TOO_FEW_TAPS in a.quality.issues)
        assertNull(a.score)
    }

    // 3
    @Test
    fun slowTapping() {
        val s = SyntheticTapping().regular(1.0)
        val a = analyze(s)
        assertTaps(s.expectedTaps(), a.metrics.tapCount)
        assertEquals(QualityStatus.VALID, a.quality.status)
        assertEquals(1000.0, a.metrics.medianIntervalMs!!, 40.0)
    }

    // 4
    @Test
    fun normalTapping() {
        val s = SyntheticTapping().regular(3.0)
        val a = analyze(s)
        assertTaps(s.expectedTaps(), a.metrics.tapCount)
        assertEquals(3.0, a.metrics.tapRateHz, 0.15)
        assertEquals(30.0, a.metrics.tapsPer10Seconds, 1.5)
        assertNotNull(a.score)
    }

    // 5
    @Test
    fun fastTappingAt30Fps() {
        val s = SyntheticTapping(fps = 30.0).regular(5.0)
        val a = analyze(s)
        assertTaps(s.expectedTaps(), a.metrics.tapCount, tolerance = 1)
    }

    @Test
    fun veryFastTappingAt60Fps() {
        val s = SyntheticTapping(fps = 60.0).regular(7.0)
        val a = analyze(s)
        assertTaps(s.expectedTaps(), a.metrics.tapCount, tolerance = 1)
    }

    // 6
    @Test
    fun smallAmplitudeTapsAreCounted() {
        val s = SyntheticTapping(noiseSigma = 0.005).regular(2.0).apply { amplitudes = listOf(0.25) }
        val a = analyze(s)
        assertTaps(s.expectedTaps(), a.metrics.tapCount)
        assertEquals(0.25, a.metrics.meanAmplitude!!, 0.05)
    }

    // 7
    @Test
    fun largeAmplitudeTapsAreCounted() {
        val s = SyntheticTapping().regular(2.0).apply { amplitudes = listOf(1.8) }
        val a = analyze(s)
        assertTaps(s.expectedTaps(), a.metrics.tapCount)
        assertEquals(1.8, a.metrics.meanAmplitude!!, 0.15)
    }

    @Test
    fun amplitudeDoesNotDependOnTapRate() {
        val slow = analyze(SyntheticTapping().regular(1.0)).metrics.meanAmplitude!!
        val fast = analyze(SyntheticTapping().regular(5.0)).metrics.meanAmplitude!!
        assertEquals(0.9, slow, 0.05)
        assertTrue("slow $slow fast $fast", abs(slow - fast) / slow < 0.10)
    }

    // 8
    @Test
    fun irregularTapping() {
        val r = Random(7)
        val s = SyntheticTapping().apply { periodsMs = List(40) { 250.0 + r.nextDouble() * 550.0 } }
        val a = analyze(s)
        assertTaps(s.expectedTaps(), a.metrics.tapCount, tolerance = 1)
    }

    // 9
    @Test
    fun noisySignalIsNotDoubleCounted() {
        val s = SyntheticTapping(noiseSigma = 0.05).regular(2.0).apply { amplitudes = listOf(0.8) }
        val a = analyze(s)
        assertTaps(s.expectedTaps(), a.metrics.tapCount, tolerance = 1)
    }

    @Test
    fun noiseAloneIsNotTapping() {
        val s = SyntheticTapping(noiseSigma = 0.04).apply { constantOpening = 0.1 }
        assertEquals(0, analyze(s).metrics.tapCount)
    }

    // 10
    @Test
    fun singleDropoutIsNotCountedAsTapping() {
        val s = SyntheticTapping().regular(3.0).status(5_000, 5_400, FrameStatus.NO_HAND)
        val a = analyze(s)
        val expected = s.expectedTaps()
        assertTrue(a.metrics.tapCount <= expected)
        assertTrue(a.metrics.tapCount >= expected - 2)
        assertEquals(1, a.metrics.frames.dropoutCount)
        assertEquals(QualityStatus.VALID, a.quality.status)
    }

    // 11
    @Test
    fun multipleDropoutsLowerQuality() {
        val s = SyntheticTapping().regular(3.0)
            .status(2_000, 2_600, FrameStatus.NO_HAND)
            .status(5_000, 5_600, FrameStatus.NO_HAND)
            .status(8_000, 8_600, FrameStatus.NO_HAND)
        val a = analyze(s)
        assertEquals(3, a.metrics.frames.dropoutCount)
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertTrue(QualityIssue.DROPOUTS_PRESENT in a.quality.issues)
        // LOW_QUALITY is scored, marked as limited reliability.
        assertEquals(ReliabilityLevel.LIMITED, a.score!!.reliability)
        assertTrue(a.metrics.tapCount <= s.expectedTaps())
    }

    @Test
    fun longDropoutIsInsufficientData() {
        // A 3 s gap: too little continuous motor data (not a technical failure of the recording).
        val s = SyntheticTapping().regular(3.0).status(3_000, 6_000, FrameStatus.NO_HAND)
        val a = analyze(s)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertTrue(QualityIssue.EXCESSIVE_DROPOUT in a.quality.issues)
        assertNull(a.score)
    }

    // 12
    @Test
    fun incompleteRecordingIsInvalid() {
        val s = SyntheticTapping(durationMs = 6_000).regular(3.0)
        val a = analyzer.analyze(s.recording(plannedDurationMs = 10_000))
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertTrue(QualityIssue.RECORDING_INCOMPLETE in a.quality.issues)
        assertNull(a.score)
    }

    // 13
    @Test
    fun insufficientFpsIsInvalid() {
        // 6 results/s is below the absolute floor: even slow taps get too few samples.
        val s = SyntheticTapping(fps = 6.0).regular(1.0)
        val a = analyze(s)
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertTrue(QualityIssue.INSUFFICIENT_FPS in a.quality.issues)
        assertNull(a.score)
    }

    @Test
    fun frameRateRequirementFollowsTheTapRate() {
        // 10 results/s resolve 2 Hz tapping (5 samples per tap): usable, LOW_QUALITY, scored.
        val slow = analyze(SyntheticTapping(fps = 10.0).regular(2.0))
        assertEquals(QualityStatus.LOW_QUALITY, slow.quality.status)
        assertTrue(QualityIssue.LOW_FPS in slow.quality.issues)
        assertNotNull(slow.score)
        // The requirement rises with the measured tap rate (>= 4 samples per tap cycle).
        val fast = analyze(SyntheticTapping(fps = 30.0).regular(4.0))
        assertEquals(4 * fast.metrics.tapRateHz, fast.quality.requiredFps, 1e-9)
    }

    @Test
    fun lowFpsIsLowQuality() {
        val s = SyntheticTapping(fps = 15.0).regular(2.0)
        val a = analyze(s)
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertTrue(QualityIssue.LOW_FPS in a.quality.issues)
    }

    // 14
    @Test
    fun noHandIsInsufficientData() {
        // Frames arrived (the recording itself is fine) but they contain no hand: no motor data.
        val s = SyntheticTapping().regular(3.0).status(0, 20_000, FrameStatus.NO_HAND)
        val a = analyze(s)
        assertEquals(0, a.metrics.tapCount)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertNull(a.score)
        assertEquals(QualityIssue.NO_HAND_DETECTED, a.quality.primaryIssue)
    }

    // 15
    @Test
    fun wrongHandIsInvalid() {
        // Every frame confidently labelled as the other hand.
        val s = SyntheticTapping().regular(3.0).side(0, 20_000, HandSideStatus.MISMATCH)
        val a = analyze(s)
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertEquals(QualityIssue.WRONG_HAND, a.quality.primaryIssue)
        assertNull(a.score)
    }

    @Test
    fun occasionalOtherHandLabelsKeepTheData() {
        // 35 % of frames labelled as the other hand (e.g. the hand seen edge-on): the landmarks are
        // the same single tracked hand, so the data stay; the side is flagged as uncertain.
        val s = SyntheticTapping().regular(3.0).side(0, 3_500, HandSideStatus.MISMATCH)
        val a = analyze(s)
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertTrue(QualityIssue.HAND_SIDE_UNCERTAIN in a.quality.issues)
        assertTaps(s.expectedTaps(), a.metrics.tapCount, tolerance = 1)
        assertNotNull(a.score)
    }

    // 19
    @Test
    fun recordingThatStartsOpenCountsTheFirstClosing() {
        val s = SyntheticTapping().regular(2.0).apply { startPhase = 0.5 }
        val a = analyze(s)
        assertTaps(s.expectedTaps(), a.metrics.tapCount)
        assertTrue(a.detection.events.first().startEstimated)
        assertFalse(a.detection.events[1].startEstimated)
    }

    // 20
    @Test
    fun finalIncompleteCycleIsNotCounted() {
        // 2 Hz with the last cycle cut while the fingers are open.
        val s = SyntheticTapping(durationMs = 10_000).apply {
            periodsMs = List(19) { 500.0 } + listOf(1_000.0)
        }
        val a = analyze(s)
        assertTrue(a.detection.incompleteFinalCycle)
        assertEquals(19, a.metrics.tapCount)
    }

    // 21
    @Test
    fun amplitudeDecrementIsMeasuredAsTrend() {
        val s = SyntheticTapping().regular(3.0).apply {
            amplitudes = List(32) { 1.0 - 0.5 * it / 31.0 }
        }
        val a = analyze(s)
        val trend = a.metrics.amplitudeTrend.relativeChangePercent!!
        assertTrue("trend $trend", trend < -30.0 && trend > -60.0)
        assertTrue(a.metrics.amplitudeSlopePercentPerSecond!! < 0)
        assertTrue(a.metrics.amplitudeTrend.early!! > a.metrics.amplitudeTrend.middle!!)
        assertTrue(a.metrics.amplitudeTrend.middle!! > a.metrics.amplitudeTrend.late!!)
    }

    @Test
    fun constantAmplitudeHasFlatTrend() {
        val a = analyze(SyntheticTapping().regular(3.0))
        assertEquals(0.0, a.metrics.amplitudeTrend.relativeChangePercent!!, 5.0)
    }

    // 22
    @Test
    fun stableRhythmHasLowVariability() {
        val a = analyze(SyntheticTapping().regular(3.0))
        assertTrue(a.metrics.intervalCvPercent!! < 5.0)
        assertTrue(a.metrics.tapToTapVariabilityPercent!! < 5.0)
        assertTrue(a.metrics.movementConsistencyPercent!! > 90.0)
        assertEquals(0, a.metrics.pauseCount)
    }

    // 23
    @Test
    fun unstableRhythmHasHighVariability() {
        val r = Random(3)
        val s = SyntheticTapping().apply { periodsMs = List(40) { 200.0 + r.nextDouble() * 700.0 } }
        val a = analyze(s)
        assertTrue("cv ${a.metrics.intervalCvPercent}", a.metrics.intervalCvPercent!! > 20.0)
        val stable = analyze(SyntheticTapping().regular(3.0))
        assertTrue(a.metrics.intervalCvPercent!! > stable.metrics.intervalCvPercent!!)
    }

    @Test
    fun pauseIsCounted() {
        val s = SyntheticTapping().apply { periodsMs = List(12) { 333.0 } + listOf(1_500.0) + List(20) { 333.0 } }
        assertTrue(analyze(s).metrics.pauseCount >= 1)
    }

    // 24
    @Test
    fun handMovingTowardsCameraIsFlaggedAsUnstable() {
        // Palm size changes, simulated by tapping over a varying scale.
        val base = SyntheticTapping().regular(3.0)
        val frames = base.frames().map {
            val factor = 0.5 + it.index / 300.0
            it.copy(thumbIndexDistancePx = it.thumbIndexDistancePx * factor, handScalePx = it.handScalePx * factor)
        }
        val a = analyzer.analyze(base.recording().copy(frames = frames))
        assertTrue(QualityIssue.UNSTABLE_TRACKING in a.quality.issues)
        // LOW_QUALITY: scored, marked as limited reliability.
        assertEquals(ReliabilityLevel.LIMITED, a.score!!.reliability)
        // Normalization keeps the taps countable.
        assertTaps(base.expectedTaps(), a.metrics.tapCount, tolerance = 1)
    }

    @Test
    fun recordingFramesOutsideTheWindowAreIgnored() {
        val s = SyntheticTapping().regular(3.0)
        val recording = s.recording()
        val shifted = recording.copy(endMs = recording.startMs + 5_000, plannedDurationMs = 5_000)
        val a = analyzer.analyze(shifted)
        assertTrue(a.metrics.tapCount in 14..16)
        assertEquals(5_000, a.metrics.recordingDurationMs)
    }

    // 25
    @Test
    fun analysisIsReproducible() {
        val s1 = SyntheticTapping(noiseSigma = 0.03, seed = 11).regular(3.0)
        val s2 = SyntheticTapping(noiseSigma = 0.03, seed = 11).regular(3.0)
        val a1 = analyzer.analyze(s1.recording())
        val a2 = analyzer.analyze(s2.recording())
        assertEquals(a1.metrics, a2.metrics)
        assertEquals(a1.quality, a2.quality)
        assertEquals(a1.score, a2.score)
        assertEquals(a1.detection.events, a2.detection.events)
    }

    // 26
    @Test
    fun assessmentCarriesAlgorithmAndScoringVersions() {
        val a = analyze(SyntheticTapping().regular(3.0))
        val assessment = FingerTappingAnalyzer.toAssessment(a, "id-1", 1_700_000_000_000L, SelectedHand.LEFT)
        assertEquals(FingerTappingVersions.ALGORITHM_VERSION, assessment.algorithmVersion)
        assertEquals(FingerTappingVersions.SCORING_VERSION, assessment.scoringVersion)
        assertEquals(FingerTappingVersions.SCORING_VERSION, assessment.performanceScore!!.scoringVersion)
        assertFalse(assessment.performanceScore!!.isClinicallyValidated)
        assertEquals("1.0", FingerTappingVersions.SCORING_VERSION)
        assertTrue(Regex("""ft-algo-\d+\.\d+\.\d+""").matches(FingerTappingVersions.ALGORITHM_VERSION))
        assertEquals(SelectedHand.LEFT, assessment.hand)
        assertEquals(a.metrics.tapCount, assessment.tapCount)
        assertEquals(a.quality.status, assessment.qualityStatus)
    }

    @Test
    fun tapEventsAreStructuredAndOrdered() {
        val a = analyze(SyntheticTapping().regular(2.0))
        val events = a.detection.events
        events.zipWithNext { x, y -> assertTrue(y.timestampMs > x.timestampMs) }
        events.forEachIndexed { i, e ->
            assertEquals(i, e.index)
            assertTrue(e.startMs <= e.peakMs && e.peakMs <= e.timestampMs)
            assertTrue(e.durationMs > 0)
            assertTrue(e.openingDurationMs >= 0 && e.closingDurationMs >= 0)
            assertTrue(e.amplitude > 0)
            assertTrue(e.confidence in 0.0..1.0)
        }
        assertTrue(a.metrics.meanEventConfidence!! > 0.8)
    }
}
