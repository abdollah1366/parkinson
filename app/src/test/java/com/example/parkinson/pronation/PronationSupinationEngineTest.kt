package com.example.parkinson.pronation

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * The whole pipeline on deterministic synthetic data: no real-time delays, fixed random seed.
 * Numbered tests follow the required scenario list.
 */
class PronationSupinationEngineTest {

    private val engine = PronationSupinationEngine()

    private fun analyze(s: SyntheticRotation, hand: SelectedHand = SelectedHand.RIGHT) = engine.analyze(s.recording(hand))

    private fun assertRange(label: String, value: Double, from: Double, to: Double) =
        assertTrue("$label = $value not in [$from, $to]", value in from..to)

    private fun assertScoreInRange(a: PronationAnalysis) {
        a.score?.let { s ->
            assertTrue("total ${s.total}", s.total in 0..100)
            listOfNotNull(s.components.speed, s.components.rhythm, s.components.amplitude, s.components.consistency, s.components.trend)
                .forEach { assertTrue("component $it", it in 0..100) }
        }
    }

    // 1
    @Test
    fun noMovementGivesNoCyclesAndNoScore() {
        val a = analyze(SyntheticRotation())
        assertEquals(0, a.metrics!!.cycleCount)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertEquals(PronationQualityIssue.NO_MOVEMENT_DETECTED, a.quality.primaryIssue)
        assertNull(a.score)
        assertEquals(ReliabilityLevel.NOT_RELIABLE, a.interpretation.reliability)
        assertNull(a.interpretation.band)
    }

    // 2
    @Test
    fun oneValidRotationIsMeasuredButIsNotEnoughForAResult() {
        // 1.5 periods: exactly one complete pronation + supination between turning points.
        val a = analyze(SyntheticRotation().profile { t -> if (t in 1.0..2.5) 45.0 * sin(2 * PI * (t - 1.0)) else 0.0 })
        assertEquals(1, a.metrics!!.cycleCount)
        assertEquals(1, a.metrics!!.validCycleCount)
        assertEquals(1_000.0, a.metrics!!.medianCycleDurationMs!!, 40.0)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertNull(a.score)
    }

    // 3
    @Test
    fun regularRotationsHaveHighRegularityAndConsistency() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0))
        val m = a.metrics!!
        assertTrue("CV ${m.cycleDurationCvPercent}", m.cycleDurationCvPercent!! < 3.0)
        assertTrue(m.amplitudeCvPercent!! < 3.0)
        assertTrue(m.withinTolerancePercent!! >= 95.0)
        assertTrue(a.score!!.components.rhythm!! >= 90)
        assertTrue(a.score!!.components.consistency!! >= 90)
        assertTrue(a.cycles.isNotEmpty() && a.cycles.all { it.valid })
    }


    // 4
    @Test
    fun slowRotationIsCountedWithCorrectCycleDuration() {
        val m = analyze(SyntheticRotation().sinusoid(0.5, 120.0)).metrics!!
        assertRange("cycles", m.cycleCount.toDouble(), 4.0, 5.0)
        assertEquals(2_000.0, m.medianCycleDurationMs!!, 60.0)
        assertEquals(120.0, m.medianAmplitudeDeg!!, 6.0)
    }

    // 5
    @Test
    fun normalRotationGivesExpectedMetrics() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0))
        val m = a.metrics!!
        assertEquals(QualityStatus.VALID, a.quality.status)
        assertRange("cycles", m.cycleCount.toDouble(), 14.0, 15.0)
        assertRange("cps", m.cyclesPerSecond, 1.35, 1.55)
        assertEquals(m.cyclesPerSecond * 60.0, m.cyclesPerMinute, 1e-9)
        assertEquals(666.7, m.medianCycleDurationMs!!, 20.0)
        assertEquals(666.7, m.meanCycleDurationMs!!, 20.0)
        assertEquals(90.0, m.medianAmplitudeDeg!!, 4.0)
        assertEquals(90.0, m.meanAmplitudeDeg!!, 4.0)
        // Peak angular velocity of A sin(2 pi f t) = pi * f * peak-to-peak = 424 deg/s.
        assertEquals(PI * 1.5 * 90.0, m.peakAngularVelocityDegS!!, 15.0)
        // Mean |w| of a sinusoid = 2/pi x peak.
        assertEquals(2 / PI * PI * 1.5 * 90.0, m.meanAngularVelocityDegS, 15.0)
        assertEquals(1.5, m.dominantFrequencyHz!!, 0.06)
        assertEquals(0, m.pauseCount)
        assertTrue(m.movementCoveragePercent > 90.0)
        assertNotNull(a.score)
        assertScoreInRange(a)
    }

    // 6
    @Test
    fun fastRotationIsResolved() {
        val m = analyze(SyntheticRotation().sinusoid(4.0, 60.0)).metrics!!
        assertRange("cycles", m.cycleCount.toDouble(), 38.0, 40.0)
        assertEquals(250.0, m.medianCycleDurationMs!!, 10.0)
        assertEquals(60.0, m.medianAmplitudeDeg!!, 5.0)
    }

    // 7
    @Test
    fun smallAmplitudeIsDetectedButBelowMinimumIsNot() {
        val small = analyze(SyntheticRotation().sinusoid(1.5, 20.0)).metrics!!
        assertRange("cycles", small.cycleCount.toDouble(), 14.0, 15.0)
        assertEquals(20.0, small.medianAmplitudeDeg!!, 2.0)

        val tiny = analyze(SyntheticRotation().sinusoid(1.5, 6.0))
        assertEquals(0, tiny.metrics!!.cycleCount)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, tiny.quality.status)
    }

    // 8
    @Test
    fun largeAmplitudeIsMeasured() {
        val a = analyze(SyntheticRotation().sinusoid(1.0, 170.0))
        assertRange("cycles", a.metrics!!.cycleCount.toDouble(), 9.0, 10.0)
        assertEquals(170.0, a.metrics!!.medianAmplitudeDeg!!, 8.0)
        assertEquals(100, a.score!!.components.amplitude)
    }

    // 9
    @Test
    fun noiseAndTremorDoNotChangeTheCount() {
        val clean = analyze(SyntheticRotation().sinusoid(1.5, 90.0)).metrics!!
        val noisy = analyze(
            SyntheticRotation(gyroNoiseDegS = 15.0, accelNoise = 0.3).profile { t ->
                45.0 * sin(2 * PI * 1.5 * t) + 1.5 * sin(2 * PI * 7.0 * t)
            }
        ).metrics!!
        assertEquals(clean.cycleCount, noisy.cycleCount)
        assertEquals(clean.medianAmplitudeDeg!!, noisy.medianAmplitudeDeg!!, 5.0)
        assertTrue("noise estimated: ${noisy.noiseLevelDegS}", noisy.noiseLevelDegS > clean.noiseLevelDegS)
    }

    @Test
    fun randomShakingIsNotCountedAsRotation() {
        // Strong broadband gyroscope noise only (shaking), no deliberate rotation.
        val a = analyze(SyntheticRotation(gyroNoiseDegS = 60.0))
        assertEquals(0, a.metrics!!.cycleCount)
        assertNull(a.score)
    }

    // 10
    @Test
    fun irregularMovementIsLessRegularAndScoresLower() {
        val regular = analyze(SyntheticRotation().sinusoid(1.5, 90.0))
        val irregular = analyze(SyntheticRotation().irregular(1.5, 90.0))
        assertTrue("irregular CV ${irregular.metrics!!.cycleDurationCvPercent}", irregular.metrics!!.cycleDurationCvPercent!! > 10.0)
        assertTrue(irregular.metrics!!.amplitudeCvPercent!! > 15.0)
        assertTrue(irregular.score!!.components.rhythm!! < regular.score!!.components.rhythm!!)
        assertTrue(irregular.score!!.components.consistency!! < regular.score!!.components.consistency!!)
        assertTrue(irregular.score!!.total < regular.score!!.total)
    }

    // 11
    @Test
    fun pausesAreDetectedAndTimed() {
        val m = analyze(SyntheticRotation().sinusoidWithPause(1.5, 90.0, fromS = 4.0, lengthS = 2.0)).metrics!!
        assertEquals(1, m.pauseCount)
        assertEquals(2_000.0, m.totalPauseMs, 300.0)
        assertEquals(m.totalPauseMs, m.longestPauseMs, 1e-9)
        assertRange("cycles", m.cycleCount.toDouble(), 10.0, 13.0)
    }

    // 12
    @Test
    fun shortDropoutIsHandledAndFlagged() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0).gap(5_000, 5_300))
        val m = a.metrics!!
        assertTrue(a.gyro.dropoutCount >= 1)
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertTrue(PronationQualityIssue.GAPS_PRESENT in a.quality.issues)
        assertTrue(a.movements.any { it.rejection == MovementRejection.SENSOR_GAP })
        assertTrue(m.validCycleCount < m.cycleCount)
        assertEquals(666.7, m.medianCycleDurationMs!!, 25.0)
        // LOW_QUALITY: scored, but with limited reliability.
        assertEquals(ReliabilityLevel.LIMITED, a.score!!.reliability)
    }

    @Test
    fun longDropoutInvalidatesTheRecording() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0).gap(3_000, 4_500))
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertTrue(PronationQualityIssue.EXCESSIVE_GAPS in a.quality.issues)
        assertNull(a.score)
    }

    // 13
    @Test
    fun missingSamplesAreCounted() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0).gap(2_000, 2_200))
        val r = PronationSupinationResult.from(a, "id", "s", 1L, SelectedHand.RIGHT, 10_000L)
        assertEquals(20.0, r.estimatedMissingSamples.toDouble(), 2.0)
        assertTrue(r.dropoutCount >= 1)
        assertEquals(200.0, r.longestGapMs.toDouble(), 15.0)
    }

    // 14
    @Test
    fun lowSamplingRateIsRejectedOrFlagged() {
        val tooLow = analyze(SyntheticRotation(accelHz = 20.0, gyroHz = 20.0).sinusoid(1.0, 90.0))
        assertEquals(QualityStatus.INVALID, tooLow.quality.status)
        assertEquals(PronationQualityIssue.SAMPLING_RATE_TOO_LOW, tooLow.quality.primaryIssue)

        val low = analyze(SyntheticRotation(accelHz = 40.0, gyroHz = 40.0).sinusoid(1.0, 90.0))
        assertEquals(QualityStatus.LOW_QUALITY, low.quality.status)
        assertTrue(PronationQualityIssue.LOW_SAMPLING_RATE in low.quality.issues)
        assertEquals(40.0, low.gyro.samplingRateHz, 0.5)
        assertRange("cycles at 40 Hz", low.metrics!!.cycleCount.toDouble(), 9.0, 10.0)
    }

    // 15
    @Test
    fun insufficientDataGivesNoScore() {
        listOf(
            SyntheticRotation().sinusoid(0.1, 90.0), // less than two cycles in 10 s
            SyntheticRotation().profile { t -> if (t < 1.0) 45.0 * sin(2 * PI * 1.5 * t) else 0.0 } // stops after one cycle
        ).forEach { s ->
            val a = analyze(s)
            assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
            assertNull(a.score)
            assertEquals(listOf(InterpretationNote.REPEAT_RECOMMENDED), a.interpretation.notes)
        }
    }

    // 16
    @Test
    fun invalidDataIsRejected() {
        val corrupt = analyze(SyntheticRotation().sinusoid(1.5, 90.0).corruptGyroEvery(5))
        assertEquals(QualityStatus.INVALID, corrupt.quality.status)
        assertTrue(PronationQualityIssue.TOO_MANY_INVALID_SAMPLES in corrupt.quality.issues)
        assertNull(corrupt.score)

        val truncated = analyze(SyntheticRotation(durationMs = 6_000).sinusoid(1.5, 90.0))
        assertEquals(QualityStatus.INVALID, truncated.quality.status)
        assertTrue(PronationQualityIssue.RECORDING_INCOMPLETE in truncated.quality.issues)

        val noGyro = engine.analyze(
            SyntheticRotation().sinusoid(1.5, 90.0).recording().let { rec ->
                rec.copy(samples = rec.samples.filter { it.type == MotionSensorType.ACCELEROMETER })
            }
        )
        assertEquals(QualityStatus.INVALID, noGyro.quality.status)
        assertEquals(PronationQualityIssue.NO_GYROSCOPE_DATA, noGyro.quality.primaryIssue)
        assertNull(noGyro.metrics)
    }

    // 17
    @Test
    fun performanceDeclineIsDetected() {
        // Slows from 2.0 to 1.0 cycles/s while the amplitude shrinks from 60 to 16 degrees.
        val a = analyze(
            SyntheticRotation().profile { t -> (30.0 - 2.2 * t) * sin(2 * PI * (2.0 * t - 0.05 * t * t)) }
        )
        val trend = a.score!!.trend
        assertEquals(PerformanceTrendState.DECLINING, trend.state)
        assertTrue("change ${trend.scoreChange}", trend.scoreChange!! <= -10)
        assertTrue(trend.earlyScore!! > trend.lateScore!!)
        assertTrue(a.score!!.components.trend!! < 100)
        assertEquals(TrendDirection.DECREASED, a.metrics!!.measureTrends.amplitudeDeg.direction)
        assertTrue(InterpretationNote.TREND_DECLINING in a.interpretation.notes)
    }

    // 18
    @Test
    fun steadyMovementHasStableTrend() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0))
        val trend = a.score!!.trend
        assertEquals(PerformanceTrendState.STABLE, trend.state)
        assertNotNull(trend.middleScore)
        assertEquals(100, a.score!!.components.trend)
        assertEquals(TrendDirection.STABLE, a.metrics!!.measureTrends.peakVelocityDegS.direction)
        assertFalse(InterpretationNote.TREND_DECLINING in a.interpretation.notes)
    }

    @Test
    fun improvingMovementIsReported() {
        val a = analyze(
            SyntheticRotation().profile { t -> (8.0 + 2.2 * t) * sin(2 * PI * (1.0 * t + 0.05 * t * t)) }
        )
        assertEquals(PerformanceTrendState.IMPROVING, a.score!!.trend.state)
    }

    // 19 + 20
    @Test
    fun leftAndRightHandGiveTheSameMetricsAndMirroredDirections() {
        val right = analyze(SyntheticRotation().sinusoid(1.5, 90.0), SelectedHand.RIGHT)
        val left = analyze(SyntheticRotation().sinusoid(1.5, 90.0), SelectedHand.LEFT)
        assertEquals(right.metrics!!.copy(segments = emptyList()), left.metrics!!.copy(segments = emptyList()))
        assertEquals(right.score!!.total, left.score!!.total)
        assertTrue(right.metrics!!.axisAlignedWithForearm)
        // The first complete movement turns the angle down: pronation for the right hand (thumb up
        // and over), supination for the mirrored left hand.
        assertEquals(RotationDirection.PRONATION, right.movements.first().direction)
        assertEquals(RotationDirection.SUPINATION, left.movements.first().direction)
        right.movements.zip(left.movements).forEach { (r, l) -> assertTrue(r.direction != l.direction) }

        val rightResult = PronationSupinationResult.from(right, "r", "s1", 1L, SelectedHand.RIGHT, 10_000L)
        val leftResult = PronationSupinationResult.from(left, "l", "s2", 1L, SelectedHand.LEFT, 10_000L)
        assertEquals(SelectedHand.RIGHT, rightResult.hand)
        assertEquals(SelectedHand.LEFT, leftResult.hand)
    }

    @Test
    fun directionIsUnknownWhenTheAxisIsNotTheForearmAxis() {
        val a = analyze(SyntheticRotation(axis = doubleArrayOf(1.0, 0.0, 0.0)).sinusoid(1.5, 90.0))
        assertFalse(a.metrics!!.axisAlignedWithForearm)
        assertTrue(a.movements.all { it.direction == RotationDirection.UNKNOWN })
        assertEquals(90.0, a.metrics!!.medianAmplitudeDeg!!, 4.0)
    }

    // 23
    @Test
    fun qualityClassification() {
        val cases = listOf(
            SyntheticRotation().sinusoid(1.5, 90.0) to QualityStatus.VALID,
            SyntheticRotation().sinusoid(1.5, 90.0).gap(5_000, 5_300) to QualityStatus.LOW_QUALITY,
            SyntheticRotation(accelerometer = false).sinusoid(1.5, 90.0) to QualityStatus.LOW_QUALITY,
            SyntheticRotation(gyroHz = 40.0).sinusoid(1.5, 90.0) to QualityStatus.LOW_QUALITY,
            SyntheticRotation() to QualityStatus.INSUFFICIENT_DATA,
            SyntheticRotation().sinusoid(1.5, 90.0).gap(3_000, 4_500) to QualityStatus.INVALID,
            SyntheticRotation(gyroHz = 15.0).sinusoid(1.0, 90.0) to QualityStatus.INVALID,
            SyntheticRotation(durationMs = 6_000).sinusoid(1.5, 90.0) to QualityStatus.INVALID
        )
        cases.forEachIndexed { i, (s, expected) ->
            val a = analyze(s)
            assertEquals("case $i ${a.quality.issues}", expected, a.quality.status)
            assertTrue(a.quality.qualityPercentage in 0..100)
            if (!a.quality.isUsable) assertNull("case $i must not be scored", a.score)
            assertScoreInRange(a)
        }
    }

    @Test
    fun gyroscopeOnlyIsSupportedAndMarkedInQuality() {
        val a = analyze(SyntheticRotation(accelerometer = false).sinusoid(1.5, 90.0))
        assertFalse(a.accelerometerAvailable)
        assertEquals(PronationQualityIssue.ACCELEROMETER_UNAVAILABLE, a.quality.primaryIssue)
        assertNull(a.metrics!!.accelerationRms)
        assertRange("cycles", a.metrics!!.cycleCount.toDouble(), 14.0, 15.0)
        assertEquals(ReliabilityLevel.LIMITED, a.score!!.reliability)
        assertTrue(InterpretationNote.GYROSCOPE_ONLY in a.interpretation.notes)
    }

    @Test
    fun smallReversalsAndDuplicateEventsDoNotCreateExtraCycles() {
        val clean = analyze(SyntheticRotation().sinusoid(1.5, 90.0)).metrics!!
        val wobbly = analyze(
            SyntheticRotation().profile { t -> 45.0 * sin(2 * PI * 1.5 * t) + 3.0 * sin(2 * PI * 5.0 * t) }
        ).metrics!!
        assertEquals(clean.cycleCount, wobbly.cycleCount)
        val dup = analyze(SyntheticRotation().sinusoid(1.5, 90.0).duplicates(5))
        assertEquals(clean.cycleCount, dup.metrics!!.cycleCount)
    }

    @Test
    fun cyclesDoNotOverlapAndAreOrdered() {
        val a = analyze(SyntheticRotation().irregular(1.5, 90.0))
        a.cycles.zipWithNext().forEach { (x, y) -> assertTrue(x.endS <= y.startS + 1e-9) }
        a.cycles.forEach {
            assertTrue(it.peakS in it.startS..it.endS)
            assertTrue(it.meanAngularVelocityDegS <= it.peakAngularVelocityDegS)
        }
        a.movements.zipWithNext().forEach { (x, y) -> assertEquals(x.endS, y.startS, 1e-9) }
    }

    @Test
    fun resultDoesNotDependOnHowThePhoneIsHeld() {
        val axes = listOf(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 1.0, 0.0), doubleArrayOf(0.5, -0.3, 0.8))
        val metrics = axes.map { analyze(SyntheticRotation(axis = it).sinusoid(1.5, 90.0)).metrics!! }
        metrics.forEach {
            assertEquals(metrics[0].cycleCount, it.cycleCount)
            assertEquals(90.0, it.medianAmplitudeDeg!!, 4.0)
            assertTrue("axis share ${it.rotationAxisSharePercent}", it.rotationAxisSharePercent > 95.0)
        }
    }

    @Test
    fun translationWithoutRotationIsNotCountedAsCycles() {
        val a = analyze(SyntheticRotation().translation(1.5, 3.0))
        assertEquals(0, a.metrics!!.cycleCount)
        assertTrue("linear acceleration: ${a.metrics!!.accelerationRms}", a.metrics!!.accelerationRms!! > 1.5)
    }

    @Test
    fun linearAccelerationIsSmallForPureRotation() {
        val m = analyze(SyntheticRotation().sinusoid(1.5, 90.0)).metrics!!
        assertTrue("accelerationRms ${m.accelerationRms}", m.accelerationRms!! < 0.5)
    }

    @Test
    fun stillPreparationCalibratesBiasAndNoise() {
        val a = analyze(SyntheticRotation(gyroBiasDegS = 2.0, gyroNoiseDegS = 0.5).sinusoid(1.5, 90.0))
        assertEquals(BaselineStatus.CALIBRATED, a.baseline.status)
        a.baseline.gyroBiasDegS.forEach { assertEquals(2.0, it, 0.2) }
        // Noise of three axes with SD 0.5 each: RMS norm ~ 0.87 deg/s.
        assertEquals(0.87, a.baseline.noiseDegS!!, 0.15)
        assertNotNull(a.baseline.gravity)
        assertEquals(90.0, a.metrics!!.medianAmplitudeDeg!!, 4.0)

        val moving = engine.analyze(
            SyntheticRotation().sinusoid(1.5, 90.0).recording().let { rec ->
                rec.copy(baselineSamples = SyntheticRotation(baselineMs = 0).sinusoid(1.0, 60.0).samples())
            }
        )
        assertEquals(BaselineStatus.NOT_STILL, moving.baseline.status)
        moving.baseline.gyroBiasDegS.forEach { assertEquals(0.0, it, 0.0) }
    }

    @Test
    fun velocityTraceIsStoredAtTheChartRate() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0))
        assertEquals(200.0, a.velocityTrace.size.toDouble(), 2.0)
        assertEquals(PI * 1.5 * 90.0, a.velocityTrace.maxOf { kotlin.math.abs(it) }.toDouble(), 30.0)
    }

    @Test
    fun analysisAndScoreAreReproducible() {
        val first = analyze(SyntheticRotation().irregular(1.2, 100.0))
        val second = analyze(SyntheticRotation().irregular(1.2, 100.0))
        assertEquals(first, second)
    }

    @Test
    fun resultCarriesVersionsAndIsNeverClinicallyValidated() {
        val a = analyze(SyntheticRotation().sinusoid(1.5, 90.0))
        val r = PronationSupinationResult.from(a, "id", "session", 1L, SelectedHand.LEFT, 10_000L)
        assertEquals("1.0", r.algorithmVersion)
        assertEquals("1.0", r.scoringVersion)
        assertEquals(PronationSupinationVersions.ALGORITHM_VERSION, r.algorithmVersion)
        assertEquals(PronationSupinationVersions.SCORING_VERSION, a.score!!.scoringVersion)
        assertFalse(a.score!!.isClinicallyValidated)
        assertFalse(InternalSoftwareReference.isNormative)
        assertEquals(InternalSoftwareReference.name, r.referenceName)
        assertEquals("session", r.sessionId)
        assertEquals(r.performanceScore, r.performanceIndex)
        assertEquals(r.qualityPercentage, r.quality.score)
    }
}
