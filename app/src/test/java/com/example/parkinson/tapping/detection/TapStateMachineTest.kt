package com.example.parkinson.tapping.detection

import com.example.parkinson.tapping.SyntheticTapping
import com.example.parkinson.tapping.signal.SignalSample
import com.example.parkinson.tapping.signal.TapSignalProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Event-detection rules tested on hand-crafted samples with a fixed envelope 0..1. */
class TapStateMachineTest {

    private val close = 0.35
    private val open = 0.65
    private val floor = 0.15

    private fun sample(t: Long, v: Double, segmentStart: Boolean = false, active: Boolean = true) =
        SignalSample(0, t, v, v, low = 0.0, high = 1.0, active = active, segmentStart = segmentStart, confidence = 1.0)

    /** Feeds (time, value) pairs, 1 sample each 33 ms unless times are given. */
    private fun run(machine: TapStateMachine, values: List<Pair<Long, Double>>): DetectionResult {
        values.forEachIndexed { i, (t, v) -> machine.onSample(sample(t, v, segmentStart = i == 0), close, open, floor) }
        return machine.result()
    }

    private fun evenly(vararg values: Double, stepMs: Long = 33L) = values.mapIndexed { i, v -> i * stepMs to v }

    @Test
    fun fullCycleIsOneTap() {
        val r = run(TapStateMachine(), evenly(0.0, 0.3, 0.7, 1.0, 0.7, 0.3, 0.0))
        assertEquals(1, r.events.size)
        val e = r.events[0]
        assertEquals(0L, e.startMs)
        assertEquals(99L, e.peakMs)
        assertEquals(165L, e.timestampMs)
        assertEquals(1.0, e.amplitude, 1e-9)
    }

    // 18
    @Test
    fun hysteresisIgnoresOscillationInsideTheBand() {
        val values = (0 until 60).map { if (it % 2 == 0) 0.40 else 0.60 }.toDoubleArray()
        val r = run(TapStateMachine(), evenly(0.0, *values))
        assertEquals(0, r.events.size)
    }

    @Test
    fun crossingOnlyTheOpenThresholdRepeatedlyIsOneTap() {
        // Wobble around the open threshold while open must not create extra taps.
        val r = run(TapStateMachine(), evenly(0.0, 0.7, 0.6, 0.7, 0.6, 0.8, 0.6, 0.2, 0.1))
        assertEquals(1, r.events.size)
    }

    // 17 / 16
    @Test
    fun debounceRejectsClosingTooSoonAfterPreviousTap() {
        val machine = TapStateMachine(DetectionConfig(minInterTapIntervalMs = 100, minEventDurationMs = 0))
        val r = run(
            machine,
            listOf(0L to 0.0, 20L to 1.0, 40L to 0.0, 60L to 1.0, 80L to 0.0, 300L to 1.0, 340L to 0.0)
        )
        assertEquals(2, r.events.size)
        assertEquals(1, r.rejectedDebounce)
    }

    @Test
    fun spikeShorterThanMinimumDurationIsRejected() {
        val machine = TapStateMachine(DetectionConfig(minEventDurationMs = 60))
        val r = run(machine, listOf(0L to 0.0, 15L to 1.0, 30L to 0.0))
        assertEquals(0, r.events.size)
        assertEquals(1, r.rejectedTooShort)
    }

    @Test
    fun dropoutDiscardsCycleInProgress() {
        val machine = TapStateMachine()
        machine.onSample(sample(0, 0.0, segmentStart = true), close, open, floor)
        machine.onSample(sample(33, 0.9), close, open, floor)
        // Tracking lost, the hand comes back closed: this is not a tap.
        machine.onSample(sample(600, 0.0, segmentStart = true), close, open, floor)
        val r = machine.result()
        assertEquals(0, r.events.size)
        assertEquals(1, r.discardedByDropout)
    }

    @Test
    fun inactiveSignalNeverProducesTaps() {
        val machine = TapStateMachine()
        listOf(0.0, 1.0, 0.0, 1.0, 0.0).forEachIndexed { i, v ->
            machine.onSample(sample(i * 50L, v, segmentStart = i == 0, active = false), close, open, floor)
        }
        assertEquals(0, machine.result().events.size)
    }

    // 19
    @Test
    fun startingOpenCountsFirstClosingWithEstimatedStart() {
        val r = run(TapStateMachine(), evenly(1.0, 0.9, 0.5, 0.1, 0.0))
        assertEquals(1, r.events.size)
        assertTrue(r.events[0].startEstimated)
        assertTrue(r.events[0].confidence < 1.0)
    }

    // 20
    @Test
    fun endingOpenIsReportedAsIncomplete() {
        val r = run(TapStateMachine(), evenly(0.0, 1.0, 0.0, 1.0))
        assertEquals(1, r.events.size)
        assertTrue(r.incompleteFinalCycle)
    }

    @Test
    fun shallowClosingIsRejectedAsTooSmall() {
        // Envelope 0..1, but this cycle only spans 0.30..0.66: amplitude 0.36 < 0.4 x range.
        val r = run(TapStateMachine(), evenly(0.30, 0.66, 0.30))
        assertEquals(0, r.events.size)
        assertEquals(1, r.rejectedTooSmall)
    }

    @Test
    fun liveCountUsesTheSamePipelineAsTheFinalResult() {
        // One tap-event source: the live count is detectTaps() on the frames received so far.
        val s = SyntheticTapping(noiseSigma = 0.02).regular(3.0)
        val analyzer = com.example.parkinson.tapping.FingerTappingAnalyzer()
        val live = analyzer.detectTaps(s.frames()).events.size
        val final = analyzer.analyze(s.recording()).metrics.tapCount
        assertEquals(final, live)
        // Partway through, the live count is the count of the frames so far.
        val half = s.frames().filter { it.timestampMs <= s.startMs + 5_000 }
        assertTrue(abs(analyzer.detectTaps(half).events.size - 15) <= 1)
    }
}
