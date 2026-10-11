package com.example.parkinson.sts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Deterministic engine tests on SYNTHETIC movements (see [SyntheticSitToStand]). */
class SitToStandEngineTest {

    private val base = SyntheticSitToStand.BASELINE

    private fun engine(protocol: SitToStandProtocol = SitToStandProtocol.FiveTimesSitToStand) =
        SitToStandEngine(base, protocol)

    private fun run(e: SitToStandEngine, samples: List<SitToStandSample>) {
        samples.forEach { e.process(it) }
    }

    @Test
    fun completeFiveRepetitionSequenceIsCountedAndCompletes() {
        val e = engine()
        run(e, SyntheticSitToStand.samples(SyntheticSitToStand.fiveCycles()))
        assertTrue(e.isComplete)
        assertEquals(SitToStandPhase.TEST_COMPLETED, e.phase)
        assertEquals(5, e.repetitions.size)
        assertEquals(listOf(1, 2, 3, 4, 5), e.repetitions.map { it.index })
        assertNull(e.invalidReason)
    }

    @Test
    fun repetitionDurationsAndTotalTimeMatchTheMovementTimeline() {
        val e = engine()
        run(e, SyntheticSitToStand.samples(SyntheticSitToStand.fiveCycles()))
        // Each cycle is 4 s; a repetition runs from 100 ms after the rise starts to the sustained seated posture,
        // which begins 825 ms into the sit-down: 2725 ms (frames every 33 ms).
        e.repetitions.forEach { rep -> assertEquals(2_725.0, rep.durationMs.toDouble(), 80.0) }
        // Total: first start of movement (1100 ms) to the end of the fifth repetition (19825 ms).
        assertEquals(18_725.0, requireNotNull(e.totalTimeMs).toDouble(), 80.0)
    }

    @Test
    fun phaseDurationsAreMeasuredFromTheSameSamples() {
        val e = engine()
        run(e, SyntheticSitToStand.samples(SyntheticSitToStand.fiveCycles()))
        val rep = e.repetitions.first()
        // Standing-up: 100 ms to 500 ms of the rise (sample grid 33 ms).
        assertEquals(400.0, rep.standingUpMs.toDouble(), 80.0)
        // Sitting-down: from 600 ms to 825 ms of the sit-down.
        assertEquals(225.0, rep.sittingDownMs.toDouble(), 80.0)
        assertTrue(rep.standOnsetMs < rep.standReachedMs && rep.standReachedMs < rep.sitOnsetMs && rep.sitOnsetMs < rep.seatedReachedMs)
    }

    @Test
    fun slowAndVariableMovementIsStillCountedFiveTimes() {
        val slow = listOf(
            SyntheticSitToStand.Segment(1_200L, 0.0, 0.0),
        ) + (1..5).flatMap {
            listOf(
                SyntheticSitToStand.Segment(2_400L + it * 100L, 0.0, 1.0),
                SyntheticSitToStand.Segment(1_500L, 1.0, 1.0),
                SyntheticSitToStand.Segment(2_600L - it * 50L, 1.0, 0.0),
                SyntheticSitToStand.Segment(1_300L, 0.0, 0.0),
            )
        }
        val e = engine()
        run(e, SyntheticSitToStand.samples(slow))
        assertEquals(5, e.repetitions.size)
        assertTrue(e.isComplete)
    }

    @Test
    fun limitedRangeOfMotionIsCountedWhenItStillReachesTheFullStandReference() {
        // Standing reached at posture 0.55 (knee 44 degrees above seated, score 1.1): enough to count.
        val e = engine()
        run(e, SyntheticSitToStand.samples(SyntheticSitToStand.fiveCycles(standPosture = 0.55)))
        assertEquals(5, e.repetitions.size)
    }

    @Test
    fun partialStandingWithoutReachingTheFullStandIsNotCounted() {
        // Peak posture 0.45 gives a score of 0.9: below the standing score of 1.
        val partial = listOf(SyntheticSitToStand.Segment(1_000L, 0.0, 0.0)) +
            (1..5).flatMap {
                listOf(
                    SyntheticSitToStand.Segment(1_000L, 0.0, 0.45),
                    SyntheticSitToStand.Segment(1_000L, 0.45, 0.0),
                    SyntheticSitToStand.Segment(1_000L, 0.0, 0.0),
                )
            }
        val e = engine()
        run(e, SyntheticSitToStand.samples(partial))
        assertTrue(e.repetitions.isEmpty())
        assertFalse(e.isComplete)
        assertEquals(5, e.partialAttempts)
    }

    @Test
    fun sittingDownThenStandingAgainBeforeSeatedIsOneRepetitionNotTwo() {
        // Stand, start to sit (score below 0.8 for a sustained time), stand again, then sit fully.
        val segments = listOf(
            SyntheticSitToStand.Segment(1_000L, 0.0, 0.0),
            SyntheticSitToStand.Segment(1_000L, 0.0, 1.0),
            SyntheticSitToStand.Segment(1_000L, 1.0, 1.0),
            SyntheticSitToStand.Segment(800L, 1.0, 0.3),
            SyntheticSitToStand.Segment(600L, 0.3, 1.0),
            SyntheticSitToStand.Segment(1_000L, 1.0, 1.0),
            SyntheticSitToStand.Segment(1_000L, 1.0, 0.0),
            SyntheticSitToStand.Segment(1_000L, 0.0, 0.0),
        )
        val e = engine(SitToStandProtocol.FiveTimesSitToStand)
        run(e, SyntheticSitToStand.samples(segments))
        assertEquals(1, e.repetitions.size)
        // The counted sitting-down started after the second stand, not during the first attempt to sit.
        assertTrue(e.repetitions.first().sitOnsetMs > 3_500L)
    }

    @Test
    fun aSingleNoisyFrameDoesNotStartAMovement() {
        val frames = (0 until 60).map { i ->
            val p = if (i == 30) 1.0 else 0.0 // one spike of the standing posture in seated frames
            SyntheticSitToStand.sample(i * 33L, p)
        }
        val e = engine()
        run(e, frames)
        assertEquals(SitToStandPhase.SEATED, e.phase)
        assertTrue(e.repetitions.isEmpty())
        assertEquals(0, e.partialAttempts)
    }

    @Test
    fun duplicateTimestampsAreIgnored() {
        val e = engine()
        val s = SyntheticSitToStand.sample(1_000L, 0.0)
        e.process(s)
        e.process(s)
        e.process(s.copy())
        assertEquals(1, e.validFrameCount)
        assertNull(e.invalidReason)
    }

    @Test
    fun timestampGoingBackwardsInvalidatesTheAttempt() {
        val e = engine()
        e.process(SyntheticSitToStand.sample(1_000L, 0.0))
        e.process(SyntheticSitToStand.sample(990L, 0.0))
        assertEquals(SitToStandPhase.PAUSED_OR_INVALID, e.phase)
        assertEquals(SitToStandInvalidReason.NON_MONOTONIC_TIMESTAMPS, e.invalidReason)
        // Nothing is processed after the attempt is invalid.
        e.process(SyntheticSitToStand.sample(2_000L, 1.0))
        assertTrue(e.repetitions.isEmpty())
    }

    @Test
    fun shortTrackingGapIsToleratedButLongLossInvalidates() {
        val e = engine()
        e.process(SyntheticSitToStand.sample(0L, 0.0))
        // 600 ms without a body: below the 1000 ms limit.
        for (t in 33L..600L step 33L) e.process(invalid(t))
        assertNull(e.invalidReason)
        e.process(SyntheticSitToStand.sample(650L, 0.0))
        assertNull(e.invalidReason)
        // 1200 ms without a body: tracking is lost.
        for (t in 683L..1900L step 33L) e.process(invalid(t))
        assertEquals(SitToStandInvalidReason.TRACKING_LOST, e.invalidReason)
        assertEquals(SitToStandPhase.PAUSED_OR_INVALID, e.phase)
    }

    @Test
    fun invalidFramesAreCountedByReasonAndNeverFilledIn() {
        val e = engine()
        e.process(SyntheticSitToStand.sample(0L, 0.0))
        e.process(invalid(33L, SitToStandIssue.LOW_VISIBILITY))
        e.process(invalid(66L, SitToStandIssue.LOW_VISIBILITY))
        e.process(invalid(99L, SitToStandIssue.NO_POSE))
        assertEquals(3, e.invalidFrameCount)
        assertEquals(mapOf(SitToStandIssue.LOW_VISIBILITY to 2, SitToStandIssue.NO_POSE to 1), e.invalidFramesByIssue)
        assertEquals(1, e.validFrameCount)
    }

    @Test
    fun incompleteAttemptAtTheTimeLimitIsNotComplete() {
        val e = engine()
        // Two repetitions only, then the time limit passes.
        val two = SyntheticSitToStand.fiveCycles().take(9)
        val samples = SyntheticSitToStand.samples(two)
        run(e, samples)
        e.checkTimeout(samples.first().timestampMs + 60_000L)
        assertEquals(SitToStandInvalidReason.TIMEOUT_INCOMPLETE, e.invalidReason)
        assertFalse(e.isComplete)
        assertNull(e.totalTimeMs)
    }

    @Test
    fun timeoutIsNotTriggeredBeforeTheLimit() {
        val e = engine()
        e.process(SyntheticSitToStand.sample(0L, 0.0))
        e.checkTimeout(59_000L)
        assertNull(e.invalidReason)
    }

    @Test
    fun interruptionStopsTheAttemptAndNothingIsCounted() {
        val e = engine()
        run(e, SyntheticSitToStand.samples(SyntheticSitToStand.fiveCycles().take(5)))
        e.interrupt()
        assertEquals(SitToStandInvalidReason.INTERRUPTED, e.invalidReason)
        assertEquals(SitToStandPhase.PAUSED_OR_INVALID, e.phase)
        assertFalse(e.isComplete)
        assertNull(e.totalTimeMs)
    }

    @Test
    fun modelFailureEndsWithErrorPhase() {
        val e = engine()
        e.fail()
        assertEquals(SitToStandPhase.ERROR, e.phase)
        e.process(SyntheticSitToStand.sample(0L, 0.0))
        assertEquals(0, e.validFrameCount)
    }

    @Test
    fun samplesAfterTheFifthRepetitionAreIgnored() {
        val e = engine()
        run(e, SyntheticSitToStand.samples(SyntheticSitToStand.fiveCycles()))
        val before = e.repetitions.size
        e.process(SyntheticSitToStand.sample(99_000L, 1.0))
        e.process(SyntheticSitToStand.sample(99_033L, 0.0))
        assertEquals(before, e.repetitions.size)
        assertTrue(e.isComplete)
    }

    @Test
    fun fewerThanTheTargetRepetitionsIsNotComplete() {
        val e = engine()
        // Seated, two full cycles, then the third rise and sit with no seated hold after it: two repetitions counted.
        run(e, SyntheticSitToStand.samples(SyntheticSitToStand.fiveCycles().take(12)))
        assertEquals(2, e.repetitions.size)
        assertFalse(e.isComplete)
        assertNull(e.totalTimeMs)
    }

    @Test
    fun movementScoreIsZeroAtTheSeatedBaselineAndOneAtTheFullStand() {
        val e = engine()
        assertEquals(0.0, e.scoreOf(SyntheticSitToStand.sample(0L, 0.0)), 1e-9)
        assertEquals(1.0, e.scoreOf(SyntheticSitToStand.sample(0L, 0.5)), 1e-9)
        assertTrue(abs(e.scoreOf(SyntheticSitToStand.sample(0L, 1.0)) - 2.0) < 1e-9)
    }

    private fun invalid(t: Long, issue: SitToStandIssue = SitToStandIssue.NO_POSE) =
        SitToStandSample.invalid(t, issue, SitToStandSide.RIGHT)
}
