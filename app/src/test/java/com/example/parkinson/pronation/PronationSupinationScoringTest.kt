package com.example.parkinson.pronation

import com.example.parkinson.assessment.PerformanceTrendState
import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.assessment.MotorPerformanceBand
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Score engine, normalization, interpretation bands and interpretation notes. */
class PronationSupinationScoringTest {

    private val engine = PronationSupinationScoreEngine()
    private val context = ReferenceContext(SelectedHand.RIGHT)

    private fun segment(seg: TrendSegment, rate: Double?, amplitude: Double?, cv: Double? = 2.0) = SegmentMetrics(
        segment = seg,
        movementCount = if (rate == null) 0 else 6,
        cycleRateHz = rate,
        medianAmplitudeDeg = amplitude,
        amplitudeCvPercent = cv,
        medianPeakVelocityDegS = amplitude?.let { it * 4 },
        velocityCvPercent = cv,
        medianCycleDurationMs = rate?.let { 1000.0 / it },
        durationCvPercent = cv,
        withinTolerancePercent = if (rate == null) null else 100.0
    )

    private val noTrend = MetricTrend(null, null, null, null, null)

    private fun metrics(
        cps: Double = 1.5,
        cycleCv: Double? = 2.0,
        amplitude: Double? = 90.0,
        amplitudeCv: Double? = 2.0,
        within: Double? = 100.0,
        velocityCv: Double? = 2.0,
        segments: List<SegmentMetrics> = TrendSegment.entries.map { segment(it, cps, amplitude) }
    ) = PronationMetrics(
        movementCount = 30, cycleCount = 15, validCycleCount = 15,
        cyclesPerSecond = cps, cyclesPerMinute = cps * 60,
        meanCycleDurationMs = 1000 / cps, medianCycleDurationMs = 1000 / cps, cycleDurationCvPercent = cycleCv,
        meanAmplitudeDeg = amplitude, medianAmplitudeDeg = amplitude, amplitudeCvPercent = amplitudeCv,
        meanAngularVelocityDegS = 200.0, peakAngularVelocityDegS = 400.0, maxAngularVelocityDegS = 420.0,
        velocityCvPercent = velocityCv, angularVelocityRmsDegS = 250.0,
        pauseCount = 0, totalPauseMs = 0.0, longestPauseMs = 0.0,
        withinTolerancePercent = within, movementCoveragePercent = 95.0, noiseLevelDegS = 1.0,
        accelerationRms = 0.2, accMagnitudeMean = 9.8, dominantFrequencyHz = cps,
        rotationAxisSharePercent = 99.0, axisAlignedWithForearm = true,
        segments = segments,
        measureTrends = MeasureTrends(noTrend, noTrend, noTrend)
    )

    private fun quality(status: QualityStatus) = PronationQualityReport(status, emptyList(), 95, 10_000.0, 100.0)

    // 21
    @Test
    fun scoreLowerBoundIsZero() {
        val worst = metrics(
            cps = 0.0, cycleCv = 500.0, amplitude = 0.0, amplitudeCv = 500.0, within = 0.0, velocityCv = 500.0,
            segments = listOf(
                segment(TrendSegment.EARLY, 3.0, 120.0),
                segment(TrendSegment.MIDDLE, 0.1, 1.0, 400.0),
                segment(TrendSegment.LATE, 0.0, 0.0, 500.0)
            )
        )
        val s = engine.score(worst, quality(QualityStatus.VALID), context)!!
        assertEquals(0, s.total)
        assertEquals(PerformanceTrendState.DECLINING, s.trend.state)
        assertEquals(0, s.components.trend)
    }

    // 22
    @Test
    fun scoreUpperBoundIsHundred() {
        val best = metrics(cps = 10.0, cycleCv = 0.0, amplitude = 300.0, amplitudeCv = 0.0, within = 100.0, velocityCv = 0.0)
        val s = engine.score(best, quality(QualityStatus.VALID), context)!!
        assertEquals(100, s.total)
        listOf(s.components.speed, s.components.rhythm, s.components.amplitude, s.components.consistency, s.components.trend)
            .forEach { assertEquals(100, it) }
    }

    @Test
    fun scoreIsAlwaysWithinZeroAndHundred() {
        val values = listOf(-5.0, 0.0, 0.3, 1.0, 2.5, 50.0)
        for (cps in values) for (cv in listOf(null, 0.0, 30.0, 1e6)) for (amp in listOf(-10.0, 0.0, 45.0, 1e4)) {
            val s = engine.score(metrics(cps = cps, cycleCv = cv, amplitude = amp, amplitudeCv = cv, velocityCv = cv), quality(QualityStatus.VALID), context)!!
            assertTrue("total ${s.total}", s.total in 0..100)
        }
    }

    @Test
    fun normalizationIsMonotonicAndExplainable() {
        assertEquals(0, engine.speedScore(0.3, context))
        assertEquals(100, engine.speedScore(2.5, context))
        assertTrue(engine.speedScore(1.0, context) < engine.speedScore(2.0, context))
        assertEquals(100, engine.rhythmScore(0.0))
        assertEquals(0, engine.rhythmScore(40.0))
        assertTrue(engine.rhythmScore(10.0) > engine.rhythmScore(20.0))
        // Small or unstable amplitude scores lower.
        assertTrue(engine.amplitudeScore(20.0, 2.0, context) < engine.amplitudeScore(80.0, 2.0, context))
        assertTrue(engine.amplitudeScore(80.0, 40.0, context) < engine.amplitudeScore(80.0, 2.0, context))
        assertNull(engine.consistencyScore(null, null))
        assertEquals(100, engine.consistencyScore(100.0, 0.0))
        // Trend: within the tolerated decline = 100, maximum decline = 0.
        assertEquals(100, engine.trendScore(PerformanceTrend(PerformanceTrendState.STABLE, 80, 78, 76, -4)))
        assertEquals(0, engine.trendScore(PerformanceTrend(PerformanceTrendState.DECLINING, 90, 70, 50, -40)))
        assertNull(engine.trendScore(PerformanceTrend(PerformanceTrendState.INSUFFICIENT_DATA, null, null, null, null)))
    }

    @Test
    fun weightsAreCentralizedAndSumToOne() {
        val c = PronationScoringConfig()
        assertEquals(1.0, c.weightSpeed + c.weightRhythm + c.weightAmplitude + c.weightConsistency + c.weightTrend, 1e-9)
        assertEquals(0.25, c.weightSpeed, 0.0)
        assertEquals(0.25, c.weightRhythm, 0.0)
        assertEquals(0.20, c.weightAmplitude, 0.0)
        assertEquals(0.15, c.weightConsistency, 0.0)
        assertEquals(0.15, c.weightTrend, 0.0)
    }

    @Test
    fun missingComponentsAreLeftOutNotCountedAsZero() {
        val s = engine.score(metrics(cycleCv = null, within = null, velocityCv = null), quality(QualityStatus.VALID), context)!!
        assertNull(s.components.rhythm)
        assertNull(s.components.consistency)
        assertTrue(s.total > 50)
    }

    @Test
    fun noScoreForUnusableQualityAndLimitedReliabilityForLowQuality() {
        assertNull(engine.score(metrics(), quality(QualityStatus.INVALID), context))
        assertNull(engine.score(metrics(), quality(QualityStatus.INSUFFICIENT_DATA), context))
        assertEquals(ReliabilityLevel.LIMITED, engine.score(metrics(), quality(QualityStatus.LOW_QUALITY), context)!!.reliability)
        assertEquals(ReliabilityLevel.RELIABLE, engine.score(metrics(), quality(QualityStatus.VALID), context)!!.reliability)
    }

    @Test
    fun trendNeedsEarlyAndLateSegments() {
        val segments = listOf(segment(TrendSegment.EARLY, null, null), segment(TrendSegment.MIDDLE, 1.5, 90.0), segment(TrendSegment.LATE, 1.5, 90.0))
        val trend = engine.trend(segments, context)
        assertEquals(PerformanceTrendState.INSUFFICIENT_DATA, trend.state)
        assertNull(trend.earlyScore)
    }

    // 24 + score boundary tests
    @Test
    fun interpretationBandsHaveExactBoundaries() {
        val expected = mapOf(
            0 to MotorPerformanceBand.VERY_LOW,
            1 to MotorPerformanceBand.VERY_LOW,
            19 to MotorPerformanceBand.VERY_LOW,
            20 to MotorPerformanceBand.SIGNIFICANTLY_REDUCED,
            39 to MotorPerformanceBand.SIGNIFICANTLY_REDUCED,
            40 to MotorPerformanceBand.REDUCED,
            59 to MotorPerformanceBand.REDUCED,
            60 to MotorPerformanceBand.ACCEPTABLE,
            79 to MotorPerformanceBand.ACCEPTABLE,
            80 to MotorPerformanceBand.GOOD,
            100 to MotorPerformanceBand.GOOD
        )
        expected.forEach { (score, band) -> assertEquals("score $score", band, MotorPerformanceBand.forScore(score)) }
    }

    @Test
    fun bandsCoverZeroToHundredWithoutGapsOrOverlaps() {
        for (score in 0..100) {
            assertEquals("score $score", 1, MotorPerformanceBand.entries.count { score in it.min..it.max })
        }
        val sorted = MotorPerformanceBand.entries.sortedBy { it.min }
        assertEquals(0, sorted.first().min)
        assertEquals(100, sorted.last().max)
        sorted.zipWithNext().forEach { (a, b) -> assertEquals(a.max + 1, b.min) }
    }

    @Test(expected = IllegalArgumentException::class)
    fun bandRejectsOutOfRangeScores() {
        MotorPerformanceBand.forScore(101)
    }

    @Test
    fun interpretationClassification() {
        val good = engine.score(metrics(cps = 2.2, amplitude = 90.0), quality(QualityStatus.VALID), context)!!
        val goodNotes = PronationSupinationInterpreter.interpret(good, quality(QualityStatus.VALID), metrics(), accelerometerUsed = true)
        assertEquals(MotorPerformanceBand.forScore(good.total), goodNotes.band)
        assertTrue(InterpretationNote.SPEED_AND_RHYTHM_GOOD in goodNotes.notes)
        assertTrue(InterpretationNote.QUALITY_GOOD in goodNotes.notes)
        assertFalse(InterpretationNote.REPEAT_RECOMMENDED in goodNotes.notes)

        val slow = engine.score(metrics(cps = 0.5, amplitude = 15.0), quality(QualityStatus.VALID), context)!!
        val slowNotes = PronationSupinationInterpreter.interpret(slow, quality(QualityStatus.VALID), metrics(), accelerometerUsed = true)
        assertTrue(InterpretationNote.SPEED_OR_RHYTHM_LOWER in slowNotes.notes)
        assertTrue(InterpretationNote.AMPLITUDE_LOWER in slowNotes.notes)

        val low = engine.score(metrics(), quality(QualityStatus.LOW_QUALITY), context)!!
        val lowNotes = PronationSupinationInterpreter.interpret(low, quality(QualityStatus.LOW_QUALITY), metrics(), accelerometerUsed = false)
        assertEquals(ReliabilityLevel.LIMITED, lowNotes.reliability)
        assertTrue(InterpretationNote.QUALITY_LIMITED in lowNotes.notes)
        assertTrue(InterpretationNote.GYROSCOPE_ONLY in lowNotes.notes)
        assertTrue(InterpretationNote.REPEAT_RECOMMENDED in lowNotes.notes)

        val none = PronationSupinationInterpreter.interpret(null, quality(QualityStatus.INVALID), null, accelerometerUsed = true)
        assertNull(none.band)
        assertEquals(ReliabilityLevel.NOT_RELIABLE, none.reliability)
    }

    @Test
    fun internalReferenceIsNotNormative() {
        assertFalse(InternalSoftwareReference.isNormative)
        assertTrue(InternalSoftwareReference.name.contains("internal"))
    }
}
