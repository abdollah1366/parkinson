package com.example.parkinson.tremor

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import com.example.parkinson.sensors.MotionStreams
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Engineering limits of the phone-motion analysis of the resting-hand test. Not clinically validated.
 * The phone measures the MOVEMENT OF THE PHONE, not of the hand: the phone's weight, the grip and the
 * voluntary stabilisation all change the signal, so these values describe the recording, never the person.
 */
data class RestingTremorImuConfig(
    /** Uniform grid used for the spectrum. Above twice the top of the band. */
    val gridHz: Double = 50.0,
    /** Lowest and highest frequency searched for a dominant peak (Hz). Engineering band. */
    val bandMinHz: Double = 3.0,
    val bandMaxHz: Double = 12.0,
    val frequencyStepHz: Double = 0.1,
    /** Half-width of the centred moving average that defines the slow component removed before analysis. */
    val detrendHalfWindowMs: Double = 500.0,
    /** Fewer clean accelerometer samples than this = insufficient data. */
    val minAccelSamples: Int = 100,
    /** Measured accelerometer rate below this = insufficient for the band (Nyquist must exceed bandMaxHz). */
    val minRateHz: Double = 40.0,
    /** Clean recording span below this share of the planned duration = insufficient. */
    val minDurationFraction: Double = 0.8,
    /** A gap in the accelerometer stream longer than this is not bridged; it is an interruption. */
    val maxGapMs: Double = 250.0,
    /** More dropouts than this (gaps beyond the limit) = low quality. */
    val frequentDropouts: Int = 3,
    /** Share of raw samples dropped by the cleaning (non-finite, out of range, out of order) above this = low quality. */
    val maxDroppedPercent: Double = 10.0,
    /** Share of the spectrum grid covered by bridged samples below this = no frequency. */
    val minCoveragePercent: Double = 90.0,
    /** Peak power divided by the mean band power below this = no dominant frequency. */
    val minPeakProminence: Double = 6.0,
    /** A dominant frequency needs at least this many cycles inside the recording. */
    val minCycles: Double = 3.0,
)

/** Quality findings of the phone-motion recording, most severe first by [status]. */
enum class ImuTremorQualityIssue(val status: QualityStatus) {
    TOO_FEW_SAMPLES(QualityStatus.INSUFFICIENT_DATA),
    RECORDING_TOO_SHORT(QualityStatus.INSUFFICIENT_DATA),
    LOW_SAMPLING_RATE(QualityStatus.INSUFFICIENT_DATA),
    LONG_GAP(QualityStatus.INSUFFICIENT_DATA),

    FREQUENT_GAPS(QualityStatus.LOW_QUALITY),
    SAMPLES_DROPPED(QualityStatus.LOW_QUALITY),
    GYROSCOPE_UNUSABLE(QualityStatus.LOW_QUALITY),
}

data class ImuTremorQualityReport(
    val status: QualityStatus,
    val issues: List<ImuTremorQualityIssue>,
) {
    /** Measurements may be shown only for VALID or LOW_QUALITY recordings. */
    val isUsable: Boolean get() = status == QualityStatus.VALID || status == QualityStatus.LOW_QUALITY

    companion object {
        fun of(issues: Collection<ImuTremorQualityIssue>): ImuTremorQualityReport {
            val sorted = issues.distinct().sortedBy { it.ordinal }
            val status = when {
                sorted.any { it.status == QualityStatus.INSUFFICIENT_DATA } -> QualityStatus.INSUFFICIENT_DATA
                sorted.any { it.status == QualityStatus.LOW_QUALITY } -> QualityStatus.LOW_QUALITY
                else -> QualityStatus.VALID
            }
            return ImuTremorQualityReport(status, sorted)
        }
    }
}

/**
 * Features of one phone-motion recording. Every value comes from the recorded samples. Units are explicit.
 * Nullable values are null when they could not be measured; they are never zero.
 */
data class ImuTremorMetrics(
    /** Span of the cleaned accelerometer stream (ms). */
    val spanMs: Double,
    /** Clean accelerometer samples used. */
    val accelSamples: Int,
    /** Measured accelerometer rate (Hz): (n - 1) / span. */
    val accelRateHz: Double,
    /** Accelerometer gaps longer than the limit (count). */
    val accelDropouts: Int,
    /** Longest accelerometer gap (ms). */
    val accelLongestGapMs: Double,
    /** Clean gyroscope samples used, or null when the gyroscope gave no usable stream. */
    val gyroSamples: Int?,
    /** RMS of the detrended acceleration magnitude (m/s^2). Gravity and the slow component are removed. */
    val accelRmsMs2: Double,
    /** RMS of the detrended angular-velocity magnitude (rad/s). Not an angle: no integration is done. */
    val gyroRmsRadS: Double?,
    /** Frequency of the spectral peak of the detrended acceleration magnitude (Hz), in the band. */
    val dominantFrequencyHz: Double?,
    /** Peak power divided by the mean band power (unitless). Null when there is no dominant frequency. */
    val spectralProminence: Double?,
)

data class ImuTremorAnalysis(
    val metrics: ImuTremorMetrics?,
    val quality: ImuTremorQualityReport,
)

/**
 * Phone-motion analysis of the resting-hand test, from the accelerometer (required) and the gyroscope
 * (optional). Steps: cleaning, timing and quality gates, gravity and slow-component removal, RMS amplitude,
 * spectral peak in the band. The spectrum is a Hann-windowed direct Fourier sum on a uniform grid (linear
 * bridging of gaps no longer than [RestingTremorImuConfig.maxGapMs]). Nothing here is a clinical measure.
 */
class RestingTremorImuEngine(val config: RestingTremorImuConfig = RestingTremorImuConfig()) {

    fun analyze(accel: List<MotionSample>, gyro: List<MotionSample>, plannedMs: Long): ImuTremorAnalysis {
        val accelClean = MotionStreams.clean(accel, MotionStreams.limitFor(MotionSensorType.ACCELEROMETER))
        val gyroClean = MotionStreams.clean(gyro, MotionStreams.limitFor(MotionSensorType.GYROSCOPE))
        val stats = MotionStreams.streamStats(MotionSensorType.ACCELEROMETER, accel, accelClean, plannedMs)

        val issues = mutableListOf<ImuTremorQualityIssue>()
        if (accelClean.size < config.minAccelSamples) issues += ImuTremorQualityIssue.TOO_FEW_SAMPLES
        if (plannedMs > 0 && stats.spanMs < config.minDurationFraction * plannedMs) issues += ImuTremorQualityIssue.RECORDING_TOO_SHORT
        if (accelClean.size >= 2 && stats.samplingRateHz < config.minRateHz) issues += ImuTremorQualityIssue.LOW_SAMPLING_RATE
        if (stats.longestGapMs > config.maxGapMs) issues += ImuTremorQualityIssue.LONG_GAP
        if (stats.dropoutCount > config.frequentDropouts) issues += ImuTremorQualityIssue.FREQUENT_GAPS
        val droppedPercent = if (accel.isEmpty()) 100.0 else 100.0 * (accel.size - accelClean.size) / accel.size
        if (droppedPercent > config.maxDroppedPercent) issues += ImuTremorQualityIssue.SAMPLES_DROPPED
        val gyroUsable = gyroClean.size >= config.minAccelSamples
        if (!gyroUsable) issues += ImuTremorQualityIssue.GYROSCOPE_UNUSABLE

        val report = ImuTremorQualityReport.of(issues)
        if (!report.isUsable) return ImuTremorAnalysis(null, report)

        val t = DoubleArray(accelClean.size) { (accelClean[it].timestampNs - accelClean[0].timestampNs) / 1e6 }
        val mag = DoubleArray(accelClean.size) { magnitude(accelClean[it]) }
        val residual = detrend(t, mag, config.detrendHalfWindowMs)
        val accelRms = rms(residual)

        var gyroRms: Double? = null
        if (gyroUsable) {
            val gt = DoubleArray(gyroClean.size) { (gyroClean[it].timestampNs - gyroClean[0].timestampNs) / 1e6 }
            val gm = DoubleArray(gyroClean.size) { magnitude(gyroClean[it]) }
            gyroRms = rms(detrend(gt, gm, config.detrendHalfWindowMs))
        }

        val grid = resampleToGrid(t, residual, config)
        val spectrum = if (grid.coveragePercent >= config.minCoveragePercent) {
            dominantFrequency(grid.values, config)
        } else {
            null
        }

        val metrics = ImuTremorMetrics(
            spanMs = stats.spanMs,
            accelSamples = accelClean.size,
            accelRateHz = stats.samplingRateHz,
            accelDropouts = stats.dropoutCount,
            accelLongestGapMs = stats.longestGapMs,
            gyroSamples = if (gyroUsable) gyroClean.size else null,
            accelRmsMs2 = accelRms,
            gyroRmsRadS = gyroRms,
            dominantFrequencyHz = spectrum?.first,
            spectralProminence = spectrum?.second,
        )
        return ImuTremorAnalysis(metrics, report)
    }

    private fun magnitude(s: MotionSample): Double =
        sqrt(s.x.toDouble() * s.x + s.y.toDouble() * s.y + s.z.toDouble() * s.z)

    private fun rms(v: DoubleArray): Double {
        if (v.isEmpty()) return 0.0
        var sum = 0.0
        for (x in v) sum += x * x
        return sqrt(sum / v.size)
    }

    /**
     * Residual after subtracting the centred moving average over +-[halfWindowMs]. Removes the mean (gravity
     * for the accelerometer) and the slow component, keeping the faster movement.
     */
    internal fun detrend(t: DoubleArray, v: DoubleArray, halfWindowMs: Double): DoubleArray {
        val n = v.size
        val prefix = DoubleArray(n + 1)
        for (i in 0 until n) prefix[i + 1] = prefix[i] + v[i]
        var lo = 0
        var hi = 0
        return DoubleArray(n) { i ->
            while (lo < i && t[i] - t[lo] > halfWindowMs) lo++
            if (hi < i) hi = i
            while (hi + 1 < n && t[hi + 1] - t[i] <= halfWindowMs) hi++
            val mean = (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
            v[i] - mean
        }
    }

    internal class Grid(val values: DoubleArray, val coveragePercent: Double)

    /**
     * Linear resampling onto the uniform grid. A grid point is taken from the two surrounding samples only if
     * they are no further apart than [RestingTremorImuConfig.maxGapMs]; otherwise it is left out (NaN) and
     * counts against coverage. NaN points are set to 0 (the mean of the detrended signal) only after the
     * coverage gate has passed; that is the only place a value is filled, and it is documented here.
     */
    internal fun resampleToGrid(t: DoubleArray, v: DoubleArray, c: RestingTremorImuConfig): Grid {
        val n = t.size
        if (n < 2) return Grid(DoubleArray(0), 0.0)
        val total = t[n - 1]
        val count = floor(total / 1000.0 * c.gridHz).toInt() + 1
        val out = DoubleArray(count)
        var covered = 0
        var i = 0
        for (k in 0 until count) {
            val g = k * 1000.0 / c.gridHz
            while (i + 1 < n && t[i + 1] <= g) i++
            val value: Double? = when {
                abs(g - t[i]) < 1e-9 -> v[i]
                i + 1 < n && g > t[i] && t[i + 1] - t[i] <= c.maxGapMs ->
                    v[i] + (v[i + 1] - v[i]) * (g - t[i]) / (t[i + 1] - t[i])
                else -> null
            }
            if (value != null) {
                out[k] = value
                covered++
            } else {
                out[k] = Double.NaN
            }
        }
        val coverage = if (count == 0) 0.0 else 100.0 * covered / count
        for (k in out.indices) if (out[k].isNaN()) out[k] = 0.0
        return Grid(out, coverage)
    }

    /**
     * Hann-windowed direct Fourier sum over the band. Returns (frequency Hz, peak / mean band power) or null when
     * the signal is flat, the peak is not prominent enough, or the recording holds too few cycles.
     */
    internal fun dominantFrequency(y: DoubleArray, c: RestingTremorImuConfig): Pair<Double, Double>? {
        val n = y.size
        if (n < 8) return null
        val durationS = n / c.gridHz
        val w = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }
        val windowed = DoubleArray(n) { y[it] * w[it] }
        var energy = 0.0
        for (x in windowed) energy += x * x
        if (energy < 1e-18) return null

        val freqs = ArrayList<Double>()
        var f = c.bandMinHz
        while (f <= c.bandMaxHz + 1e-9) {
            freqs += f
            f += c.frequencyStepHz
        }
        val power = DoubleArray(freqs.size)
        for ((j, freq) in freqs.withIndex()) {
            var re = 0.0
            var im = 0.0
            val omega = 2 * PI * freq / c.gridHz
            for (k in 0 until n) {
                val angle = omega * k
                re += windowed[k] * cos(angle)
                im -= windowed[k] * sin(angle)
            }
            power[j] = re * re + im * im
        }
        var peakIndex = 0
        for (j in power.indices) if (power[j] > power[peakIndex]) peakIndex = j
        val mean = power.average()
        if (mean <= 0.0) return null
        val prominence = power[peakIndex] / mean
        val cycles = freqs[peakIndex] * durationS
        if (prominence < c.minPeakProminence || cycles < c.minCycles) return null
        return freqs[peakIndex] to prominence
    }
}
