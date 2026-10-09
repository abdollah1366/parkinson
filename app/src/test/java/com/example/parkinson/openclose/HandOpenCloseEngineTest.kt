package com.example.parkinson.openclose

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.tapping.raw.FrameStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Engine tests on SYNTHETIC openings (a 1 Hz open/close waveform, see [SyntheticOpening]). They check
 * the algorithm's rules only; they are not measurements of any person.
 */
class HandOpenCloseEngineTest {

    private val engine = HandOpenCloseEngine()

    private fun cycles(frames: List<OpenCloseFrame>, endMs: Long) =
        engine.countCycles(frames, startMs = 0L, endMs = endMs)

    /** No cycle may cover any part of the time window [fromMs, toMs]: tracking was lost there. */
    private fun assertNoCycleSpans(cycles: List<OpenCloseCycle>, fromMs: Long, toMs: Long) {
        cycles.forEach { c ->
            assertFalse(
                "cycle ${c.startMs}..${c.endMs} must not span the lost window $fromMs..$toMs",
                c.startMs < toMs && c.endMs > fromMs
            )
        }
    }

    @Test
    fun completeOpenCloseOpenCycleIsCountedOnce() {
        // The first opening crossing (~825 ms) starts the cycle; the next one (~1815 ms) completes it.
        val frames = SyntheticOpening.frames(0, 1_900)
        val cycles = cycles(frames, 1_900)
        assertEquals(1, cycles.size)
        val c = cycles.single()
        assertTrue("cycle starts at the first opening, got ${c.startMs}", c.startMs in 700L..950L)
        assertTrue("cycle ends at the second opening, got ${c.endMs}", c.endMs in 1_700L..1_900L)
        assertTrue("closing stage must be positive", c.closingMs > 0)
        assertTrue("opening stage must be positive", c.openingMs > 0)
        assertTrue("amplitude close to the synthetic range, got ${c.amplitude}", abs(c.amplitude - (SyntheticOpening.OPEN - SyntheticOpening.CLOSED)) < 0.05)
    }

    @Test
    fun multipleConsecutiveCyclesAreEachCountedOnce() {
        // The first opening (~825 ms) only starts the measurement; each later opening completes one cycle:
        // 1815, 2805, ... 9801 ms = 9 cycles in ten seconds.
        val frames = SyntheticOpening.frames(0, 10_000)
        val cycles = cycles(frames, 10_000)
        assertEquals(9, cycles.size)
        assertTrue(cycles.zipWithNext().all { (a, b) -> b.endMs > a.endMs })
    }

    @Test
    fun noMovementProducesNoCycles() {
        // Only sensor-like jitter of +-0.01 around a nearly straight hand.
        val frames = SyntheticOpening.frames(0, 10_000) { t -> 0.9 + if (t % 66 == 0L) 0.01 else -0.01 }
        val analysis = engine.analyze(SyntheticOpening.recording(frames, 0, 10_000))
        assertTrue(analysis.detection.cycles.isEmpty())
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertFalse(analysis.quality.isUsable)
    }

    @Test
    fun partialOpeningNeverCountsAsACycle() {
        // The hand only opens to about half of its range: the highest point (1.5) is below the full-opening reference.
        val frames = SyntheticOpening.frames(0, 10_000) { t -> 0.45 + 0.15 * (1 + kotlin.math.cos(2 * Math.PI * t / 1000.0)) }
        val detection = engine.analyze(SyntheticOpening.recording(frames, 0, 10_000)).detection
        assertTrue("no completed cycle from partial openings", detection.cycles.isEmpty())
        assertTrue("partial openings are rejected and counted", detection.rejectedNotFullRange >= 1)
    }

    @Test
    fun incompleteFinalCycleIsNotCounted() {
        // The recording ends after the first opening crossing (825 ms) but before the cycle is complete (1815 ms).
        val analysis = engine.analyze(SyntheticOpening.recording(SyntheticOpening.frames(0, 1_500), 0, 1_500))
        assertTrue(analysis.detection.cycles.isEmpty())
        assertTrue("the open cycle in progress is reported as incomplete", analysis.detection.incompleteFinalCycle)
    }

    @Test
    fun nonFiniteFingerLandmarkIsOutOfFrame() {
        val lm = SyntheticHandResult.landmarks(0.3).toMutableList()
        lm[HandLandmarkIndex.RING_FINGER_PIP] = HandLandmark(14, Float.NaN, 0.5f, 0f)
        val frame = OpenCloseFrameExtractor.extract(0, HandTrackingResult.HandDetected(100, lm, null, 0.9f, 100, 100))
        assertEquals(FrameStatus.OUT_OF_FRAME, frame.status)
    }

    @Test
    fun partialClosingNeverCountsAsACycle() {
        // The fist never closes below 1.5 (the closed reference is 1.35): a half-closed hand is not a closed hand.
        val frames = SyntheticOpening.frames(0, 10_000) { t -> 0.7 + 0.15 * (1 + kotlin.math.cos(2 * Math.PI * t / 1000.0)) }
        val detection = engine.analyze(SyntheticOpening.recording(frames, 0, 10_000)).detection
        assertTrue(detection.cycles.isEmpty())
    }

    @Test
    fun missingLandmarksDoNotCreateCyclesOrFalseTaps() {
        // Landmarks missing for 1.2 s in the middle: the cycles around that window are not counted.
        val frames = SyntheticOpening.frames(0, 10_000).map { f ->
            if (f.timestampMs in 3_000L..4_200L) f.copy(status = FrameStatus.OUT_OF_FRAME, opening = Double.NaN) else f
        }
        val cycles = cycles(frames, 10_000)
        assertNoCycleSpans(cycles, 3_000L, 4_200L)
        assertTrue("the cycles outside the gap are still counted", cycles.size >= 6)
    }

    @Test
    fun trackingLossDuringACycleDiscardsThatCycle() {
        // A 500 ms gap inside the cycle that starts at 1798 ms: that cycle is never counted.
        val frames = SyntheticOpening.frames(0, 10_000).filterNot { it.timestampMs in 2_000L..2_500L }
        val analysis = engine.analyze(SyntheticOpening.recording(frames, 0, 10_000))
        assertNoCycleSpans(analysis.detection.cycles, 2_000L, 2_500L)
        // Ends at 1815 ms (kept), 2805 ms (lost: it starts before the gap); 2805..9801 ms otherwise.
        assertEquals(8, analysis.detection.cycles.size)
        assertTrue("the discarded cycle is reported", analysis.detection.discardedByDropout >= 1)
        assertEquals(1, analysis.metrics.frames.dropoutCount)
    }

    @Test
    fun duplicateTimestampsAreCountedOnce() {
        val once = SyntheticOpening.frames(0, 10_000)
        val twice = once.flatMap { listOf(it, it.copy(index = it.index + 100_000)) }
        val analysis = engine.analyze(SyntheticOpening.recording(twice, 0, 10_000))
        assertEquals(once.size, analysis.frames.size)
        assertEquals(9, analysis.detection.cycles.size)
        assertEquals(analysis.detection.cycles.size, cycles(once, 10_000).size)
    }

    @Test
    fun irregularFrameIntervalsDoNotChangeTheCount() {
        val gaps = longArrayOf(15, 50, 90, 33, 70)
        val frames = ArrayList<OpenCloseFrame>()
        var t = 0L
        var k = 0
        while (t <= 10_000) {
            frames += SyntheticOpening.validFrame(frames.size, t, SyntheticOpening.opening(t))
            t += gaps[k % gaps.size]
            k++
        }
        val cycles = cycles(frames, 10_000)
        assertEquals(9, cycles.size)
        cycles.forEach { assertTrue("cycle duration ${it.durationMs} ms", it.durationMs in 800L..1_200L) }
    }

    @Test
    fun handMovingOutOfFrameDoesNotCreateCycles() {
        val frames = SyntheticOpening.frames(0, 10_000).map { f ->
            if (f.timestampMs in 4_000L..5_500L) f.copy(status = FrameStatus.OUT_OF_FRAME, opening = Double.NaN) else f
        }
        val analysis = engine.analyze(SyntheticOpening.recording(frames, 0, 10_000))
        assertNoCycleSpans(analysis.detection.cycles, 4_000L, 5_500L)
        assertTrue(analysis.detection.cycles.size in 6..9)
        assertTrue(analysis.metrics.frames.outOfFrameFrames > 0)
    }

    @Test
    fun correctCycleCountWithoutDuplicateEvents() {
        val cycles = cycles(SyntheticOpening.frames(0, 10_000), 10_000)
        assertEquals(9, cycles.size)
        val ends = cycles.map { it.endMs }
        assertEquals("every cycle end is a distinct event", ends.size, ends.toSet().size)
        assertTrue("events are ordered", ends == ends.sorted())
        cycles.forEach { assertTrue("period ${it.durationMs} ms", abs(it.durationMs - 1_000L) <= 100L) }
    }

    @Test
    fun emptyRecordingIsNotUsable() {
        val analysis = engine.analyze(SyntheticOpening.recording(emptyList(), 0, 10_000))
        assertTrue(analysis.detection.cycles.isEmpty())
        assertEquals(QualityStatus.INVALID, analysis.quality.status)
        assertFalse(analysis.quality.isUsable)
        assertNull(analysis.metrics.cycleRatePerSecond)
    }

    @Test
    fun completeRecordingIsValidWithMeasurementsAndNoScore() {
        val analysis = engine.analyze(SyntheticOpening.recording(SyntheticOpening.frames(0, 10_000), 0, 10_000))
        assertEquals(QualityStatus.VALID, analysis.quality.status)
        assertTrue(analysis.quality.isUsable)
        assertEquals(9, analysis.metrics.completedCycles)
        val rate = analysis.metrics.cycleRatePerSecond!!
        assertTrue("about one cycle per second, got $rate", abs(rate - 1.0) < 0.1)
        assertTrue(analysis.metrics.cycleDurationCvPercent!! < 5.0)
        assertEquals(0, analysis.metrics.partialOpenings)
    }

    @Test
    fun wrongHandIsRejectedByTheRecordingCheck() {
        val frames = SyntheticOpening.frames(0, 10_000).map { it.copy(sideStatus = HandSideStatus.MISMATCH) }
        val analysis = engine.analyze(SyntheticOpening.recording(frames, 0, 10_000))
        assertEquals(QualityStatus.INVALID, analysis.quality.status)
        assertTrue(analysis.quality.issues.contains(OpenCloseQualityIssue.WRONG_HAND))
    }

    @Test
    fun implausibleOpeningIsTreatedAsALandmarkGlitch() {
        val frames = SyntheticOpening.frames(0, 2_000).map { f ->
            if (f.timestampMs == 990L) f.copy(opening = 2.0) else f
        }
        val analysis = engine.analyze(SyntheticOpening.recording(frames, 0, 2_000))
        val glitch = analysis.frames.single { it.timestampMs == 990L }
        assertEquals(FrameStatus.OUT_OF_FRAME, glitch.status)
    }

    // --- Landmark extraction ---------------------------------------------------------------

    @Test
    fun straightFingerIsFullExtensionAndCurlLowersIt() {
        val straight = OpenCloseFrameExtractor.extract(0, SyntheticHandResult.hand(100, 1.0))
        assertEquals(FrameStatus.VALID, straight.status)
        assertEquals(1.0, straight.opening, 0.01)
        for (target in listOf(0.9, 0.6, 0.45)) {
            val frame = OpenCloseFrameExtractor.extract(0, SyntheticHandResult.hand(100, target))
            assertEquals(FrameStatus.VALID, frame.status)
            assertEquals("target $target", target, frame.opening, 0.01)
        }
    }

    /**
     * Different finger proportions (short, typical, long fingers relative to the palm) must give the
     * same feature for the same finger posture. The old fingertip-to-wrist / palm feature failed this:
     * a short-fingered hand never reached its "open" reference, and every attempt was rejected.
     */
    @Test
    fun handProportionsDoNotChangeTheFeature() {
        for (target in listOf(1.0, 0.8, 0.5)) {
            val values = listOf(0.75, 0.9, 1.0, 1.2, 1.35).map { scale ->
                OpenCloseFrameExtractor.extract(0, SyntheticHandResult.hand(100, target, lengthScale = scale)).opening
            }
            values.forEach { assertEquals("posture $target", target, it, 0.01) }
        }
    }

    @Test
    fun recordingWithShortFingersIsCountedLikeATypicalHand() {
        // Same posture waveform for a short-fingered and a long-fingered person: the same cycle count.
        fun countFor(scale: Double): Int {
            val frames = (0L..10_000L step 33L).map { t ->
                OpenCloseFrameExtractor.extract(t.toInt(), SyntheticHandResult.hand(t, SyntheticOpening.opening(t), lengthScale = scale))
            }
            return HandOpenCloseEngine().analyze(SyntheticOpening.recording(frames, 0, 10_000)).detection.cycles.size
        }
        val short = countFor(0.75)
        assertEquals(9, short)
        assertEquals(short, countFor(1.3))
    }

    @Test
    fun missingLandmarksAreOutOfFrame() {
        val short = HandTrackingResult.HandDetected(
            timestampMs = 100,
            landmarks = List(10) { HandLandmark(it, 0.5f, 0.5f, 0f) },
            handSide = null,
            confidence = 0.9f,
            imageWidth = 100,
            imageHeight = 100
        )
        val frame = OpenCloseFrameExtractor.extract(0, short)
        assertEquals(FrameStatus.OUT_OF_FRAME, frame.status)
        assertTrue(frame.opening.isNaN())
    }

    @Test
    fun fingertipOutsideTheImageIsOutOfFrame() {
        val lm = SyntheticHandResult.landmarks(0.3).toMutableList()
        lm[HandLandmarkIndex.MIDDLE_FINGER_TIP] = HandLandmark(12, 0.5f, 1.3f, 0f)
        val frame = OpenCloseFrameExtractor.extract(
            0,
            HandTrackingResult.HandDetected(100, lm, null, 0.9f, 100, 100)
        )
        assertEquals(FrameStatus.OUT_OF_FRAME, frame.status)
    }

    @Test
    fun noHandAndMultipleHandsAreNotValid() {
        assertEquals(FrameStatus.NO_HAND, OpenCloseFrameExtractor.extract(0, HandTrackingResult.NoHandDetected(1)).status)
        assertEquals(
            FrameStatus.MULTIPLE_HANDS,
            OpenCloseFrameExtractor.extract(0, HandTrackingResult.MultipleHandsDetected(1, 2)).status
        )
        assertEquals(FrameStatus.ERROR, OpenCloseFrameExtractor.extract(0, HandTrackingResult.Error(1, "x")).status)
    }
}
