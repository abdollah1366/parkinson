package com.example.parkinson.tapping.scoring

import com.example.parkinson.assessment.MotorPerformanceBand
import com.example.parkinson.assessment.PerformanceTrendState
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.tapping.FingerTappingAnalyzer
import com.example.parkinson.tapping.SyntheticTapping
import com.example.parkinson.tapping.metrics.TapSegmentMetrics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FingerTappingScoreEngineTest {

    private val engine = FingerTappingScoreEngine()
    private val analysis = FingerTappingAnalyzer().analyze(SyntheticTapping().regular(3.0).recording())
    private val metrics = analysis.metrics
    private val quality = analysis.quality

    private fun segment(i: Int, rate: Double, amplitude: Double, taps: Int = 10) =
        TapSegmentMetrics(i, taps, rate, amplitude, 2.0, 2.0)

    // 19
    @Test
    fun validRecordingIsScoredReliably() {
        assertEquals(QualityStatus.VALID, quality.status)
        val s = engine.score(metrics, quality)!!
        assertEquals(ReliabilityLevel.RELIABLE, s.reliability)
        assertTrue(s.total in 0..100)
        assertEquals(analysis.score, s)
        assertFalse(s.isClinicallyValidated)
        assertEquals("1.0", s.scoringVersion)
    }

    @Test
    fun lowQualityIsScoredWithLimitedReliabilityAndUnusableIsNever() {
        assertEquals(ReliabilityLevel.LIMITED, engine.score(metrics, quality.copy(status = QualityStatus.LOW_QUALITY))!!.reliability)
        assertNull(engine.score(metrics, quality.copy(status = QualityStatus.INSUFFICIENT_DATA)))
        assertNull(engine.score(metrics, quality.copy(status = QualityStatus.INVALID)))
    }

    @Test
    fun weightsAreCentralizedAndSumToOne() {
        val c = ScoringConfig()
        assertEquals(1.0, c.weightSpeed + c.weightRegularity + c.weightAmplitude + c.weightConsistency + c.weightTrend, 1e-9)
        assertEquals(0.30, c.weightSpeed, 0.0)
        assertEquals(0.25, c.weightRegularity, 0.0)
        assertEquals(0.20, c.weightAmplitude, 0.0)
        assertEquals(0.15, c.weightConsistency, 0.0)
        assertEquals(0.10, c.weightTrend, 0.0)
    }

    @Test
    fun normalizationIsMonotonic() {
        assertEquals(0, engine.speedScore(0.5))
        assertEquals(100, engine.speedScore(5.0))
        assertTrue(engine.speedScore(2.0) < engine.speedScore(3.0))
        assertEquals(100, engine.regularityScore(0.0))
        assertEquals(0, engine.regularityScore(50.0))
        assertEquals(0, engine.amplitudeScore(0.15))
        assertEquals(100, engine.amplitudeScore(1.0))
    }

    // 20
    @Test
    fun scoreBoundaries() {
        val best = metrics.copy(
            tapRateHz = 10.0, intervalCvPercent = 0.0, meanAmplitude = 2.0, movementConsistencyPercent = 100.0,
            segments = listOf(segment(0, 6.0, 1.5), segment(1, 6.0, 1.5), segment(2, 6.0, 1.5))
        )
        assertEquals(100, engine.score(best, quality)!!.total)
        val worst = metrics.copy(
            tapRateHz = 0.0, intervalCvPercent = 500.0, meanAmplitude = 0.0, movementConsistencyPercent = 0.0,
            segments = listOf(segment(0, 6.0, 1.5), segment(1, 0.0, 0.0), segment(2, 0.0, 0.0))
        )
        val s = engine.score(worst, quality)!!
        assertEquals(0, s.total)
        assertEquals(PerformanceTrendState.DECLINING, s.trendState)
        for (rate in listOf(-1.0, 0.0, 2.0, 100.0)) for (cv in listOf(null, 0.0, 30.0, 1e6)) {
            val t = engine.score(metrics.copy(tapRateHz = rate, intervalCvPercent = cv), quality)!!.total
            assertTrue("total $t", t in 0..100)
        }
    }

    // 17 + 18
    @Test
    fun trendStates() {
        val stable = engine.trend(listOf(segment(0, 3.0, 0.9), segment(1, 3.0, 0.9), segment(2, 3.0, 0.9)))
        assertEquals(PerformanceTrendState.STABLE, stable.state)
        assertEquals(100, engine.trendScore(stable))
        val declining = engine.trend(listOf(segment(0, 4.0, 1.0), segment(1, 3.0, 0.7), segment(2, 1.5, 0.4)))
        assertEquals(PerformanceTrendState.DECLINING, declining.state)
        assertTrue(engine.trendScore(declining)!! < 100)
        val improving = engine.trend(listOf(segment(0, 1.5, 0.4), segment(1, 3.0, 0.7), segment(2, 4.0, 1.0)))
        assertEquals(PerformanceTrendState.IMPROVING, improving.state)
        val missing = engine.trend(listOf(segment(0, 3.0, 0.9, taps = 1), segment(1, 3.0, 0.9), segment(2, 3.0, 0.9)))
        assertEquals(PerformanceTrendState.INSUFFICIENT_DATA, missing.state)
        assertNull(engine.trendScore(missing))
    }

    @Test
    fun bandsMatchTheIndex() {
        val s = engine.score(metrics, quality)!!
        assertEquals(MotorPerformanceBand.forScore(s.total), s.band)
    }

    @Test
    fun interpretationIsQualityAware() {
        val s = engine.score(metrics, quality)!!
        val good = FingerTappingInterpreter.interpret(s, quality)
        assertEquals(ReliabilityLevel.RELIABLE, good.reliability)
        assertTrue(TappingNote.QUALITY_GOOD in good.notes)
        assertFalse(TappingNote.LIGHTING_WARNING in good.notes)

        val dim = quality.copy(cameraQuality = CameraQuality.WARNING)
        val dimNotes = FingerTappingInterpreter.interpret(s, dim)
        assertTrue(TappingNote.LIGHTING_WARNING in dimNotes.notes)
        assertNotNull(dimNotes.band)

        val slow = engine.score(metrics.copy(tapRateHz = 0.8), quality)!!
        assertTrue(TappingNote.SPEED_OR_RHYTHM_LOWER in FingerTappingInterpreter.interpret(slow, quality).notes)

        val none = FingerTappingInterpreter.interpret(null, quality.copy(status = QualityStatus.INSUFFICIENT_DATA))
        assertNull(none.band)
        assertEquals(ReliabilityLevel.NOT_RELIABLE, none.reliability)
        assertEquals(listOf(TappingNote.REPEAT_RECOMMENDED), none.notes)
    }
}
