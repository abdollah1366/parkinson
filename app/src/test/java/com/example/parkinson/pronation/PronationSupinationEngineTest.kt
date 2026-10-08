package com.example.parkinson.pronation

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** Deterministic synthetic data only: no real-time delays, no randomness without a fixed seed. */
class PronationSupinationEngineTest {

    private val engine = PronationSupinationEngine()

    private fun analyze(s: SyntheticRotation) = engine.analyze(s.recording())

    private fun assertRange(label: String, value: Double, from: Double, to: Double) =
        assertTrue("$label = $value not in [$from, $to]", value in from..to)

    // 1
    @Test
    fun noMovementGivesNoCyclesAndNoScore() {
        val a = analyze(SyntheticRotation())
        assertEquals(0, a.metrics!!.cycleCount)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertEquals(PronationQualityIssue.NO_MOVEMENT_DETECTED, a.quality.primaryIssue)
        assertNull(a.score)
    }

    // 2
    @Test
    fun slowRotationIsCountedWithCorrectCycleDuration() {
        val m = analyze(SyntheticRotation().sinusoid(0.5, 120.0)).metrics!!
        assertRange("cycles", m.cycleCount.toDouble(), 4.0, 5.0)
        assertEquals(2_000.0, m.medianCycleDurationMs!!, 60.0)
        assertEquals(120.0, m.movementAmplitudeDeg!!, 6.0)
    }

    // 3
    @Test
    fun normalRotationGivesExpectedMetricsAndValidScore() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0))
        val m = a.metrics!!
        assertEquals(QualityStatus.VALID, a.quality.status)
        assertRange("cycles", m.cycleCount.toDouble(), 14.0, 15.0)
        assertRange("rate", m.cycleRateHz, 1.35, 1.55)
        assertEquals(666.7, m.medianCycleDurationMs!!, 20.0)
        assertEquals(666.7, m.meanCycleDurationMs!!, 20.0)
        assertEquals(90.0, m.movementAmplitudeDeg!!, 4.0)
        // Peak angular velocity of A sin(2 pi f t): pi * f * pp = 424 deg/s (low-pass at 10 Hz keeps it).
        assertEquals(PI * 1.5 * 90.0, m.angularVelocityPeakDegS!!, 15.0)
        // Mean |w| of a sinusoid = 2/pi x peak.
        assertEquals(2 / PI * PI * 1.5 * 90.0, m.angularVelocityMeanDegS, 15.0)
        assertEquals(1.5, m.dominantFrequencyHz!!, 0.06)
        assertEquals(0, m.pauseCount)
        assertNotNull(a.score)
    }

    // 4
    @Test
    fun fastRotationIsResolved() {
        val m = analyze(SyntheticRotation().sinusoid(4.0, 60.0)).metrics!!
        assertRange("cycles", m.cycleCount.toDouble(), 38.0, 40.0)
        assertEquals(250.0, m.medianCycleDurationMs!!, 10.0)
        assertEquals(60.0, m.movementAmplitudeDeg!!, 5.0)
    }

    // 5
    @Test
    fun smallAmplitudeIsDetectedButBelowMinimumIsNot() {
        val small = analyze(SyntheticRotation().sinusoid(1.5, 20.0)).metrics!!
        assertRange("cycles", small.cycleCount.toDouble(), 14.0, 15.0)
        assertEquals(20.0, small.movementAmplitudeDeg!!, 2.0)

        val tiny = analyze(SyntheticRotation().sinusoid(1.5, 6.0))
        assertEquals(0, tiny.metrics!!.cycleCount)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, tiny.quality.status)
    }

    // 6
    @Test
    fun largeAmplitudeIsMeasured() {
        val m = analyze(SyntheticRotation().sinusoid(1.0, 170.0)).metrics!!
        assertRange("cycles", m.cycleCount.toDouble(), 9.0, 10.0)
        assertEquals(170.0, m.movementAmplitudeDeg!!, 8.0)
    }

    // 7 + 8
    @Test
    fun regularRhythmIsMoreConsistentThanIrregularRhythm() {
        val regular = analyze(SyntheticRotation().sinusoid(1.5, 90.0)).metrics!!
        val irregular = analyze(SyntheticRotation().irregular(1.5, 90.0)).metrics!!
        assertTrue("regular CV ${regular.cycleDurationCvPercent}", regular.cycleDurationCvPercent!! < 3.0)
        assertTrue("regular amplitude CV", regular.amplitudeCvPercent!! < 3.0)
        assertTrue(regular.movementConsistencyPercent!! >= 95.0)

        assertTrue("irregular CV ${irregular.cycleDurationCvPercent}", irregular.cycleDurationCvPercent!! > 10.0)
        assertTrue("irregular amplitude CV", irregular.amplitudeCvPercent!! > 15.0)
        assertTrue(irregular.movementConsistencyPercent!! < regular.movementConsistencyPercent!!)
    }

    // 9
    @Test
    fun movementPauseIsDetectedAndTimed() {
        val m = analyze(SyntheticRotation().sinusoidWithPause(1.5, 90.0, fromS = 4.0, lengthS = 2.0)).metrics!!
        assertEquals(1, m.pauseCount)
        assertEquals(2_000.0, m.pauseDurationMs, 300.0)
        // The movement before and after the pause is still counted (about 8 s of rotation).
        assertRange("cycles", m.cycleCount.toDouble(), 10.0, 13.0)
    }

    // 10
    @Test
    fun sensorNoiseAndTremorDoNotChangeTheCount() {
        val clean = analyze(SyntheticRotation().sinusoid(1.5, 90.0)).metrics!!
        val noisy = analyze(
            SyntheticRotation(gyroNoiseDegS = 15.0, accelNoise = 0.3).profile { t ->
                45.0 * sin(2 * PI * 1.5 * t) + 1.5 * sin(2 * PI * 7.0 * t)
            }
        ).metrics!!
        assertEquals(clean.cycleCount, noisy.cycleCount)
        assertEquals(clean.movementAmplitudeDeg!!, noisy.movementAmplitudeDeg!!, 5.0)
    }

    // 11
    @Test
    fun shortDropoutIsHandledAndFlagged() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0).gap(5_000, 5_300))
        val m = a.metrics!!
        assertTrue(a.gyro.dropoutCount >= 1)
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertTrue(PronationQualityIssue.GAPS_PRESENT in a.quality.issues)
        // Movements spanning the gap are excluded from timing, the rest are kept.
        assertTrue(a.halfCycles.any { !it.valid })
        assertTrue(m.validCycleCount < m.cycleCount)
        assertEquals(666.7, m.medianCycleDurationMs!!, 25.0)
        assertNull("no score unless VALID", a.score)
    }

    @Test
    fun longDropoutInvalidatesTheRecording() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0).gap(3_000, 4_500))
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertTrue(PronationQualityIssue.EXCESSIVE_GAPS in a.quality.issues)
        assertNull(a.score)
    }

    // 12
    @Test
    fun insufficientSamplingRateIsRejected() {
        val tooLow = analyze(SyntheticRotation(accelHz = 20.0, gyroHz = 20.0).sinusoid(1.0, 90.0))
        assertEquals(QualityStatus.INVALID, tooLow.quality.status)
        assertEquals(PronationQualityIssue.SAMPLING_RATE_TOO_LOW, tooLow.quality.primaryIssue)

        val low = analyze(SyntheticRotation(accelHz = 40.0, gyroHz = 40.0).sinusoid(1.0, 90.0))
        assertEquals(QualityStatus.LOW_QUALITY, low.quality.status)
        assertTrue(PronationQualityIssue.LOW_SAMPLING_RATE in low.quality.issues)
        assertEquals(40.0, low.gyro.samplingRateHz, 0.5)
        assertRange("cycles at 40 Hz", low.metrics!!.cycleCount.toDouble(), 9.0, 10.0)
    }

    // 13
    @Test
    fun incompleteRecordingIsRejected() {
        val a = analyze(SyntheticRotation(durationMs = 6_000).sinusoid(1.5, 90.0))
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertTrue(PronationQualityIssue.RECORDING_INCOMPLETE in a.quality.issues)
        assertNull(a.score)
    }

    // 14
    @Test
    fun smallReversalsAndDuplicateEventsDoNotCreateExtraCycles() {
        val clean = analyze(SyntheticRotation().sinusoid(1.5, 90.0)).metrics!!
        // A 6 deg back-and-forth wobble at 5 Hz on top of every movement.
        val wobbly = analyze(
            SyntheticRotation().profile { t -> 45.0 * sin(2 * PI * 1.5 * t) + 3.0 * sin(2 * PI * 5.0 * t) }
        ).metrics!!
        assertEquals(clean.cycleCount, wobbly.cycleCount)

        val dup = analyze(SyntheticRotation().sinusoid(1.5, 90.0).duplicates(5))
        assertEquals(clean.cycleCount, dup.metrics!!.cycleCount)
    }

    // 15
    @Test
    fun qualityRejectionNeverProducesAScore() {
        listOf(
            SyntheticRotation(),
            SyntheticRotation(durationMs = 4_000).sinusoid(1.5, 90.0),
            SyntheticRotation(gyroHz = 15.0).sinusoid(1.0, 90.0)
        ).forEach { s ->
            val a = analyze(s)
            assertFalse(a.quality.status.name, a.quality.status == QualityStatus.VALID)
            assertNull(a.score)
        }
    }

    // 16
    @Test
    fun analysisAndScoreAreReproducible() {
        val first = analyze(SyntheticRotation().irregular(1.2, 100.0))
        val second = analyze(SyntheticRotation().irregular(1.2, 100.0))
        assertEquals(first, second)
        val score = PronationSupinationScorer.score(first.metrics!!, first.quality)
        assertEquals(score, PronationSupinationScorer.score(first.metrics!!, first.quality))
    }

    // 17
    @Test
    fun resultCarriesAlgorithmAndScoringVersions() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0))
        val r = PronationSupinationResult.from(a, "id", 1L, SelectedHand.LEFT, 10_000L)
        assertEquals("ps-algo-1.0.0", r.algorithmVersion)
        assertEquals(PronationSupinationVersions.ALGORITHM_VERSION, r.algorithmVersion)
        assertEquals(PronationSupinationVersions.SCORING_VERSION, r.scoringVersion)
        assertTrue("research, non-diagnostic", r.scoringVersion.endsWith("research"))
        assertEquals(r.scoringVersion, r.performanceScore!!.scoringVersion)
        assertFalse(r.performanceScore!!.isClinicallyValidated)
        // The hand is the explicit user selection.
        assertEquals(SelectedHand.LEFT, r.hand)
        assertEquals(r.performanceScore!!.total, r.performanceIndex)
    }

    @Test
    fun resultDoesNotDependOnHowThePhoneIsHeld() {
        val axes = listOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.5, -0.3, 0.8))
        val metrics = axes.map { analyze(SyntheticRotation(axis = it).sinusoid(1.5, 90.0)).metrics!! }
        metrics.forEach {
            assertEquals(metrics[0].cycleCount, it.cycleCount)
            assertEquals(90.0, it.movementAmplitudeDeg!!, 4.0)
            assertTrue("axis share ${it.rotationAxisSharePercent}", it.rotationAxisSharePercent > 95.0)
        }
    }

    @Test
    fun translationWithoutRotationIsNotCountedAsCycles() {
        val a = analyze(SyntheticRotation().translation(1.5, 3.0))
        assertEquals(0, a.metrics!!.cycleCount)
        assertTrue("linear acceleration seen: ${a.metrics!!.accelerationRms}", a.metrics!!.accelerationRms > 1.5)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
    }

    @Test
    fun linearAccelerationIsSmallForPureRotation() {
        // Gravity is tracked with the gyroscope, so rotating the phone is not seen as translation.
        val m = analyze(SyntheticRotation().sinusoid(1.5, 90.0)).metrics!!
        assertTrue("accelerationRms ${m.accelerationRms}", m.accelerationRms < 0.5)
    }

    @Test
    fun stillPreparationCalibratesGyroscopeBias() {
        val a = analyze(SyntheticRotation(gyroBiasDegS = 2.0).sinusoid(1.5, 90.0))
        assertEquals(BaselineStatus.CALIBRATED, a.baseline.status)
        a.baseline.gyroBiasDegS.forEach { assertEquals(2.0, it, 0.2) }
        assertNotNull(a.baseline.gravity)
        assertEquals(90.0, a.metrics!!.movementAmplitudeDeg!!, 4.0)

        // A phone that moves during preparation is not used as a bias reference.
        val moving = engine.analyze(
            SyntheticRotation().sinusoid(1.5, 90.0).recording().let { rec ->
                rec.copy(baselineSamples = SyntheticRotation(baselineMs = 0).sinusoid(1.0, 60.0).samples())
            }
        )
        assertEquals(BaselineStatus.NOT_STILL, moving.baseline.status)
        moving.baseline.gyroBiasDegS.forEach { assertEquals(0.0, it, 0.0) }
    }

    @Test
    fun missingBaselineIsReportedButDoesNotBlockTheResult() {
        val a = analyze(SyntheticRotation(baselineMs = 0).sinusoid(1.5, 90.0))
        assertEquals(BaselineStatus.NO_DATA, a.baseline.status)
        assertEquals(QualityStatus.VALID, a.quality.status)
    }

    @Test
    fun performanceTrendFollowsDecreasingAmplitude() {
        val decreasing = analyze(
            SyntheticRotation().profile { t -> (75.0 - 4.5 * t) * sin(2 * PI * 1.5 * t) }
        ).trend!!
        assertEquals(TrendDirection.DECREASED, decreasing.amplitudeDeg.direction)
        assertTrue(decreasing.amplitudeDeg.changePercent!! < -40.0)
        assertEquals(TrendDirection.STABLE, decreasing.cycleDurationMs.direction)

        val steady = analyze(SyntheticRotation().sinusoid(1.5, 90.0)).trend!!
        assertEquals(TrendDirection.STABLE, steady.amplitudeDeg.direction)
        assertEquals(TrendDirection.STABLE, steady.peakVelocityDegS.direction)
        assertNotNull(steady.amplitudeDeg.middle)
    }

    @Test
    fun scoreIsHigherForFasterLargerMoreRegularMovement() {
        val good = analyze(SyntheticRotation().sinusoid(2.0, 120.0)).score!!
        val slowSmall = analyze(SyntheticRotation().sinusoid(0.6, 30.0)).score!!
        assertTrue("${good.total} > ${slowSmall.total}", good.total > slowSmall.total)
        assertTrue(good.total in 0..100 && slowSmall.total in 0..100)
    }
}
