package com.example.parkinson.tremor

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Phone-motion analysis on SYNTHETIC samples (test input only, never a patient measurement). */
class RestingTremorImuEngineTest {

    private val engine = RestingTremorImuEngine()
    private val planned = 15_000L

    private fun samples(
        type: MotionSensorType,
        rateHz: Double,
        durationS: Double,
        value: (Double) -> Triple<Double, Double, Double>,
        startNs: Long = 1_000_000_000L,
    ): List<MotionSample> {
        val n = (durationS * rateHz).toInt()
        return List(n) { i ->
            val t = i / rateHz
            val (x, y, z) = value(t)
            MotionSample(
                type = type,
                timestampNs = startNs + (i * 1e9 / rateHz).toLong(),
                x = x.toFloat(), y = y.toFloat(), z = z.toFloat(),
            )
        }
    }

    /** Accelerometer: gravity 9.81 m/s^2 on z plus a 5 Hz component of 0.3 m/s^2 amplitude. */
    private fun accelTremor(rateHz: Double = 100.0, durationS: Double = 15.0) = samples(
        MotionSensorType.ACCELEROMETER, rateHz, durationS,
        { t -> Triple(0.0, 0.0, 9.81 + 0.3 * sin(2 * PI * 5.0 * t)) },
    )

    /** Gyroscope: 5 Hz angular velocity of 0.05 rad/s amplitude about x. */
    private fun gyroTremor(rateHz: Double = 100.0, durationS: Double = 15.0) = samples(
        MotionSensorType.GYROSCOPE, rateHz, durationS,
        { t -> Triple(0.05 * sin(2 * PI * 5.0 * t), 0.0, 0.0) },
    )

    @Test
    fun knownFiveHzPhoneMotionGivesTheKnownFrequencyAndUnitsInSiAndRadPerSecond() {
        val analysis = engine.analyze(accelTremor(), gyroTremor(), planned)
        assertTrue(analysis.quality.isUsable)
        assertEquals(QualityStatus.VALID, analysis.quality.status)
        val m = requireNotNull(analysis.metrics)

        // 0.3 m/s^2 sinusoid: RMS = 0.3 / sqrt(2) m/s^2.
        assertEquals(0.3 / Math.sqrt(2.0), m.accelRmsMs2, 0.01)
        // Gyroscope magnitude is |0.05 sin|: RMS of its detrended form is positive and below the amplitude.
        val gyroRms = requireNotNull(m.gyroRmsRadS)
        assertTrue("gyro RMS $gyroRms rad/s", gyroRms in 0.01..0.05)
        assertEquals(5.0, requireNotNull(m.dominantFrequencyHz), 0.15)
        assertTrue(requireNotNull(m.spectralProminence) >= 6.0)
        assertEquals(100.0, m.accelRateHz, 0.5)
        assertEquals(0, m.accelDropouts)
        assertEquals(1_500, m.accelSamples)
    }

    @Test
    fun repeatedAnalysisOfTheSameSamplesIsIdentical() {
        val a = engine.analyze(accelTremor(), gyroTremor(), planned)
        val b = engine.analyze(accelTremor(), gyroTremor(), planned)
        assertEquals(a, b)
    }

    @Test
    fun missingGyroscopeIsReportedAndTheAccelerometerFeaturesStillCount() {
        val analysis = engine.analyze(accelTremor(), emptyList(), planned)
        assertEquals(QualityStatus.LOW_QUALITY, analysis.quality.status)
        assertTrue(ImuTremorQualityIssue.GYROSCOPE_UNUSABLE in analysis.quality.issues)
        val m = requireNotNull(analysis.metrics)
        // Not measured: null, never a zero standing in for the gyroscope.
        assertNull(m.gyroRmsRadS)
        assertNull(m.gyroSamples)
        assertEquals(5.0, requireNotNull(m.dominantFrequencyHz), 0.15)
    }

    @Test
    fun noAccelerometerDataIsInsufficientWithNoMetrics() {
        val analysis = engine.analyze(emptyList(), gyroTremor(), planned)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertTrue(ImuTremorQualityIssue.TOO_FEW_SAMPLES in analysis.quality.issues)
        assertNull(analysis.metrics)
        assertFalse(analysis.quality.isUsable)
    }

    @Test
    fun nonFiniteAndImplausibleSamplesAreDroppedAndCounted() {
        val good = accelTremor(durationS = 15.0)
        val corrupt = List(200) { i ->
            MotionSample(
                MotionSensorType.ACCELEROMETER,
                timestampNs = 1_000_000_000L + (i * 1e9 / 100.0).toLong() + 5_000_000L,
                x = if (i % 2 == 0) Float.NaN else 1e9f,
                y = Float.POSITIVE_INFINITY, z = 0f,
            )
        }
        val analysis = engine.analyze(good + corrupt, gyroTremor(), planned)
        val m = requireNotNull(analysis.metrics)
        assertEquals(1_500, m.accelSamples)
        assertTrue(ImuTremorQualityIssue.SAMPLES_DROPPED in analysis.quality.issues)
        assertEquals(QualityStatus.LOW_QUALITY, analysis.quality.status)
        // The corrupt values never reach the features.
        assertEquals(0.3 / Math.sqrt(2.0), m.accelRmsMs2, 0.01)
    }

    @Test
    fun duplicateAndOutOfOrderTimestampsAreNotDoubleCounted() {
        val base = accelTremor(durationS = 15.0)
        val duplicates = base.take(50)
        val analysis = engine.analyze(base + duplicates, gyroTremor(), planned)
        val m = requireNotNull(analysis.metrics)
        assertEquals(1_500, m.accelSamples)
        // 50 of 1550 raw samples (3.2 %) are dropped: under the 10 % limit, so no quality issue is raised.
        assertFalse(ImuTremorQualityIssue.SAMPLES_DROPPED in analysis.quality.issues)
        assertEquals(QualityStatus.VALID, analysis.quality.status)
    }

    @Test
    fun lowSamplingRateIsInsufficientAndNoFrequencyIsInventedFromIt() {
        val slow = accelTremor(rateHz = 20.0)
        val analysis = engine.analyze(slow, gyroTremor(rateHz = 20.0), planned)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertTrue(ImuTremorQualityIssue.LOW_SAMPLING_RATE in analysis.quality.issues)
        assertNull(analysis.metrics)
    }

    @Test
    fun aLongGapInTheStreamIsInsufficientNotBridged() {
        // Drop 1 s of samples in the middle of the recording.
        val full = accelTremor(durationS = 15.0)
        val gapStartNs = 1_000_000_000L + 7_000_000_000L
        val gapEndNs = gapStartNs + 1_000_000_000L
        val withGap = full.filterNot { it.timestampNs in gapStartNs until gapEndNs }
        val analysis = engine.analyze(withGap, gyroTremor(), planned)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertTrue(ImuTremorQualityIssue.LONG_GAP in analysis.quality.issues)
        assertNull(analysis.metrics)
    }

    @Test
    fun aRecordingShorterThanTheRequiredShareIsInsufficient() {
        val short = accelTremor(durationS = 5.0)
        val analysis = engine.analyze(short, gyroTremor(durationS = 5.0), planned)
        assertTrue(ImuTremorQualityIssue.RECORDING_TOO_SHORT in analysis.quality.issues)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertNull(analysis.metrics)
    }

    @Test
    fun aMotionlessPhoneHasZeroAmplitudeAndNoFrequency() {
        val still = samples(MotionSensorType.ACCELEROMETER, 100.0, 15.0, { Triple(0.0, 0.0, 9.81) })
        val gyroStill = samples(MotionSensorType.GYROSCOPE, 100.0, 15.0, { Triple(0.0, 0.0, 0.0) })
        val analysis = engine.analyze(still, gyroStill, planned)
        assertTrue(analysis.quality.isUsable)
        val m = requireNotNull(analysis.metrics)
        // A flat signal has no residual: the RMS is genuinely zero, and no frequency is claimed.
        assertTrue("accel RMS ${m.accelRmsMs2}", abs(m.accelRmsMs2) < 1e-9)
        assertNull(m.dominantFrequencyHz)
        assertNull(m.spectralProminence)
    }

    @Test
    fun timestampsAreMonotonicAndTheSpanMatchesTheRecording() {
        val analysis = engine.analyze(accelTremor(), gyroTremor(), planned)
        val m = requireNotNull(analysis.metrics)
        assertEquals(14_990.0, m.spanMs, 15.0)
    }
}
