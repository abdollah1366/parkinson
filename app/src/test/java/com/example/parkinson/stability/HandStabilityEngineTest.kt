package com.example.parkinson.stability

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class HandStabilityEngineTest {

    private val engine = HandStabilityEngine()

    private fun analyze(motion: SyntheticMotion) = engine.analyze(motion.recording())

    @Test
    fun stablePhoneIsValidWithHighIndexAndNoFrequency() {
        val a = analyze(SyntheticMotion())
        assertEquals(QualityStatus.VALID, a.quality.status)
        assertTrue(a.quality.issues.isEmpty())
        val m = a.metrics!!
        assertEquals(9.81, m.accMagnitudeMean, 0.05)
        assertTrue("accDyn=${m.accDynamicRms}", m.accDynamicRms < 0.03)
        assertTrue("gyroDyn=${m.gyroDynamicRms}", m.gyroDynamicRms < 0.3)
        assertNull(m.dominantFrequencyHz)
        assertEquals(FrequencyStatus.MOVEMENT_TOO_SMALL, m.frequencyStatus)
        assertTrue("index=${a.index}", a.index!!.total >= 90)
        assertTrue(a.quality.qualityScore >= 95)
    }

    @Test
    fun measuredSamplingRateIsUsedNotAssumed() {
        val a = analyze(SyntheticMotion(accelHz = 97.3, gyroHz = 203.0))
        assertEquals(97.3, a.accel.samplingRateHz, 0.2)
        assertEquals(203.0, a.gyro.samplingRateHz, 0.5)
        assertEquals(1000 / 97.3, a.accel.medianIntervalMs, 0.05)
        assertEquals(0, a.accel.dropoutCount)
    }

    @Test
    fun slowMovementGivesRotationRangeButNoTremorFrequency() {
        val a = analyze(SyntheticMotion().sway(hz = 0.5, gyroAmpDegS = 10.0))
        val m = a.metrics!!
        // 10 deg/s at 0.5 Hz -> angle amplitude 3.2 deg -> about 6.4 deg peak to peak.
        assertEquals(6.4, m.rotationRangeDeg, 1.0)
        assertNull(m.dominantFrequencyHz)
        assertEquals(QualityStatus.VALID, a.quality.status)
    }

    @Test
    fun fastOscillationReportsItsFrequency() {
        val a = analyze(SyntheticMotion().oscillation(hz = 5.0, gyroAmpDegS = 20.0, linearAmp = 0.5))
        val m = a.metrics!!
        assertEquals(FrequencyStatus.REPORTED, m.frequencyStatus)
        assertEquals(5.0, m.dominantFrequencyHz!!, 0.2)
        assertTrue("band=${m.oscillationBandPowerPercent}", m.oscillationBandPowerPercent!! > 80)
        // 20 deg/s amplitude sine: RMS about 14 deg/s.
        assertEquals(14.1, m.gyroDynamicRms, 1.5)
        assertEquals(0.35, m.accDynamicRms, 0.05)
    }

    @Test
    fun otherFrequenciesAreResolved() {
        listOf(3.5, 8.0, 11.0).forEach { hz ->
            val m = analyze(SyntheticMotion().oscillation(hz = hz, gyroAmpDegS = 15.0)).metrics!!
            assertEquals("hz=$hz", hz, m.dominantFrequencyHz!!, 0.2)
        }
    }

    @Test
    fun indexDecreasesAsMovementIncreases() {
        val stable = analyze(SyntheticMotion()).index!!.total
        val slow = analyze(SyntheticMotion().sway(0.5, 10.0)).index!!.total
        val small = analyze(SyntheticMotion().oscillation(5.0, 3.0, 0.05)).index!!.total
        val large = analyze(SyntheticMotion().oscillation(5.0, 40.0, 1.0)).index!!.total
        assertTrue("stable=$stable small=$small", stable > small)
        assertTrue("small=$small large=$large", small > large)
        assertTrue("stable=$stable slow=$slow", stable > slow)
        assertTrue(large in 0..100 && stable in 0..100)
    }

    @Test
    fun broadbandNoiseHasNoClearPeak() {
        val a = analyze(SyntheticMotion(gyroNoiseDegS = 4.0, accelNoise = 0.2))
        val m = a.metrics!!
        assertTrue(m.gyroDynamicRms > HandStabilityEngine.MIN_OSCILLATION_RMS_DEG_S)
        assertEquals(FrequencyStatus.NO_CLEAR_PEAK, m.frequencyStatus)
        assertNull(m.dominantFrequencyHz)
    }

    @Test
    fun shortDropoutIsLowQualityAndHasNoIndex() {
        val a = analyze(SyntheticMotion().gap(5_000, 5_400))
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertTrue(StabilityQualityIssue.GAPS_PRESENT in a.quality.issues)
        assertEquals(1, a.accel.dropoutCount)
        assertEquals(1, a.gyro.dropoutCount)
        assertEquals(410.0, a.accel.longestGapMs, 1.0)
        assertNull(a.index)
        assertNotNull(a.metrics)
        assertTrue(a.quality.isUsable)
    }

    @Test
    fun longSensorGapIsInvalid() {
        val a = analyze(SyntheticMotion().gap(3_000, 4_500))
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertEquals(StabilityQualityIssue.EXCESSIVE_GAPS, a.quality.primaryIssue)
        assertFalse(a.quality.isUsable)
    }

    @Test
    fun lowSamplingRateIsLowQualityWithoutFrequency() {
        val a = analyze(SyntheticMotion(accelHz = 25.0, gyroHz = 25.0).oscillation(5.0, 20.0))
        assertEquals(25.0, a.gyro.samplingRateHz, 0.1)
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertTrue(StabilityQualityIssue.LOW_SAMPLING_RATE in a.quality.issues)
        assertEquals(FrequencyStatus.SAMPLING_TOO_LOW, a.metrics!!.frequencyStatus)
        assertNull(a.metrics!!.dominantFrequencyHz)
    }

    @Test
    fun veryLowSamplingRateIsInvalid() {
        val a = analyze(SyntheticMotion(accelHz = 8.0, gyroHz = 8.0))
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertEquals(StabilityQualityIssue.SAMPLING_RATE_TOO_LOW, a.quality.primaryIssue)
        assertTrue(StabilityQualityIssue.TOO_FEW_SAMPLES in a.quality.issues)
    }

    @Test
    fun turningThePhoneIsFlagged() {
        val a = analyze(SyntheticMotion().tilt(60.0))
        assertEquals(60.0, a.metrics!!.tiltChangeDeg, 3.0)
        assertTrue(StabilityQualityIssue.PHONE_REORIENTED in a.quality.issues)
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertNull(a.index)
    }

    @Test
    fun smallTiltIsNotFlagged() {
        val a = analyze(SyntheticMotion().tilt(10.0))
        assertFalse(StabilityQualityIssue.PHONE_REORIENTED in a.quality.issues)
    }

    @Test
    fun interruptedShortRecordingIsInvalid() {
        val a = analyze(SyntheticMotion(durationMs = 3_000))
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertTrue(StabilityQualityIssue.RECORDING_INCOMPLETE in a.quality.issues)
        assertEquals(20.0, a.accel.completenessPercent, 1.0)
    }

    @Test
    fun insufficientSamplesWithoutOtherProblems() {
        // 16 Hz over 15 s = 240 samples per stream passes; every 20th sample only does not.
        val ok = analyze(SyntheticMotion(accelHz = 16.0, gyroHz = 16.0))
        assertFalse(StabilityQualityIssue.TOO_FEW_SAMPLES in ok.quality.issues)
        val few = engine.analyze(
            StabilityRecording(SyntheticMotion().samples().filter { (it.timestampNs / 10_000_000) % 20 == 0L }, 15_000L, SelectedHand.LEFT)
        )
        assertTrue(StabilityQualityIssue.TOO_FEW_SAMPLES in few.quality.issues)
        assertFalse(few.quality.isUsable)
    }

    @Test
    fun missingGyroscopeIsInvalidWithoutMetrics() {
        val samples = SyntheticMotion().samples().filter { it.type == MotionSensorType.ACCELEROMETER }
        val a = engine.analyze(StabilityRecording(samples, 15_000L, SelectedHand.RIGHT))
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertTrue(StabilityQualityIssue.NO_GYROSCOPE_DATA in a.quality.issues)
        assertNull(a.metrics)
        assertNull(a.index)
        assertEquals(0, a.quality.qualityScore)
    }

    @Test
    fun emptyRecordingDoesNotCrash() {
        val a = engine.analyze(StabilityRecording(emptyList(), 15_000L, SelectedHand.RIGHT))
        assertEquals(QualityStatus.INVALID, a.quality.status)
        assertNull(a.metrics)
        assertEquals(0.0, a.analyzedDurationMs, 0.0)
    }

    @Test
    fun manyCorruptSamplesAreInvalid() {
        val a = analyze(SyntheticMotion().corruptEvery(5))
        assertEquals(80.0, a.accel.validPercent, 0.5)
        assertEquals(StabilityQualityIssue.TOO_MANY_INVALID_SAMPLES, a.quality.primaryIssue)
    }

    @Test
    fun fewCorruptSamplesAreDroppedAndFlagged() {
        val a = analyze(SyntheticMotion().corruptEvery(50))
        assertTrue(StabilityQualityIssue.INVALID_SAMPLES_PRESENT in a.quality.issues)
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertTrue(a.metrics!!.accMagnitudeMean.isFinite())
    }

    @Test
    fun arrivalOrderDoesNotChangeTheResult() {
        val samples = SyntheticMotion().oscillation(6.0, 10.0).samples()
        val a = engine.analyze(StabilityRecording(samples, 15_000L, SelectedHand.RIGHT))
        val b = engine.analyze(StabilityRecording(samples.shuffled(Random(3)), 15_000L, SelectedHand.RIGHT))
        assertEquals(a, b)
    }

    @Test
    fun duplicateTimestampsAreDroppedAndCountedAsInvalid() {
        val samples = SyntheticMotion().oscillation(6.0, 10.0).samples()
        val a = engine.analyze(StabilityRecording(samples, 15_000L, SelectedHand.RIGHT))
        val b = engine.analyze(StabilityRecording(samples + samples.take(60), 15_000L, SelectedHand.RIGHT))
        // Same measurements; the duplicate deliveries only lower the valid-sample share.
        assertEquals(a.metrics, b.metrics)
        assertEquals(1_500, b.accel.validSamples)
        assertTrue(b.accel.validPercent < 100.0)
        assertTrue(StabilityQualityIssue.INVALID_SAMPLES_PRESENT in b.quality.issues)
    }

    @Test
    fun analysisIsDeterministic() {
        val rec = SyntheticMotion().oscillation(4.0, 12.0, 0.3).recording()
        assertEquals(engine.analyze(rec), engine.analyze(rec))
    }

    @Test
    fun streamsUseOneSynchronizedWindow() {
        val samples = SyntheticMotion().samples()
        // Gyroscope starts 300 ms late.
        val late = samples.filterNot { it.type == MotionSensorType.GYROSCOPE && it.timestampNs < 1_300_000_000L }
        val a = engine.analyze(StabilityRecording(late, 15_000L, SelectedHand.RIGHT))
        assertEquals(14_690.0, a.analyzedDurationMs, 15.0)
        assertFalse(StabilityQualityIssue.STREAMS_MISALIGNED in a.quality.issues)
    }

    @Test
    fun indexComponentsFollowTheReferenceValues() {
        val still = engine.score(metricsWith(gyro = HandStabilityEngine.GYRO_REF_STILL_DEG_S, acc = HandStabilityEngine.ACC_REF_STILL))
        assertEquals(100, still.total)
        val moving = engine.score(metricsWith(gyro = HandStabilityEngine.GYRO_REF_MOVING_DEG_S, acc = HandStabilityEngine.ACC_REF_MOVING))
        assertEquals(0, moving.total)
        val beyond = engine.score(metricsWith(gyro = 500.0, acc = 50.0))
        assertEquals(0, beyond.total)
        val middle = engine.score(metricsWith(gyro = 3.87, acc = 0.173)) // geometric means
        assertEquals(50, middle.rotationComponent)
        assertEquals(50, middle.accelerationComponent)
        assertEquals(HandStabilityVersions.SCORING_VERSION, middle.scoringVersion)
        assertTrue(middle.scoringVersion.endsWith("preliminary"))
    }

    private fun metricsWith(gyro: Double, acc: Double) = StabilityMetrics(
        accMagnitudeMean = 9.81, accMagnitudeSd = 0.0, accMagnitudeVariance = 0.0, accMagnitudeRange = 0.0,
        accDynamicRms = acc, accDynamicVariance = 0.0,
        gyroMagnitudeMean = 0.0, gyroMagnitudeRms = 0.0, gyroMagnitudeSd = 0.0, gyroMagnitudeVariance = 0.0,
        gyroMagnitudeMax = 0.0, gyroDynamicRms = gyro, rotationRangeDeg = 0.0, tiltChangeDeg = 0.0,
        dominantFrequencyHz = null, oscillationBandPowerPercent = null, frequencyStatus = FrequencyStatus.MOVEMENT_TOO_SMALL
    )
}

