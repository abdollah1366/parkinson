package com.example.parkinson.tremor

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.tapping.raw.FrameStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Engine tests on SYNTHETIC trajectories (test input only). */
class RestingTremorEngineTest {

    private val engine = RestingTremorEngine()

    private fun analyze(frames: List<RestingTremorFrame>, planned: Long = 15_000L) =
        engine.analyze(SyntheticTremor.recording(frames, planned))

    @Test
    fun knownCircularMovementGivesTheKnownFrequencyAndAmplitude() {
        // 5 Hz circle of radius 5 % of a hand length: the radial amplitude is exactly 5 %.
        val analysis = analyze(SyntheticTremor.frames(frequencyHz = 5.0, amplitude = 0.05))
        val m = requireNotNull(analysis.metrics)
        assertEquals(QualityStatus.VALID, analysis.quality.status)
        assertEquals(5.0, requireNotNull(m.dominantFrequencyHz), 0.1)
        assertEquals(5.0, requireNotNull(m.amplitudeRmsPercent), 0.5)
        assertTrue(requireNotNull(m.periodicityPercent) > 50.0)
        assertEquals(100.0, m.validFramePercent, 1e-9)
        assertEquals(0, m.interruptionCount)
        assertEquals(0.0, m.longestInterruptionMs.toDouble(), 1e-9)
        assertTrue(m.slowDriftPercent < 5.0)
    }

    @Test
    fun stationaryHandWithMinimalNoiseHasASmallAmplitudeAndNoFrequency() {
        // Noise of 0.05 px on a 100 px hand: far below any movement, and no peak stands out.
        val analysis = analyze(SyntheticTremor.frames(frequencyHz = null, noise = 0.0005))
        val m = requireNotNull(analysis.metrics)
        assertEquals(QualityStatus.VALID, analysis.quality.status)
        assertTrue(requireNotNull(m.amplitudeRmsPercent) < 0.5)
        assertNull("no reliable peak in noise", m.dominantFrequencyHz)
        assertNull(m.periodicityPercent)
        assertNull(m.spectralProminence)
    }

    @Test
    fun perfectlyStillHandGivesZeroAmplitudeAndNoFrequency() {
        val m = requireNotNull(analyze(SyntheticTremor.frames(frequencyHz = null)).metrics)
        assertEquals(0.0, requireNotNull(m.amplitudeRmsPercent), 1e-9)
        assertNull(m.dominantFrequencyHz)
    }

    @Test
    fun missingSamplesReduceTheValidShareButKeepTheMeasurement() {
        val frames = SyntheticTremor.frames(frequencyHz = 5.0, amplitude = 0.05)
        // Every 7th frame has no hand: 85.7 % valid, single-frame gaps only.
        val lossy = SyntheticTremor.withStatus(frames, FrameStatus.NO_HAND) { it.index % 7 == 3 }
        val analysis = analyze(lossy)
        val m = requireNotNull(analysis.metrics)
        assertEquals(QualityStatus.LOW_QUALITY, analysis.quality.status)
        assertTrue(analysis.quality.isUsable)
        assertTrue(analysis.quality.issues.contains(RestingTremorQualityIssue.REDUCED_VALID_FRAMES))
        assertEquals(100.0 * (frames.size - frames.count { it.index % 7 == 3 }) / frames.size, m.validFramePercent, 1e-9)
        assertEquals(0, m.interruptionCount)
        // The movement is still recovered from the remaining samples.
        assertEquals(5.0, requireNotNull(m.dominantFrequencyHz), 0.2)
    }

    @Test
    fun trackingLossLongerThanOneSecondIsInsufficientData() {
        val frames = SyntheticTremor.frames(frequencyHz = 5.0, amplitude = 0.05)
        val lost = SyntheticTremor.withStatus(frames, FrameStatus.NO_HAND) { it.timestampMs in 5_000L..6_599L }
        val analysis = analyze(lost)
        val m = requireNotNull(analysis.metrics)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertFalse(analysis.quality.isUsable)
        assertTrue(analysis.quality.issues.contains(RestingTremorQualityIssue.LONG_INTERRUPTION))
        assertEquals(1, m.interruptionCount)
        assertTrue(m.longestInterruptionMs >= 1_500L)
    }

    @Test
    fun repeatedInterruptionsAreCountedAndReported() {
        val frames = SyntheticTremor.frames(frequencyHz = 5.0, amplitude = 0.05)
        // Five 400 ms gaps: longer than the 250 ms interruption limit, shorter than the 1 s limit.
        val gaps = listOf(2_000L, 4_000L, 6_000L, 8_000L, 10_000L)
        val lossy = SyntheticTremor.withStatus(frames, FrameStatus.NO_HAND) { f ->
            gaps.any { f.timestampMs in it until it + 400 }
        }
        val m = requireNotNull(analyze(lossy).metrics)
        assertEquals(5, m.interruptionCount)
        // A 400 ms outage ends at the next valid frame (about 33 ms later), so each gap is about 430 ms.
        assertTrue(m.longestInterruptionMs in 400L..460L)
        assertTrue(m.interruptionTotalMs in 2_000L..2_300L)
        assertEquals(QualityStatus.LOW_QUALITY, analyze(lossy).quality.status)
    }

    @Test
    fun irregularFrameIntervalsAreInsufficient() {
        val frames = SyntheticTremor.frames(frequencyHz = 5.0, amplitude = 0.05)
        // Alternating 20 ms and 80 ms steps (median 50 ms): half of the intervals are outside [0.5, 2] x median.
        var t = 0L
        val timestamps = frames.indices.map { i -> t.also { t += if (i % 2 == 0) 20L else 80L } }
        val analysis = analyze(SyntheticTremor.withTimestamps(frames, timestamps))
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertTrue(analysis.quality.issues.contains(RestingTremorQualityIssue.IRREGULAR_SAMPLING))
        assertEquals(50.0, requireNotNull(analysis.metrics).irregularIntervalPercent, 1.0)
    }

    @Test
    fun nonMonotonicTimestampsAreInvalidAndGiveNoMetrics() {
        val frames = SyntheticTremor.frames(frequencyHz = 5.0, amplitude = 0.05)
        val timestamps = frames.map { it.timestampMs }.toMutableList()
        timestamps[100] = timestamps[90]
        val analysis = analyze(SyntheticTremor.withTimestamps(frames, timestamps))
        assertEquals(QualityStatus.INVALID, analysis.quality.status)
        assertEquals(listOf(RestingTremorQualityIssue.NON_MONOTONIC_TIMESTAMPS), analysis.quality.issues)
        assertNull(analysis.metrics)
    }

    @Test
    fun tooShortRecordingIsInsufficientData() {
        val frames = SyntheticTremor.frames(durationMs = 5_000L, frequencyHz = 5.0, amplitude = 0.05)
        val analysis = analyze(frames, planned = 15_000L)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertTrue(analysis.quality.issues.contains(RestingTremorQualityIssue.RECORDING_TOO_SHORT))
    }

    @Test
    fun grossMovementOfTheWholeHandIsInvalid() {
        // Drift of one hand length per second: the 1 s local mean moves about 14 hand lengths over 15 s.
        val frames = SyntheticTremor.frames(frequencyHz = 5.0, amplitude = 0.05, driftPerSecond = 1.0)
        val analysis = analyze(frames)
        assertEquals(QualityStatus.INVALID, analysis.quality.status)
        assertEquals(RestingTremorQualityIssue.GROSS_MOVEMENT, analysis.quality.primaryIssue)
        assertFalse(analysis.quality.isUsable)
    }

    @Test
    fun multipleHandsInMostFramesIsInvalid() {
        val frames = SyntheticTremor.frames(frequencyHz = 5.0, amplitude = 0.05)
        val crowded = SyntheticTremor.withStatus(frames, FrameStatus.MULTIPLE_HANDS) { it.index % 5 < 2 }
        val analysis = analyze(crowded)
        assertEquals(QualityStatus.INVALID, analysis.quality.status)
        assertTrue(analysis.quality.issues.contains(RestingTremorQualityIssue.MULTIPLE_HANDS))
    }

    @Test
    fun noValidFramesGivesNoMeasurementAtAll() {
        val frames = SyntheticTremor.withStatus(SyntheticTremor.frames(), FrameStatus.NO_HAND) { true }
        val analysis = analyze(frames)
        assertNull("must not invent a measurement", analysis.metrics)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertFalse(analysis.quality.isUsable)
    }

    @Test
    fun tooFewFramesGivesNoMeasurement() {
        val analysis = analyze(SyntheticTremor.frames(durationMs = 300L))
        assertNull(analysis.metrics)
        assertEquals(listOf(RestingTremorQualityIssue.TOO_FEW_FRAMES), analysis.quality.issues)
    }

    @Test
    fun metricsUseTheFramesTimingAndCounts() {
        val frames = SyntheticTremor.frames(durationMs = 9_000L, frequencyHz = 5.0, amplitude = 0.05)
        val invalidIndices = setOf(10, 20, 30)
        val withLoss = SyntheticTremor.withStatus(frames, FrameStatus.OUT_OF_FRAME) { it.index in invalidIndices }
        val m = requireNotNull(analyze(withLoss, planned = 9_000L).metrics)
        assertEquals(frames.size, m.totalFrames)
        assertEquals(frames.size - 3, m.validFrames)
        assertEquals(3, m.outOfFrameFrames)
        assertEquals(9_000L, m.plannedDurationMs)
        assertEquals(frames.last().timestampMs - frames.first().timestampMs, m.actualDurationMs)
        assertEquals(33.333, m.medianFrameIntervalMs, 0.5)
        assertEquals(30.0, m.frameRateHz, 0.5)
        assertEquals(100.0 * (frames.size - 3) / frames.size, m.validFramePercent, 1e-9)
        assertEquals(100.0, m.medianHandScalePx, 1e-9)
        assertTrue(m.signalCoveragePercent > 90.0)
    }
}
