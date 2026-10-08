package com.example.parkinson.tapping.scoring

import com.example.parkinson.tapping.FingerTappingAnalyzer
import com.example.parkinson.tapping.SyntheticTapping
import com.example.parkinson.assessment.QualityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceScorerTest {

    private val analysis = FingerTappingAnalyzer().analyze(SyntheticTapping().regular(3.0).recording())
    private val metrics = analysis.metrics
    private val quality = analysis.quality

    @Test
    fun validRecordingIsScored() {
        assertEquals(QualityStatus.VALID, quality.status)
        assertNotNull(PerformanceScorer.score(metrics, quality))
    }

    // 24
    @Test
    fun nonValidQualityIsNeverScored() {
        for (status in QualityStatus.entries.filter { it != QualityStatus.VALID }) {
            assertNull(status.name, PerformanceScorer.score(metrics, quality.copy(status = status)))
        }
    }

    @Test
    fun componentsFollowTheDocumentedLinearMapping() {
        val config = ScoringConfig()
        val m = metrics.copy(
            tapRateHz = 2.5,
            intervalCvPercent = 10.0,
            meanAmplitude = 0.8,
            movementConsistencyPercent = 85.0,
            amplitudeTrend = metrics.amplitudeTrend.copy(relativeChangePercent = -20.0)
        )
        val s = PerformanceScorer.score(m, quality.copy(qualityScore = 90), config)!!
        assertEquals(50, s.rate)          // 2.5 / 5.0
        assertEquals(80, s.rhythm)        // 1 - 10 / 50
        assertEquals(80, s.amplitude)     // 0.8 / 1.0
        assertEquals(60, s.amplitudeTrend) // 1 - 20 / 50
        assertEquals(85, s.consistency)
        assertEquals(90, s.dataQuality)
        val expected = 50 * 0.30 + 80 * 0.20 + 80 * 0.20 + 60 * 0.15 + 85 * 0.10 + 90 * 0.05
        assertEquals(Math.round(expected).toInt(), s.total)
    }

    @Test
    fun amplitudeIncreaseIsNotRewardedAboveNoChange() {
        val up = metrics.copy(amplitudeTrend = metrics.amplitudeTrend.copy(relativeChangePercent = 30.0))
        assertEquals(100, PerformanceScorer.score(up, quality)!!.amplitudeTrend)
    }

    @Test
    fun missingTrendIsExcludedAndWeightsRenormalized() {
        val m = metrics.copy(amplitudeTrend = metrics.amplitudeTrend.copy(relativeChangePercent = null))
        val s = PerformanceScorer.score(m, quality)!!
        assertNull(s.amplitudeTrend)
        val c = ScoringConfig()
        val weighted = s.rate * c.weightRate + s.rhythm * c.weightRhythm + s.amplitude * c.weightAmplitude +
            s.consistency * c.weightConsistency + s.dataQuality * c.weightDataQuality
        val weights = c.weightRate + c.weightRhythm + c.weightAmplitude + c.weightConsistency + c.weightDataQuality
        assertEquals(Math.round(weighted / weights).toInt(), s.total)
    }

    @Test
    fun valuesBeyondAnchorsAreClamped() {
        val m = metrics.copy(tapRateHz = 9.0, meanAmplitude = 3.0, intervalCvPercent = 80.0)
        val s = PerformanceScorer.score(m, quality)!!
        assertEquals(100, s.rate)
        assertEquals(100, s.amplitude)
        assertEquals(0, s.rhythm)
        assertTrue(s.total in 0..100)
    }

    // 25
    @Test
    fun scoringIsReproducible() {
        val first = PerformanceScorer.score(metrics, quality)
        repeat(5) { assertEquals(first, PerformanceScorer.score(metrics, quality)) }
    }

    @Test
    fun scoreIsLabelledPreliminary() {
        val s = PerformanceScorer.score(metrics, quality)!!
        assertTrue(s.scoringVersion.contains("preliminary"))
        assertEquals(false, s.isClinicallyValidated)
    }
}
