package com.example.parkinson.stability

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Versions stored with every Hand Stability result. Bump [ALGORITHM_VERSION] for any change to
 * cleaning, metrics, frequency analysis or quality rules; bump [SCORING_VERSION] for any change to
 * the index. See docs/hand-stability-algorithm.md.
 */
object HandStabilityVersions {
    const val ALGORITHM_VERSION = "hs-algo-1.0.0"

    /** "preliminary": engineering reference values, NOT clinically validated. */
    const val SCORING_VERSION = "hs-score-0.1.0-preliminary"
}

/** Everything recorded during the 15-second window, in arrival order. */
data class StabilityRecording(
    val samples: List<MotionSample>,
    val plannedDurationMs: Long,
    val hand: SelectedHand
)

/** Timing and validity of one sensor stream, measured from its own timestamps. */
data class StreamStats(
    val type: MotionSensorType,
    val receivedSamples: Int,
    val validSamples: Int,
    val validPercent: Double,
    val spanMs: Double,
    /** Measured, not requested: (n - 1) / span. 0 with fewer than 2 samples. */
    val samplingRateHz: Double,
    val medianIntervalMs: Double,
    val intervalSdMs: Double?,
    val dropoutCount: Int,
    /** Time missing in gaps, beyond one normal interval per gap. */
    val dropoutTotalMs: Double,
    val longestGapMs: Double,
    val completenessPercent: Double,
    val unreliablePercent: Double
) {
    val dropoutShare: Double get() = if (spanMs > 0) dropoutTotalMs / spanMs else 1.0
}

enum class FrequencyStatus {
    /** A clear peak in the 3-12 Hz band was found and is reported. */
    REPORTED,

    /** Gyroscope rate too low to resolve 12 Hz reliably. */
    SAMPLING_TOO_LOW,

    /** Rotational movement at the level of sensor noise: a "frequency" would be noise. */
    MOVEMENT_TOO_SMALL,

    /** Movement present but its power is not concentrated around one frequency. */
    NO_CLEAR_PEAK
}

/**
 * Motion metrics. Acceleration in m/s^2, angular velocity in deg/s, angles in degrees.
 * "Dynamic" = after removing the slowly varying part (gravity / sensor bias / posture drift) with a
 * centered 1 s moving average, i.e. the movement content above ~1 Hz.
 */
data class StabilityMetrics(
    val accMagnitudeMean: Double,
    val accMagnitudeSd: Double,
    val accMagnitudeVariance: Double,
    val accMagnitudeRange: Double,
    val accDynamicRms: Double,
    val accDynamicVariance: Double,

    val gyroMagnitudeMean: Double,
    val gyroMagnitudeRms: Double,
    val gyroMagnitudeSd: Double,
    val gyroMagnitudeVariance: Double,
    val gyroMagnitudeMax: Double,
    val gyroDynamicRms: Double,

    /** Largest peak-to-peak rotation about one device axis (bias and linear drift removed). */
    val rotationRangeDeg: Double,
    /** Largest change of the gravity direction relative to the start: how far the phone was turned. */
    val tiltChangeDeg: Double,

    val dominantFrequencyHz: Double?,
    /** Share of 1-20 Hz rotational power that lies in 3-12 Hz; null when not computable. */
    val oscillationBandPowerPercent: Double?,
    val frequencyStatus: FrequencyStatus
)

enum class StabilityQualityIssue(val severity: QualityStatus) {
    NO_ACCELEROMETER_DATA(QualityStatus.INVALID),
    NO_GYROSCOPE_DATA(QualityStatus.INVALID),
    SAMPLING_RATE_TOO_LOW(QualityStatus.INVALID),
    RECORDING_INCOMPLETE(QualityStatus.INVALID),
    EXCESSIVE_GAPS(QualityStatus.INVALID),
    TOO_MANY_INVALID_SAMPLES(QualityStatus.INVALID),

    TOO_FEW_SAMPLES(QualityStatus.INSUFFICIENT_DATA),

    LOW_SAMPLING_RATE(QualityStatus.LOW_QUALITY),
    GAPS_PRESENT(QualityStatus.LOW_QUALITY),
    INVALID_SAMPLES_PRESENT(QualityStatus.LOW_QUALITY),
    SENSOR_UNRELIABLE(QualityStatus.LOW_QUALITY),
    STREAMS_MISALIGNED(QualityStatus.LOW_QUALITY),
    PHONE_REORIENTED(QualityStatus.LOW_QUALITY),
    GRAVITY_IMPLAUSIBLE(QualityStatus.LOW_QUALITY)
}

/** Engineering limits for a usable recording (technical reliability, not the person). */
data class StabilityQualityThresholds(
    /** 10 Hz over 15 s. */
    val minSamplesPerStream: Int = 150,
    val minRateHz: Double = 15.0,
    val goodRateHz: Double = 40.0,
    val minCompletenessPercent: Double = 90.0,
    val minValidPercent: Double = 90.0,
    val goodValidPercent: Double = 99.0,
    val maxGapShare: Double = 0.20,
    val maxLongestGapMs: Double = 1_000.0,
    val goodGapShare: Double = 0.02,
    val goodLongestGapMs: Double = 250.0,
    val maxUnreliablePercent: Double = 10.0,
    val maxStreamOffsetMs: Double = 500.0,
    val maxTiltChangeDeg: Double = 30.0,
    val minGravity: Double = 7.0,
    val maxGravity: Double = 12.5
)

data class StabilityQualityReport(
    val status: QualityStatus,
    /** Most severe first. */
    val issues: List<StabilityQualityIssue>,
    /** 0..100 technical recording quality. */
    val qualityScore: Int
) {
    val primaryIssue: StabilityQualityIssue? get() = issues.firstOrNull()
    val isUsable: Boolean get() = status == QualityStatus.VALID || status == QualityStatus.LOW_QUALITY
}

/** "شاخص ثبات دست": preliminary engineering index 0..100, higher = steadier. */
data class StabilityIndex(
    val total: Int,
    val rotationComponent: Int,
    val accelerationComponent: Int,
    val scoringVersion: String = HandStabilityVersions.SCORING_VERSION
)

data class StabilityAnalysis(
    val accel: StreamStats,
    val gyro: StreamStats,
    /** null when a stream has too few valid samples to compute anything. */
    val metrics: StabilityMetrics?,
    val quality: StabilityQualityReport,
    /** Only for VALID recordings. */
    val index: StabilityIndex?,
    /** Overlap of both streams = the synchronized analysis window. */
    val analyzedDurationMs: Double
)

/**
 * Hand Stability analysis. Pure and deterministic: the same recording always gives the same
 * result. Sensor rate is never assumed; every time step uses the measured timestamps.
 */
class HandStabilityEngine(
    private val thresholds: StabilityQualityThresholds = StabilityQualityThresholds(),
    /** Half width of the centered moving average that separates movement from gravity/bias/drift. */
    private val trendHalfWindowS: Double = 0.5
) {

    fun analyze(recording: StabilityRecording): StabilityAnalysis {
        val accelRaw = recording.samples.filter { it.type == MotionSensorType.ACCELEROMETER }
        val gyroRaw = recording.samples.filter { it.type == MotionSensorType.GYROSCOPE }
        val accel = clean(accelRaw, ACCEL_LIMIT)
        val gyro = clean(gyroRaw, GYRO_LIMIT_RAD_S)
        val accelStats = streamStats(MotionSensorType.ACCELEROMETER, accelRaw, accel, recording.plannedDurationMs)
        val gyroStats = streamStats(MotionSensorType.GYROSCOPE, gyroRaw, gyro, recording.plannedDurationMs)

        val metrics = if (accel.size >= MIN_FOR_METRICS && gyro.size >= MIN_FOR_METRICS) {
            computeMetrics(accel, gyro, gyroStats.samplingRateHz)
        } else null

        val overlapMs = if (accel.isNotEmpty() && gyro.isNotEmpty()) {
            max(0.0, (min(accel.last().timestampNs, gyro.last().timestampNs) -
                max(accel.first().timestampNs, gyro.first().timestampNs)) / 1e6)
        } else 0.0
        val offsetMs = if (accel.isNotEmpty() && gyro.isNotEmpty()) {
            max(
                abs(accel.first().timestampNs - gyro.first().timestampNs),
                abs(accel.last().timestampNs - gyro.last().timestampNs)
            ) / 1e6
        } else 0.0

        val quality = assessQuality(accelStats, gyroStats, metrics, offsetMs)
        val index = if (quality.status == QualityStatus.VALID && metrics != null) score(metrics) else null
        return StabilityAnalysis(accelStats, gyroStats, metrics, quality, index, overlapMs)
    }

    // --- Cleaning and timing -------------------------------------------------------------------

    /** Drops non-finite, physically implausible and out-of-order/duplicate samples. */
    private fun clean(samples: List<MotionSample>, limit: Double): List<MotionSample> {
        val plausible = samples.filter {
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite() &&
                abs(it.x) <= limit && abs(it.y) <= limit && abs(it.z) <= limit
        }.sortedBy { it.timestampNs }
        val out = ArrayList<MotionSample>(plausible.size)
        for (s in plausible) {
            if (out.isEmpty() || s.timestampNs > out.last().timestampNs) out += s
        }
        return out
    }

    private fun streamStats(
        type: MotionSensorType,
        raw: List<MotionSample>,
        valid: List<MotionSample>,
        plannedMs: Long
    ): StreamStats {
        val n = valid.size
        val validPercent = if (raw.isEmpty()) 0.0 else n * 100.0 / raw.size
        val unreliablePercent = if (n == 0) 0.0 else valid.count { it.unreliable } * 100.0 / n
        if (n < 2) {
            return StreamStats(type, raw.size, n, validPercent, 0.0, 0.0, 0.0, null, 0, 0.0, 0.0, 0.0, unreliablePercent)
        }
        val intervals = DoubleArray(n - 1) { (valid[it + 1].timestampNs - valid[it].timestampNs) / 1e6 }
        val sorted = intervals.sorted()
        val median = percentile(sorted, 0.5)
        val spanMs = (valid.last().timestampNs - valid.first().timestampNs) / 1e6
        val gapThreshold = max(GAP_FACTOR * median, MIN_GAP_MS)
        var dropouts = 0
        var dropoutMs = 0.0
        for (i in intervals) {
            if (i > gapThreshold) {
                dropouts++
                dropoutMs += i - median
            }
        }
        val mean = intervals.average()
        val sd = if (intervals.size >= 2) sqrt(intervals.sumOf { (it - mean) * (it - mean) } / (intervals.size - 1)) else null
        val completeness = if (plannedMs > 0) min(100.0, (spanMs + median) * 100.0 / plannedMs) else 0.0
        return StreamStats(
            type = type,
            receivedSamples = raw.size,
            validSamples = n,
            validPercent = validPercent,
            spanMs = spanMs,
            samplingRateHz = if (spanMs > 0) (n - 1) * 1000.0 / spanMs else 0.0,
            medianIntervalMs = median,
            intervalSdMs = sd,
            dropoutCount = dropouts,
            dropoutTotalMs = dropoutMs,
            longestGapMs = sorted.last(),
            completenessPercent = completeness,
            unreliablePercent = unreliablePercent
        )
    }

    // --- Metrics -------------------------------------------------------------------------------

    private class Axes(val t: DoubleArray, val x: DoubleArray, val y: DoubleArray, val z: DoubleArray)

    private fun axes(samples: List<MotionSample>, t0: Long, scale: Double): Axes = Axes(
        t = DoubleArray(samples.size) { (samples[it].timestampNs - t0) / 1e9 },
        x = DoubleArray(samples.size) { samples[it].x * scale },
        y = DoubleArray(samples.size) { samples[it].y * scale },
        z = DoubleArray(samples.size) { samples[it].z * scale }
    )

    private fun computeMetrics(
        accelSamples: List<MotionSample>,
        gyroSamples: List<MotionSample>,
        gyroRateHz: Double
    ): StabilityMetrics {
        // One time base for both streams: the shared sensor clock, relative to the earliest sample.
        val t0 = min(accelSamples.first().timestampNs, gyroSamples.first().timestampNs)
        val a = axes(accelSamples, t0, 1.0)
        val g = axes(gyroSamples, t0, RAD_TO_DEG)

        // Acceleration magnitude (includes gravity, independent of phone orientation).
        val accMag = DoubleArray(a.t.size) { norm(a.x[it], a.y[it], a.z[it]) }
        val accMean = accMag.average()
        val accVar = variance(accMag, accMean)

        // Gravity / posture estimate and movement part.
        val ax = movingAverage(a.t, a.x)
        val ay = movingAverage(a.t, a.y)
        val az = movingAverage(a.t, a.z)
        val accDyn = DoubleArray(a.t.size) { norm(a.x[it] - ax[it], a.y[it] - ay[it], a.z[it] - az[it]) }
        val accDynMeanSq = accDyn.sumOf { it * it } / accDyn.size

        var tilt = 0.0
        for (i in a.t.indices) tilt = max(tilt, angleDeg(ax[0], ay[0], az[0], ax[i], ay[i], az[i]))

        // Angular velocity.
        val gyroMag = DoubleArray(g.t.size) { norm(g.x[it], g.y[it], g.z[it]) }
        val gyroMean = gyroMag.average()
        val gyroVar = variance(gyroMag, gyroMean)
        val gx = movingAverage(g.t, g.x)
        val gy = movingAverage(g.t, g.y)
        val gz = movingAverage(g.t, g.z)
        val dx = DoubleArray(g.t.size) { g.x[it] - gx[it] }
        val dy = DoubleArray(g.t.size) { g.y[it] - gy[it] }
        val dz = DoubleArray(g.t.size) { g.z[it] - gz[it] }
        val gyroDynRms = sqrt(g.t.indices.sumOf { dx[it] * dx[it] + dy[it] * dy[it] + dz[it] * dz[it] } / g.t.size)

        val rotationRange = maxOf(rotationRange(g.t, g.x), rotationRange(g.t, g.y), rotationRange(g.t, g.z))

        val spectrum = spectrum(g.t, dx, dy, dz, gyroRateHz)
        val (frequency, bandPercent, status) = when {
            spectrum == null -> Triple(null, null, FrequencyStatus.SAMPLING_TOO_LOW)
            gyroDynRms < MIN_OSCILLATION_RMS_DEG_S -> Triple(null, spectrum.bandPercent, FrequencyStatus.MOVEMENT_TOO_SMALL)
            spectrum.peakConcentration < MIN_PEAK_CONCENTRATION ->
                Triple(null, spectrum.bandPercent, FrequencyStatus.NO_CLEAR_PEAK)
            else -> Triple(spectrum.peakHz, spectrum.bandPercent, FrequencyStatus.REPORTED)
        }

        return StabilityMetrics(
            accMagnitudeMean = accMean,
            accMagnitudeSd = sqrt(accVar),
            accMagnitudeVariance = accVar,
            accMagnitudeRange = accMag.max() - accMag.min(),
            accDynamicRms = sqrt(accDynMeanSq),
            accDynamicVariance = variance(accDyn, accDyn.average()),
            gyroMagnitudeMean = gyroMean,
            gyroMagnitudeRms = sqrt(gyroMag.sumOf { it * it } / gyroMag.size),
            gyroMagnitudeSd = sqrt(gyroVar),
            gyroMagnitudeVariance = gyroVar,
            gyroMagnitudeMax = gyroMag.max(),
            gyroDynamicRms = gyroDynRms,
            rotationRangeDeg = rotationRange,
            tiltChangeDeg = tilt,
            dominantFrequencyHz = frequency,
            oscillationBandPowerPercent = bandPercent,
            frequencyStatus = status
        )
    }

    /** Centered moving average over +-[trendHalfWindowS] on non-uniform timestamps, O(n). */
    private fun movingAverage(t: DoubleArray, v: DoubleArray): DoubleArray {
        val out = DoubleArray(t.size)
        var lo = 0
        var hi = 0
        var sum = 0.0
        for (i in t.indices) {
            while (hi < t.size && t[hi] <= t[i] + trendHalfWindowS) sum += v[hi++]
            while (t[lo] < t[i] - trendHalfWindowS) sum -= v[lo++]
            out[i] = sum / (hi - lo)
        }
        return out
    }

    /**
     * Peak-to-peak angle about one axis: integrate the bias-free angular velocity (trapezoid on
     * real timestamps), then remove the linear trend left by residual bias.
     */
    private fun rotationRange(t: DoubleArray, w: DoubleArray): Double {
        val bias = w.average()
        val angle = DoubleArray(t.size)
        for (i in 1 until t.size) {
            angle[i] = angle[i - 1] + ((w[i] - bias) + (w[i - 1] - bias)) / 2.0 * (t[i] - t[i - 1])
        }
        val tm = t.average()
        val am = angle.average()
        var num = 0.0
        var den = 0.0
        for (i in t.indices) {
            num += (t[i] - tm) * (angle[i] - am)
            den += (t[i] - tm) * (t[i] - tm)
        }
        val slope = if (den > 0) num / den else 0.0
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        for (i in t.indices) {
            val r = angle[i] - (am + slope * (t[i] - tm))
            lo = min(lo, r)
            hi = max(hi, r)
        }
        return hi - lo
    }

    private class Spectrum(val peakHz: Double, val peakConcentration: Double, val bandPercent: Double)

    /**
     * Rotational power spectrum (sum over the three axes) of the movement part of the gyroscope
     * signal. Resampled to a uniform grid (linear interpolation on real timestamps), Hann window,
     * DFT evaluated every 0.1 Hz from 1 to 20 Hz. Needs a measured rate of at least 30 Hz so that
     * 12 Hz lies safely below Nyquist. Returns null when the rate is too low.
     */
    private fun spectrum(t: DoubleArray, x: DoubleArray, y: DoubleArray, z: DoubleArray, rateHz: Double): Spectrum? {
        if (rateHz < MIN_RATE_FOR_FREQUENCY_HZ || t.size < 2) return null
        val fs = min(RESAMPLE_HZ, floor(rateHz))
        val duration = t.last() - t.first()
        val n = floor(duration * fs).toInt()
        if (n < fs * 4) return null // fewer than 4 s: resolution too coarse

        val signals = listOf(x, y, z).map { resample(t, it, t.first(), fs, n) }
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
        val maxHz = min(SPECTRUM_MAX_HZ, fs / 2.0 - 0.5)
        val freqs = generateSequence(SPECTRUM_MIN_HZ) { it + SPECTRUM_STEP_HZ }
            .takeWhile { it <= maxHz + 1e-9 }.toList()
        val power = DoubleArray(freqs.size)
        for ((k, f) in freqs.withIndex()) {
            val w = 2.0 * PI * f / fs
            var p = 0.0
            for (s in signals) {
                var re = 0.0
                var im = 0.0
                for (i in 0 until n) {
                    val v = s[i] * window[i]
                    re += v * cos(w * i)
                    im -= v * sin(w * i)
                }
                p += re * re + im * im
            }
            power[k] = p
        }
        val total = power.sum()
        if (total <= 0.0) return Spectrum(0.0, 0.0, 0.0)
        val band = freqs.indices.filter { freqs[it] >= BAND_LOW_HZ - 1e-9 && freqs[it] <= BAND_HIGH_HZ + 1e-9 }
        val peak = band.maxByOrNull { power[it] } ?: return Spectrum(0.0, 0.0, 0.0)
        val peakHz = freqs[peak]
        val nearPeak = freqs.indices.filter { abs(freqs[it] - peakHz) <= PEAK_HALF_WIDTH_HZ + 1e-9 }.sumOf { power[it] }
        return Spectrum(
            peakHz = (peakHz * 10).roundToInt() / 10.0,
            peakConcentration = nearPeak / total,
            bandPercent = band.sumOf { power[it] } * 100.0 / total
        )
    }

    private fun resample(t: DoubleArray, v: DoubleArray, start: Double, fs: Double, n: Int): DoubleArray {
        val out = DoubleArray(n)
        var j = 0
        for (i in 0 until n) {
            val ti = start + i / fs
            while (j < t.size - 2 && t[j + 1] < ti) j++
            val span = t[j + 1] - t[j]
            val f = if (span > 0) ((ti - t[j]) / span).coerceIn(0.0, 1.0) else 0.0
            out[i] = v[j] + (v[j + 1] - v[j]) * f
        }
        return out
    }

    // --- Quality -------------------------------------------------------------------------------

    private fun assessQuality(
        accel: StreamStats,
        gyro: StreamStats,
        metrics: StabilityMetrics?,
        offsetMs: Double
    ): StabilityQualityReport {
        val th = thresholds
        val issues = linkedSetOf<StabilityQualityIssue>()
        if (accel.validSamples == 0) issues += StabilityQualityIssue.NO_ACCELEROMETER_DATA
        if (gyro.validSamples == 0) issues += StabilityQualityIssue.NO_GYROSCOPE_DATA

        val streams = listOf(accel, gyro).filter { it.validSamples > 0 }
        for (s in streams) {
            if (s.samplingRateHz < th.minRateHz) issues += StabilityQualityIssue.SAMPLING_RATE_TOO_LOW
            else if (s.samplingRateHz < th.goodRateHz) issues += StabilityQualityIssue.LOW_SAMPLING_RATE

            if (s.completenessPercent < th.minCompletenessPercent) issues += StabilityQualityIssue.RECORDING_INCOMPLETE

            if (s.dropoutShare > th.maxGapShare || s.longestGapMs > th.maxLongestGapMs) {
                issues += StabilityQualityIssue.EXCESSIVE_GAPS
            } else if (s.dropoutShare > th.goodGapShare || s.longestGapMs > th.goodLongestGapMs) {
                issues += StabilityQualityIssue.GAPS_PRESENT
            }

            if (s.validPercent < th.minValidPercent) issues += StabilityQualityIssue.TOO_MANY_INVALID_SAMPLES
            else if (s.validPercent < th.goodValidPercent) issues += StabilityQualityIssue.INVALID_SAMPLES_PRESENT

            if (s.validSamples < th.minSamplesPerStream) issues += StabilityQualityIssue.TOO_FEW_SAMPLES
            if (s.unreliablePercent > th.maxUnreliablePercent) issues += StabilityQualityIssue.SENSOR_UNRELIABLE
        }
        if (streams.size == 2 && offsetMs > th.maxStreamOffsetMs) issues += StabilityQualityIssue.STREAMS_MISALIGNED
        if (metrics != null) {
            if (metrics.tiltChangeDeg > th.maxTiltChangeDeg) issues += StabilityQualityIssue.PHONE_REORIENTED
            if (metrics.accMagnitudeMean !in th.minGravity..th.maxGravity) issues += StabilityQualityIssue.GRAVITY_IMPLAUSIBLE
        }

        // Most severe first, declaration order within one severity.
        val sorted = issues.sortedWith(compareByDescending<StabilityQualityIssue> { it.severity.ordinal }.thenBy { it.ordinal })
        val status = sorted.maxOfOrNull { it.severity.ordinal }?.let { QualityStatus.entries[it] } ?: QualityStatus.VALID
        return StabilityQualityReport(status, sorted, qualityScore(accel, gyro))
    }

    /** 0..100 from the weakest stream: rate 30 %, completeness 30 %, validity 20 %, gaps 20 %. */
    private fun qualityScore(accel: StreamStats, gyro: StreamStats): Int {
        val th = thresholds
        fun one(s: StreamStats): Double {
            if (s.validSamples < 2) return 0.0
            val rate = (s.samplingRateHz / th.goodRateHz).coerceIn(0.0, 1.0)
            val complete = (s.completenessPercent / 100.0).coerceIn(0.0, 1.0)
            val valid = (s.validPercent / 100.0).coerceIn(0.0, 1.0)
            val gaps = 1.0 - (s.dropoutShare / th.maxGapShare).coerceIn(0.0, 1.0)
            return 30 * rate + 30 * complete + 20 * valid + 20 * gaps
        }
        return min(one(accel), one(gyro)).roundToInt().coerceIn(0, 100)
    }

    // --- Index ---------------------------------------------------------------------------------

    /**
     * Preliminary engineering index. Each component maps a movement RMS logarithmically between a
     * "sensor-noise level" reference (100) and a "large deliberate movement" reference (0).
     * The references are engineering values, not clinical cut-offs.
     */
    fun score(metrics: StabilityMetrics): StabilityIndex {
        val rotation = component(metrics.gyroDynamicRms, GYRO_REF_STILL_DEG_S, GYRO_REF_MOVING_DEG_S)
        val acceleration = component(metrics.accDynamicRms, ACC_REF_STILL, ACC_REF_MOVING)
        return StabilityIndex(
            total = ((rotation + acceleration) / 2.0).roundToInt(),
            rotationComponent = rotation.roundToInt(),
            accelerationComponent = acceleration.roundToInt()
        )
    }

    private fun component(value: Double, still: Double, moving: Double): Double {
        val v = max(value, still)
        return 100.0 * ((ln(moving) - ln(v)) / (ln(moving) - ln(still))).coerceIn(0.0, 1.0)
    }

    // --- helpers -------------------------------------------------------------------------------

    private fun norm(x: Double, y: Double, z: Double) = sqrt(x * x + y * y + z * z)

    private fun variance(v: DoubleArray, mean: Double): Double =
        if (v.size < 2) 0.0 else v.sumOf { (it - mean) * (it - mean) } / (v.size - 1)

    private fun angleDeg(x1: Double, y1: Double, z1: Double, x2: Double, y2: Double, z2: Double): Double {
        val n1 = norm(x1, y1, z1)
        val n2 = norm(x2, y2, z2)
        if (n1 == 0.0 || n2 == 0.0) return 0.0
        val c = ((x1 * x2 + y1 * y2 + z1 * z2) / (n1 * n2)).coerceIn(-1.0, 1.0)
        return acos(c) * RAD_TO_DEG
    }

    private fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = p * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = min(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    companion object {
        const val RAD_TO_DEG = 180.0 / PI

        /** +-16 g: beyond any phone accelerometer range = corrupt sample. */
        const val ACCEL_LIMIT = 16 * 9.80665

        /** 2000 deg/s, the usual gyroscope full scale. */
        const val GYRO_LIMIT_RAD_S = 2000.0 / RAD_TO_DEG

        const val MIN_FOR_METRICS = 3
        const val GAP_FACTOR = 3.0
        const val MIN_GAP_MS = 40.0

        const val MIN_RATE_FOR_FREQUENCY_HZ = 30.0
        const val RESAMPLE_HZ = 50.0
        const val SPECTRUM_MIN_HZ = 1.0
        const val SPECTRUM_MAX_HZ = 20.0
        const val SPECTRUM_STEP_HZ = 0.1
        const val BAND_LOW_HZ = 3.0
        const val BAND_HIGH_HZ = 12.0
        const val PEAK_HALF_WIDTH_HZ = 0.5

        /** Above typical MEMS gyroscope noise (~0.1-0.2 deg/s RMS). */
        const val MIN_OSCILLATION_RMS_DEG_S = 0.5

        /** A pure oscillation puts ~100 % of its power within +-0.5 Hz; white noise ~6 %. */
        const val MIN_PEAK_CONCENTRATION = 0.25

        const val GYRO_REF_STILL_DEG_S = 0.5
        const val GYRO_REF_MOVING_DEG_S = 30.0
        const val ACC_REF_STILL = 0.02
        const val ACC_REF_MOVING = 1.5
    }
}
