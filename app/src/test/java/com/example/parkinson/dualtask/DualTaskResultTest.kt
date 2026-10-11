package com.example.parkinson.dualtask

import com.example.parkinson.assessment.QualityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dual-task computations on SYNTHETIC values. Not clinical evidence. */
class DualTaskResultTest {

    @Test
    fun answersFollowFromTheStartAndTheLastNumber() {
        assertEquals(SerialSubtraction.Answers(10, true), SerialSubtraction.answers(98, 68))
        // 98 -> 67 is not reachable by threes: at least one answer was wrong.
        assertEquals(SerialSubtraction.Answers(10, false), SerialSubtraction.answers(98, 67))
        assertNull(SerialSubtraction.answers(98, 99))
    }

    @Test
    fun aSteadyWalkerWhoKeepsCountingScoresHigh() {
        val s = DualTaskScoring.score(walk(110.0, 1.9), walk(104.0, 2.2), answersPerMinute = 24.0, longPauses = 0)
        assertTrue("score=$s", s.overall!! >= 90)
        assertTrue(s.signs.isEmpty())
    }

    @Test
    fun slowingIrregularStepsAndFewAnswersAreSigns() {
        val s = DualTaskScoring.score(walk(105.0, 2.5), walk(80.0, 5.5), answersPerMinute = 8.0, longPauses = 3)
        assertTrue(DualTaskSign.LARGE_GAIT_COST in s.signs)
        assertTrue(DualTaskSign.VARIABILITY_RISES in s.signs)
        assertTrue(DualTaskSign.SLOW_ANSWERS in s.signs)
        assertTrue(DualTaskSign.LONG_PAUSES in s.signs)
        assertTrue(DualTaskSign.PARKINSONIAN_PATTERN in s.signs)
        assertTrue("score=$s", s.overall!! < 50)
    }

    @Test
    fun theCostIsPercentSlower() {
        assertEquals(10.0, DualTaskScoring.costPercent(100.0, 90.0)!!, 1e-9)
        assertNull(DualTaskScoring.costPercent(null, 90.0))
    }

    @Test
    fun theReportedNumberIsPreferredToTheMicrophoneCount() {
        val r = sample()
        // 15 answers in 30 s.
        assertEquals(30.0, r.answersPerMinute!!, 1e-9)
        assertEquals(20.0, r.copy(answers = null).answersPerMinute!!, 1e-9)
        assertNull(r.copy(answers = null, spokenUtterances = null).answersPerMinute)
        assertFalse(r.score == null)
    }

    @Test
    fun spokenBurstsAreMergedAcrossShortGapsAndLongSilencesCounted() {
        // 10 ms frames: "هشتاد" 300 ms, gap 200 ms, "و چهار" 300 ms (one answer), silence 3.5 s, next answer 400 ms.
        val mask = BooleanArray(500)
        fun on(from: Int, to: Int) { for (i in from until to) mask[i] = true }
        on(10, 40); on(60, 90); on(440, 480)
        // A 50 ms click is dropped.
        on(200, 205)
        val a = SpokenAnswerCounter.fromActivity(mask, hopMs = 10.0)
        assertEquals(2, a.utterances)
        assertEquals(1, a.longPauses)
        assertEquals(3_500.0, a.longestPauseMs!!, 1.0)
    }

    companion object {
        fun walk(cadence: Double, cv: Double) = WalkSummary(cadence, 120_000.0 / cadence, cv, 4.0, 80.0)

        fun sample() = DualTaskResult(
            assessmentId = "dual-1",
            timestampEpochMs = 1_700_000_000_000L,
            sessionId = "s",
            protocolId = DualTaskResult.PROTOCOL_ID,
            plannedWalkingMs = 30_000.0,
            single = walk(108.0, 2.0),
            dual = walk(99.0, 2.8),
            singleSteps = 52,
            dualSteps = 48,
            startNumber = 94,
            lastNumber = 49,
            answers = 15,
            answersExact = true,
            spokenUtterances = 10,
            longPauses = 1,
            longestPauseMs = 3_200.0,
            coveragePercent = 98.0,
            qualityStatus = QualityStatus.VALID,
            algorithmVersion = DualTaskResult.ALGORITHM_VERSION,
            configSummary = "fixture",
            scoringVersion = DualTaskScoring.VERSION,
        )
    }
}

