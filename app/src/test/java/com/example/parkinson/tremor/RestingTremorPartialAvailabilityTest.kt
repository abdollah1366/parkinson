package com.example.parkinson.tremor

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.tapping.raw.FrameStatus
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** SYNTHETIC recordings for unit tests only. */
class RestingTremorPartialAvailabilityTest {

    private val engine = RestingTremorEngine()

    /**
     * 15 s of a 5 Hz movement with five 400 ms tracking gaps. The gaps cut the frame coverage below the
     * 90 % the frequency needs, but the amplitude (from the valid frames) is still measurable.
     */
    private fun recordingWithGaps(): RestingTremorRecording {
        val base = SyntheticTremor.frames(frequencyHz = 5.0, amplitude = 0.05)
        val gapStarts = listOf(1_000L, 3_000L, 5_000L, 7_000L, 9_000L)
        val frames = SyntheticTremor.withStatus(base, FrameStatus.NO_HAND) { f ->
            gapStarts.any { s -> f.timestampMs >= s && f.timestampMs < s + 400L }
        }
        return SyntheticTremor.recording(frames)
    }

    @Test
    fun unavailableFrequencyDoesNotInvalidateTheAmplitude() {
        val analysis = engine.analyze(recordingWithGaps())
        val m = requireNotNull(analysis.metrics)
        // The recording is usable (gaps and reduced validity are LOW_QUALITY, not INSUFFICIENT).
        assertTrue(analysis.quality.isUsable)
        assertTrue(analysis.quality.status == QualityStatus.LOW_QUALITY)
        assertTrue(m.signalCoveragePercent < 90.0)
        // Frequency needs 90 % coverage: unavailable, reported as null (never zero).
        assertNull(m.dominantFrequencyHz)
        assertNull(m.spectralProminence)
        assertNull(m.periodicityPercent)
        // Amplitude comes from the valid samples and is still reported.
        val amplitude = requireNotNull(m.amplitudeRmsPercent)
        assertTrue(amplitude > 0.0)
    }

    @Test
    fun summaryNamesUnavailableMetricsAndCountsStatuses() {
        val recording = recordingWithGaps()
        val analysis = engine.analyze(recording)
        val text = RestingTremorDiagnostics.summary(
            resultsInWindow = recording.frames.size,
            resultsOutsideWindow = 2,
            recording = recording,
            analysis = analysis,
        )
        assertTrue(text, text.contains("resultsInWindow=${recording.frames.size}"))
        assertTrue(text, text.contains("resultsOutsideWindow=2"))
        assertTrue(text, text.contains("NO_HAND:"))
        assertTrue(text, text.contains("frequencyHz=unavailable"))
        assertTrue(text, text.contains("quality=LOW_QUALITY"))
        assertTrue(text, text.contains("nonMonotonicAt=-1"))
    }

    @Test
    fun summaryForAnUnmeasurableRecordingSaysSo() {
        val recording = RestingTremorRecording(frames = emptyList(), plannedDurationMs = 15_000L)
        val analysis = engine.analyze(recording)
        val text = RestingTremorDiagnostics.summary(0, 0, recording, analysis)
        assertTrue(text, text.endsWith("metrics=unavailable"))
        assertTrue(text, text.contains("quality=INSUFFICIENT_DATA"))
    }

    @Test
    fun summaryLocatesANonMonotonicTimestamp() {
        val frames = SyntheticTremor.frames(durationMs = 2_000L)
        val times = frames.mapIndexed { i, f -> if (i == 10) f.timestampMs - 500 else f.timestampMs }
        val recording = SyntheticTremor.recording(SyntheticTremor.withTimestamps(frames, times))
        val analysis = engine.analyze(recording)
        val text = RestingTremorDiagnostics.summary(frames.size, 0, recording, analysis)
        assertTrue(text, text.contains("nonMonotonicAt=10"))
        assertTrue(text, text.contains("quality=INVALID"))
        assertTrue(text, text.contains("NON_MONOTONIC_TIMESTAMPS"))
    }
}
