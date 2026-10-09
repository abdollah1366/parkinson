package com.example.parkinson.sensors

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** SYNTHETIC streams for unit tests only; never an assessment result. */
class PhoneInertialAnalyzerTest {

    private val analyzer = PhoneInertialAnalyzer()

    /** Accelerometer at [rateHz] with magnitude 9.81 + [amp] sin(2 pi f t); gyro constant 0.5 rad/s. */
    private fun stream(
        seconds: Double = 20.0,
        rateHz: Double = 50.0,
        stepHz: Double = 2.0,
        amp: Double = 2.0,
        dropAfterS: Double? = null,
        dropLenS: Double = 0.0,
    ): List<MotionSample> {
        val out = ArrayList<MotionSample>()
        val n = (seconds * rateHz).toInt()
        for (i in 0 until n) {
            val t = i / rateHz
            if (dropAfterS != null && t >= dropAfterS && t < dropAfterS + dropLenS) continue
            val ns = (t * 1e9).toLong()
            out += MotionSample(MotionSensorType.ACCELEROMETER, ns, 0f, 0f, (9.81 + amp * sin(2 * PI * stepHz * t)).toFloat())
            out += MotionSample(MotionSensorType.GYROSCOPE, ns, 0f, 0f, 0.5f)
        }
        return out.sortedBy { it.timestampNs }
    }

    @Test
    fun knownStepFrequencyIsRecoveredAndConvertedToCadence() {
        val a = requireNotNull(analyzer.analyze(stream(stepHz = 2.0), durationMs = 20_000.0).metrics)
        assertEquals(2.0, requireNotNull(a.dominantStepFrequencyHz), 0.05)
        assertEquals(120.0, requireNotNull(a.cadenceStepsPerMinute), 3.0)
        assertEquals(2.0 / Math.sqrt(2.0), requireNotNull(a.accelFluctuationRmsMps2), 0.1)
        assertEquals(0.5, requireNotNull(a.meanGyroMagnitudeRadPerS), 1e-6)
    }

    @Test
    fun measuredRateAndIntervalsComeFromTimestampsNotFromTheRequest() {
        val a = requireNotNull(analyzer.analyze(stream(rateHz = 25.0), durationMs = 20_000.0).metrics)
        assertEquals(25.0, a.accelerometer.measuredRateHz, 0.1)
        assertEquals(40.0, a.accelerometer.medianIntervalMs, 0.01)
        assertEquals(0, a.accelerometer.dropoutCount)
    }

    @Test
    fun rateBelowMinimumIsFlaggedButStillMeasured() {
        val analysis = analyzer.analyze(stream(rateHz = 10.0), durationMs = 20_000.0)
        assertTrue(analysis.issues.contains(InertialQualityIssue.LOW_SAMPLE_RATE))
        assertFalse(analysis.isUsable)
        requireNotNull(analysis.metrics)
    }

    @Test
    fun dropoutsAreCountedAndFlagged() {
        val analysis = analyzer.analyze(stream(dropAfterS = 8.0, dropLenS = 0.5), durationMs = 20_000.0)
        val m = requireNotNull(analysis.metrics)
        assertEquals(1, m.accelerometer.dropoutCount)
        assertTrue(m.accelerometer.longestGapMs >= 500.0)
        assertTrue(analysis.issues.contains(InertialQualityIssue.LONG_DROPOUT))
    }

    @Test
    fun shortRecordingIsFlaggedAsTooShort() {
        val analysis = analyzer.analyze(stream(seconds = 5.0), durationMs = 5_000.0)
        assertTrue(analysis.issues.contains(InertialQualityIssue.TOO_SHORT))
        assertNull(requireNotNull(analysis.metrics).dominantStepFrequencyHz)
    }

    @Test
    fun missingGyroscopeIsReportedAndNotFabricated() {
        val accelOnly = stream().filter { it.type == MotionSensorType.ACCELEROMETER }
        val m = requireNotNull(analyzer.analyze(accelOnly, durationMs = 20_000.0).metrics)
        assertNull(m.meanGyroMagnitudeRadPerS)
        assertEquals(0, m.gyroscope.sampleCount)
        assertTrue(analyzer.analyze(accelOnly, durationMs = 20_000.0).issues.contains(InertialQualityIssue.GYRO_MISSING))
    }

    @Test
    fun noSamplesGivesNoMetrics() {
        val analysis = analyzer.analyze(emptyList(), durationMs = 20_000.0)
        assertNull(analysis.metrics)
        assertFalse(analysis.isUsable)
    }

    @Test
    fun nonMonotonicTimestampsAreRejected() {
        val s = stream().toMutableList()
        s[100] = s[100].copy(timestampNs = s[0].timestampNs)
        val analysis = analyzer.analyze(s, durationMs = 20_000.0)
        assertTrue(analysis.issues.contains(InertialQualityIssue.NON_MONOTONIC_TIMESTAMPS))
        assertNull(analysis.metrics)
    }

    @Test
    fun noStepPeakWithoutFluctuationGivesNoFrequency() {
        val flat = stream(amp = 0.0)
        val m = requireNotNull(analyzer.analyze(flat, durationMs = 20_000.0).metrics)
        assertNull(m.dominantStepFrequencyHz)
        assertNull(m.cadenceStepsPerMinute)
        assertEquals(0.0, requireNotNull(m.accelFluctuationRmsMps2), 1e-6)
    }
}
