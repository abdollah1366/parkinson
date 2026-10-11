package com.example.parkinson.sensors

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Feature extraction from a phone worn at the waist (see the placement protocol). Input: raw
 * [MotionSample]s with SensorEvent timestamps (nanoseconds, one monotonic clock for both sensors).
 *
 * Units: accelerometer m/s^2 (gravity included in the raw data; the analysis removes the slow component
 * and reports only fluctuations), gyroscope rad/s, time ms or Hz. Nothing here is calibrated physical
 * distance, step length or foot contact.
 */
data class InertialStreamStats(
    val sampleCount: Int,
    /** Requested rate is only a request; this is the measured mean rate (Hz) from the timestamps. */
    val measuredRateHz: Double,
    /** Median interval between consecutive samples (ms). */
    val medianIntervalMs: Double,
    /** Intervals longer than [PhoneInertialAnalyzer.DROPOUT_MS] (count). */
    val dropoutCount: Int,
    val longestGapMs: Double,
    /** Share of samples flagged SENSOR_STATUS_UNRELIABLE (percent). */
    val unreliablePercent: Double,
)

data class InertialMetrics(
    val durationMs: Double,
    val accelerometer: InertialStreamStats,
    val gyroscope: InertialStreamStats,
    /** RMS of the detrended acceleration magnitude (m/s^2). Null when not computable. */
    val accelFluctuationRmsMps2: Double?,
    /** Dominant frequency of the acceleration magnitude (Hz); the step frequency when walking. Null if no reliable peak. */
    val dominantStepFrequencyHz: Double?,
    /** 60 x [dominantStepFrequencyHz] (steps/min). Null with the frequency. */
    val cadenceStepsPerMinute: Double?,
    /** Mean gyroscope magnitude (rad/s). Null when the gyroscope has no usable samples. */
    val meanGyroMagnitudeRadPerS: Double?,
)

enum class InertialQualityIssue { TOO_SHORT, ACCEL_MISSING, GYRO_MISSING, LOW_SAMPLE_RATE, LONG_DROPOUT, NON_MONOTONIC_TIMESTAMPS }

data class InertialAnalysis(
    /** Null when the input cannot be measured at all (empty or non-monotonic). */
    val metrics: InertialMetrics?,
    val issues: List<InertialQualityIssue>,
) {
    val isUsable: Boolean get() = metrics != null && issues.isEmpty()
}

/**
 * ENGINEERING limits and bands, not clinical values:
 * - [MIN_DURATION_MS]: enough walking for a periodogram.
 * - [MIN_RATE_HZ]: the step frequency (about 1-2 Hz) needs a sampling rate well above 2 x 4 Hz.
 * - Frequency band [MIN_STEP_HZ]..[MAX_STEP_HZ]: the walking-cadence range used for the step-frequency
 *   estimate. It is an analysis band, not a clinical threshold.
 */
class PhoneInertialAnalyzer(
    private val gridHz: Double = 50.0,
    private val detrendWindowMs: Double = 1_000.0,
) {

    fun analyze(samples: List<MotionSample>, durationMs: Double): InertialAnalysis {
        for (i in 1 until samples.size) {
            if (samples[i].timestampNs < samples[i - 1].timestampNs) {
                return InertialAnalysis(null, listOf(InertialQualityIssue.NON_MONOTONIC_TIMESTAMPS))
            }
        }
        val accel = samples.filter { it.type == MotionSensorType.ACCELEROMETER }
        val gyro = samples.filter { it.type == MotionSensorType.GYROSCOPE }
        if (accel.isEmpty() && gyro.isEmpty()) return InertialAnalysis(null, listOf(InertialQualityIssue.ACCEL_MISSING))

        val issues = ArrayList<InertialQualityIssue>()
        val accelStats = streamStats(accel)
        val gyroStats = streamStats(gyro)
        if (accelStats == null) issues += InertialQualityIssue.ACCEL_MISSING
        if (gyroStats == null) issues += InertialQualityIssue.GYRO_MISSING
        if (durationMs < MIN_DURATION_MS) issues += InertialQualityIssue.TOO_SHORT
        if (accelStats != null && accelStats.measuredRateHz < MIN_RATE_HZ) issues += InertialQualityIssue.LOW_SAMPLE_RATE
        if (accelStats != null && accelStats.dropoutCount > 0) issues += InertialQualityIssue.LONG_DROPOUT

        val magnitude = accel.map { magnitude(it) }
        val times = accel.map { it.timestampNs / 1e6 }
        val grid = resample(times, magnitude)
        val detrended = detrend(grid)
        val rms = rmsOf(detrended)
        // Too-short recordings are flagged TOO_SHORT and get no frequency at all.
        val spectrum = if (durationMs >= MIN_DURATION_MS) dominantFrequency(detrended) else null

        val metrics = InertialMetrics(
            durationMs = durationMs,
            accelerometer = accelStats ?: EMPTY_STATS,
            gyroscope = gyroStats ?: EMPTY_STATS,
            accelFluctuationRmsMps2 = rms,
            dominantStepFrequencyHz = spectrum,
            cadenceStepsPerMinute = spectrum?.let { it * 60.0 },
            meanGyroMagnitudeRadPerS = if (gyro.isEmpty()) null else gyro.map { magnitude(it) }.average(),
        )
        return InertialAnalysis(metrics, issues)
    }

    private fun streamStats(s: List<MotionSample>): InertialStreamStats? {
        if (s.size < 2) return null
        val intervals = DoubleArray(s.size - 1) { (s[it + 1].timestampNs - s[it].timestampNs) / 1e6 }
        val sorted = intervals.sortedArray()
        val median = if (sorted.size % 2 == 1) sorted[sorted.size / 2]
        else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2.0
        val spanMs = intervals.sum()
        val gaps = intervals.filter { it > DROPOUT_MS }
        return InertialStreamStats(
            sampleCount = s.size,
            measuredRateHz = if (spanMs > 0) (s.size - 1) * 1000.0 / spanMs else 0.0,
            medianIntervalMs = median,
            dropoutCount = gaps.size,
            longestGapMs = intervals.maxOrNull() ?: 0.0,
            unreliablePercent = 100.0 * s.count { it.unreliable } / s.size,
        )
    }

    /** Linear interpolation onto a uniform grid; grid points inside a dropout (> DROPOUT_MS) stay NaN. */
    private fun resample(times: List<Double>, values: List<Double>): DoubleArray {
        if (times.size < 2) return DoubleArray(0)
        val start = times.first()
        val n = floor((times.last() - start) * gridHz / 1000.0).toInt() + 1
        val out = DoubleArray(n) { Double.NaN }
        var k = 0
        for (g in 0 until n) {
            val tg = start + g * 1000.0 / gridHz
            while (k + 1 < times.size - 1 && times[k + 1] <= tg) k++
            if (times[k] > tg || k + 1 >= times.size) continue
            val span = times[k + 1] - times[k]
            if (span > DROPOUT_MS) continue
            val f = if (span > 0) (tg - times[k]) / span else 0.0
            out[g] = values[k] + (values[k + 1] - values[k]) * f
        }
        return out
    }

    /** Subtracts the centred moving average over non-NaN samples. NaN stays NaN. */
    private fun detrend(x: DoubleArray): DoubleArray {
        val half = (detrendWindowMs * gridHz / 2000.0).toInt().coerceAtLeast(1)
        return DoubleArray(x.size) { i ->
            if (x[i].isNaN()) return@DoubleArray Double.NaN
            var sum = 0.0
            var n = 0
            for (j in maxOf(0, i - half)..minOf(x.size - 1, i + half)) {
                if (!x[j].isNaN()) { sum += x[j]; n++ }
            }
            x[i] - sum / n
        }
    }

    private fun rmsOf(x: DoubleArray): Double? {
        val valid = x.filter { !it.isNaN() }
        if (valid.size < 2) return null
        return sqrt(valid.sumOf { it * it } / valid.size)
    }

    /**
     * Dominant frequency in [MIN_STEP_HZ, MAX_STEP_HZ] by a Hann-windowed periodogram (direct DFT).
     * Null when coverage is below 90 %, fewer than [MIN_CYCLES] cycles fit, or the peak prominence
     * (peak / mean band power) is below [MIN_PROMINENCE].
     */
    private fun dominantFrequency(x: DoubleArray): Double? {
        val coverage = x.count { !it.isNaN() }.toDouble() / x.size.coerceAtLeast(1)
        if (x.size < 4 || coverage < 0.9) return null
        val n = x.size
        val s = DoubleArray(n) { if (x[it].isNaN()) 0.0 else x[it] }
        val durationS = (n - 1) / gridHz
        val freqs = generateSequence(MIN_STEP_HZ) { it + FREQ_STEP_HZ }.takeWhile { it <= MAX_STEP_HZ + 1e-9 }.toList()
        val power = DoubleArray(freqs.size)
        for (k in freqs.indices) {
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val w = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
                val phase = 2 * PI * freqs[k] * i / gridHz
                re += s[i] * w * cos(phase)
                im += s[i] * w * sin(phase)
            }
            power[k] = re * re + im * im
        }
        val mean = power.average()
        if (!mean.isFinite() || mean <= 0.0) return null
        var peak = 0
        for (k in power.indices) if (power[k] > power[peak]) peak = k
        val cycles = freqs[peak] * durationS
        if (power[peak] / mean < MIN_PROMINENCE || cycles < MIN_CYCLES) return null
        return freqs[peak]
    }

    private fun magnitude(s: MotionSample): Double = sqrt(s.x.toDouble() * s.x + s.y.toDouble() * s.y + s.z.toDouble() * s.z)

    companion object {
        /** Gaps between consecutive samples longer than this count as dropouts (ms). */
        const val DROPOUT_MS = 200.0
        const val MIN_DURATION_MS = 10_000.0
        const val MIN_RATE_HZ = 20.0
        const val MIN_STEP_HZ = 0.5
        const val MAX_STEP_HZ = 4.0
        const val FREQ_STEP_HZ = 0.02
        const val MIN_CYCLES = 3.0
        const val MIN_PROMINENCE = 6.0

        private val EMPTY_STATS = InertialStreamStats(0, 0.0, 0.0, 0, 0.0, 0.0)
    }
}
