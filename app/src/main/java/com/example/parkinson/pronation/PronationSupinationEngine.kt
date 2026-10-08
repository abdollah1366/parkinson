package com.example.parkinson.pronation

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import com.example.parkinson.sensors.MotionStreams
import com.example.parkinson.sensors.MotionStreams.RAD_TO_DEG
import com.example.parkinson.sensors.StreamStats
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Versions stored with every Pronation/Supination result. Bump [ALGORITHM_VERSION] for any change
 * to preprocessing, detection, metrics or quality rules; bump [SCORING_VERSION] for any change to
 * the performance score. See docs/pronation-supination-algorithm.md.
 */
object PronationSupinationVersions {
    const val ALGORITHM_VERSION = "ps-algo-1.0.0"

    /** "research": engineering normalization, NOT clinically validated, non-diagnostic. */
    const val SCORING_VERSION = "ps-score-0.1.0-research"
}

/**
 * One recording. [samples]: everything received during RECORDING. [baselineSamples]: samples from
 * the preparation period (phone held still), used to estimate the initial orientation and the
 * gyroscope bias.
 */
data class PronationRecording(
    val samples: List<MotionSample>,
    val baselineSamples: List<MotionSample>,
    val plannedDurationMs: Long,
    val hand: SelectedHand
)

enum class BaselineStatus {
    /** Phone was still during preparation: gyroscope bias and gravity direction estimated. */
    CALIBRATED,

    /** Phone moved during preparation: no bias estimate (drift is removed per recording instead). */
    NOT_STILL,

    /** No preparation samples. */
    NO_DATA
}

/** Initial orientation / baseline from the preparation period. Vectors in device coordinates. */
data class RotationBaseline(
    val status: BaselineStatus,
    /** deg/s; zero unless [status] is CALIBRATED. */
    val gyroBiasDegS: List<Double>,
    /** m/s^2 mean accelerometer vector (initial gravity / orientation); null without data. */
    val gravity: List<Double>?
)

/** One movement between two consecutive turning points (one pronation or one supination). */
data class HalfCycle(
    /** Seconds from the start of the analysis window. */
    val startS: Double,
    val endS: Double,
    /** Rotation angle between the two turning points, degrees. */
    val amplitudeDeg: Double,
    /** Largest angular velocity about the rotation axis during this movement, deg/s. */
    val peakVelocityDegS: Double,
    /** false when a sensor dropout covers too much of it: excluded from timing and amplitude metrics. */
    val valid: Boolean
) {
    val durationS: Double get() = endS - startS
    val midS: Double get() = (startS + endS) / 2.0
}

/** One complete cycle = two consecutive half-cycles (pronation + supination). */
data class RotationCycle(
    val startS: Double,
    val durationS: Double,
    val amplitudeDeg: Double,
    val peakVelocityDegS: Double
)

enum class TrendDirection { STABLE, INCREASED, DECREASED }

/** One measure in the early, middle and late third of the recording. */
data class MetricTrend(
    val early: Double?,
    val middle: Double?,
    val late: Double?,
    /** (late - early) / early in percent; null when early or late could not be measured. */
    val changePercent: Double?,
    val direction: TrendDirection?
)

/** "روند عملکرد حرکتی": how the movement changed over the 10 seconds. Not disease progression. */
data class PerformanceTrend(
    val amplitudeDeg: MetricTrend,
    val peakVelocityDegS: MetricTrend,
    val cycleDurationMs: MetricTrend
)

/**
 * Raw metrics. Angles in degrees, angular velocity in deg/s about the main rotation axis, times
 * in ms, acceleration in m/s^2. Variability values are coefficients of variation in percent and
 * are null with fewer than 3 values.
 */
data class PronationMetrics(
    val halfCycleCount: Int,
    val cycleCount: Int,
    val validCycleCount: Int,
    val cycleRateHz: Double,
    val meanCycleDurationMs: Double?,
    val medianCycleDurationMs: Double?,
    val cycleDurationCvPercent: Double?,
    val angularVelocityMeanDegS: Double,
    /** Median of the per-movement peak angular velocities. */
    val angularVelocityPeakDegS: Double?,
    val angularVelocityCvPercent: Double?,
    val angularVelocityRmsDegS: Double,
    /** Median rotation angle per movement (turning point to turning point). */
    val movementAmplitudeDeg: Double?,
    val amplitudeCvPercent: Double?,
    /** Share of movements whose amplitude and duration are within +-30 % of the person's median. */
    val movementConsistencyPercent: Double?,
    val pauseCount: Int,
    val pauseDurationMs: Double,
    /** RMS of the linear (gravity-removed) acceleration of the phone. */
    val accelerationRms: Double,
    /** Reported only when one frequency clearly dominates and >= 3 cycles exist. */
    val dominantFrequencyHz: Double?,
    /** Share of rotational variance about the main axis: high = one consistent rotation axis. */
    val rotationAxisSharePercent: Double,
    val accMagnitudeMean: Double
)

enum class PronationQualityIssue(val severity: QualityStatus) {
    NO_ACCELEROMETER_DATA(QualityStatus.INVALID),
    NO_GYROSCOPE_DATA(QualityStatus.INVALID),
    SAMPLING_RATE_TOO_LOW(QualityStatus.INVALID),
    RECORDING_INCOMPLETE(QualityStatus.INVALID),
    EXCESSIVE_GAPS(QualityStatus.INVALID),
    TOO_MANY_INVALID_SAMPLES(QualityStatus.INVALID),

    TOO_FEW_SAMPLES(QualityStatus.INSUFFICIENT_DATA),
    NO_MOVEMENT_DETECTED(QualityStatus.INSUFFICIENT_DATA),
    TOO_FEW_VALID_CYCLES(QualityStatus.INSUFFICIENT_DATA),

    LOW_SAMPLING_RATE(QualityStatus.LOW_QUALITY),
    GAPS_PRESENT(QualityStatus.LOW_QUALITY),
    INVALID_SAMPLES_PRESENT(QualityStatus.LOW_QUALITY),
    SENSOR_UNRELIABLE(QualityStatus.LOW_QUALITY),
    STREAMS_MISALIGNED(QualityStatus.LOW_QUALITY),
    ROTATION_AXIS_UNSTABLE(QualityStatus.LOW_QUALITY),
    GRAVITY_IMPLAUSIBLE(QualityStatus.LOW_QUALITY)
}

/** Engineering limits for a usable recording (technical reliability, not the person). */
data class PronationQualityThresholds(
    /** 10 Hz over 10 s. */
    val minSamplesPerStream: Int = 100,
    /** Below this, turning-point timing of movements up to 6 Hz is not resolvable. */
    val minRateHz: Double = 25.0,
    val goodRateHz: Double = 50.0,
    val minCompletenessPercent: Double = 90.0,
    val minValidPercent: Double = 90.0,
    val goodValidPercent: Double = 99.0,
    val maxGapShare: Double = 0.20,
    val maxLongestGapMs: Double = 1_000.0,
    val goodGapShare: Double = 0.02,
    val goodLongestGapMs: Double = 250.0,
    val maxUnreliablePercent: Double = 10.0,
    val maxStreamOffsetMs: Double = 500.0,
    val minCycles: Int = 2,
    val minAxisSharePercent: Double = 60.0,
    val minGravity: Double = 6.0,
    val maxGravity: Double = 16.0
)

/** Signal-processing and detection parameters (engineering values, see the algorithm doc). */
data class PronationDetectionConfig(
    val maxGridHz: Double = 100.0,
    val lowPassHz: Double = 10.0,
    /** Minimum rotation between turning points that counts as a movement. */
    val minAmplitudeDeg: Double = 10.0,
    /** Hysteresis = max(minAmplitudeDeg, relativeHysteresis x the person's median amplitude). */
    val relativeHysteresis: Double = 0.3,
    /** Faster reversals are physiologically implausible for forearm rotation and are debounced. */
    val maxCycleFrequencyHz: Double = 6.0,
    /** A movement is excluded when a sensor dropout covers more than this share of it. */
    val maxGapShareOfHalfCycle: Double = 0.25,
    val pauseVelocityFraction: Double = 0.15,
    val pauseVelocityFloorDegS: Double = 5.0,
    val minPauseMs: Double = 400.0,
    val pauseCycleFraction: Double = 0.3,
    val consistencyTolerance: Double = 0.30,
    val trendStableBandPercent: Double = 15.0,
    val baselineWindowMs: Long = 2_000L,
    val stillGyroRmsDegS: Double = 10.0,
    val maxBiasDegS: Double = 5.0,
    val gravityTimeConstantS: Double = 1.0,
    val minFrequencyHz: Double = 0.2,
    val maxFrequencyHz: Double = 6.0,
    val frequencyStepHz: Double = 0.05,
    val peakHalfWidthHz: Double = 0.25,
    val minPeakConcentration: Double = 0.4
)

data class PronationQualityReport(
    val status: QualityStatus,
    /** Most severe first. */
    val issues: List<PronationQualityIssue>,
    /** 0..100 technical recording quality. */
    val qualityScore: Int
) {
    val primaryIssue: PronationQualityIssue? get() = issues.firstOrNull()
    val isUsable: Boolean get() = status == QualityStatus.VALID || status == QualityStatus.LOW_QUALITY
}

data class PronationAnalysis(
    val accel: StreamStats,
    val gyro: StreamStats,
    val baseline: RotationBaseline,
    val halfCycles: List<HalfCycle>,
    val cycles: List<RotationCycle>,
    /** null when the streams are too short to process. */
    val metrics: PronationMetrics?,
    val trend: PerformanceTrend?,
    val quality: PronationQualityReport,
    /** Only for VALID recordings. */
    val score: PronationSupinationPerformanceScore?,
    /** Overlap of both streams = the synchronized analysis window. */
    val analyzedDurationMs: Double,
    /** Hysteresis actually used for turning points, degrees. */
    val hysteresisDeg: Double
)

/**
 * Pronation/supination analysis. Pure and deterministic.
 *
 * Sensor events -> timestamp synchronization (overlap window, uniform grid on real timestamps)
 * -> preprocessing (cleaning, bias correction from the preparation baseline) -> main rotation
 * axis (PCA of angular velocity: independent of how the phone is held) -> zero-phase low-pass
 * -> rotation angle (integration, linear drift removal) -> turning points with adaptive
 * hysteresis + debounce + dropout handling -> metrics -> trend -> quality -> score.
 *
 * The gyroscope is the primary signal: it measures rotation only, so translating the phone does
 * not create cycles. The accelerometer gives the initial orientation, the linear acceleration
 * and plausibility checks.
 */
class PronationSupinationEngine(
    private val thresholds: PronationQualityThresholds = PronationQualityThresholds(),
    private val config: PronationDetectionConfig = PronationDetectionConfig()
) {

    fun analyze(recording: PronationRecording): PronationAnalysis {
        val accelRaw = recording.samples.filter { it.type == MotionSensorType.ACCELEROMETER }
        val gyroRaw = recording.samples.filter { it.type == MotionSensorType.GYROSCOPE }
        val accel = MotionStreams.clean(accelRaw, MotionStreams.ACCEL_LIMIT)
        val gyro = MotionStreams.clean(gyroRaw, MotionStreams.GYRO_LIMIT_RAD_S)
        val accelStats = MotionStreams.streamStats(MotionSensorType.ACCELEROMETER, accelRaw, accel, recording.plannedDurationMs)
        val gyroStats = MotionStreams.streamStats(MotionSensorType.GYROSCOPE, gyroRaw, gyro, recording.plannedDurationMs)
        val baseline = baseline(recording.baselineSamples)

        val signal = if (accel.size >= MIN_FOR_PROCESSING && gyro.size >= MIN_FOR_PROCESSING) {
            buildSignal(accel, gyro, gyroStats, baseline)
        } else null

        var halfCycles = emptyList<HalfCycle>()
        var cycles = emptyList<RotationCycle>()
        var metrics: PronationMetrics? = null
        var trend: PerformanceTrend? = null
        var hysteresis = config.minAmplitudeDeg
        if (signal != null) {
            val detection = detect(signal)
            halfCycles = detection.halfCycles
            hysteresis = detection.hysteresisDeg
            cycles = pairCycles(halfCycles)
            metrics = computeMetrics(signal, halfCycles, cycles, accel)
            trend = trend(halfCycles, signal.durationS)
        }

        val offsetMs = if (accel.isNotEmpty() && gyro.isNotEmpty()) {
            max(
                abs(accel.first().timestampNs - gyro.first().timestampNs),
                abs(accel.last().timestampNs - gyro.last().timestampNs)
            ) / 1e6
        } else 0.0
        val quality = assessQuality(accelStats, gyroStats, metrics, offsetMs)
        val score = if (quality.status == QualityStatus.VALID && metrics != null) {
            PronationSupinationScorer.score(metrics, quality)
        } else null
        return PronationAnalysis(
            accel = accelStats,
            gyro = gyroStats,
            baseline = baseline,
            halfCycles = halfCycles,
            cycles = cycles,
            metrics = metrics,
            trend = trend,
            quality = quality,
            score = score,
            analyzedDurationMs = (signal?.durationS ?: 0.0) * 1000.0,
            hysteresisDeg = hysteresis
        )
    }

    // --- Baseline (preparation period) ---------------------------------------------------------

    private fun baseline(samples: List<MotionSample>): RotationBaseline {
        val zero = listOf(0.0, 0.0, 0.0)
        val accel = MotionStreams.clean(samples.filter { it.type == MotionSensorType.ACCELEROMETER }, MotionStreams.ACCEL_LIMIT)
        val gyro = MotionStreams.clean(samples.filter { it.type == MotionSensorType.GYROSCOPE }, MotionStreams.GYRO_LIMIT_RAD_S)
        // Only the last part of the preparation: the phone is in position by then.
        fun lastWindow(s: List<MotionSample>): List<MotionSample> {
            if (s.isEmpty()) return s
            val from = s.last().timestampNs - config.baselineWindowMs * 1_000_000L
            return s.filter { it.timestampNs >= from }
        }
        val a = lastWindow(accel)
        val g = lastWindow(gyro)
        val gravity = if (a.isNotEmpty()) {
            listOf(a.sumOf { it.x.toDouble() } / a.size, a.sumOf { it.y.toDouble() } / a.size, a.sumOf { it.z.toDouble() } / a.size)
        } else null
        if (g.size < MIN_FOR_PROCESSING) return RotationBaseline(BaselineStatus.NO_DATA, zero, gravity)

        val mean = listOf(
            g.sumOf { it.x.toDouble() } / g.size * RAD_TO_DEG,
            g.sumOf { it.y.toDouble() } / g.size * RAD_TO_DEG,
            g.sumOf { it.z.toDouble() } / g.size * RAD_TO_DEG
        )
        val rms = sqrt(g.sumOf { s ->
            val x = s.x * RAD_TO_DEG
            val y = s.y * RAD_TO_DEG
            val z = s.z * RAD_TO_DEG
            x * x + y * y + z * z
        } / g.size)
        val biasNorm = sqrt(mean.sumOf { it * it })
        return if (rms <= config.stillGyroRmsDegS && biasNorm <= config.maxBiasDegS) {
            RotationBaseline(BaselineStatus.CALIBRATED, mean, gravity)
        } else {
            RotationBaseline(BaselineStatus.NOT_STILL, zero, gravity)
        }
    }

    // --- Synchronization and preprocessing -----------------------------------------------------

    /** All arrays share one uniform time grid over the overlap of both streams. */
    private class Signal(
        val fs: Double,
        val t: DoubleArray,
        /** Low-pass filtered angular velocity about the main axis, deg/s. */
        val omega: DoubleArray,
        /** Rotation angle about the main axis, deg, linear drift removed. */
        val angle: DoubleArray,
        /** Length of the raw gyroscope dropout that contains this grid point, ms (0 = none). */
        val gapMs: DoubleArray,
        /** Linear acceleration magnitude, m/s^2. */
        val linearAcc: DoubleArray,
        val axisSharePercent: Double
    ) {
        val n: Int get() = t.size
        val durationS: Double get() = if (t.isEmpty()) 0.0 else t.last() - t.first()
    }

    private fun buildSignal(
        accel: List<MotionSample>,
        gyro: List<MotionSample>,
        gyroStats: StreamStats,
        baseline: RotationBaseline
    ): Signal? {
        val t0 = max(accel.first().timestampNs, gyro.first().timestampNs)
        val t1 = min(accel.last().timestampNs, gyro.last().timestampNs)
        val spanS = (t1 - t0) / 1e9
        if (spanS < MIN_WINDOW_S || gyroStats.samplingRateHz <= 0.0) return null
        val fs = min(config.maxGridHz, floor(gyroStats.samplingRateHz)).coerceAtLeast(1.0)
        val n = floor(spanS * fs).toInt() + 1
        if (n < MIN_FOR_PROCESSING) return null
        val t = DoubleArray(n) { it / fs }

        val gapThresholdMs = max(MotionStreams.GAP_FACTOR * gyroStats.medianIntervalMs, MotionStreams.MIN_GAP_MS)
        val bias = baseline.gyroBiasDegS
        val g = resample(gyro, t0, fs, n, RAD_TO_DEG, gapThresholdMs)
        for (k in 0 until n) {
            g.x[k] -= bias[0]
            g.y[k] -= bias[1]
            g.z[k] -= bias[2]
        }
        val a = resample(accel, t0, fs, n, 1.0, Double.MAX_VALUE)

        // Main rotation axis: dominant eigenvector of the angular-velocity covariance.
        val (axis, share) = principalAxis(g.x, g.y, g.z)
        val raw = DoubleArray(n) { g.x[it] * axis[0] + g.y[it] * axis[1] + g.z[it] * axis[2] }
        val omega = lowPass(raw, min(config.lowPassHz, 0.4 * fs), fs)

        // Rotation angle (trapezoid on the grid), then linear drift removal (residual bias).
        val angle = DoubleArray(n)
        for (k in 1 until n) angle[k] = angle[k - 1] + (omega[k] + omega[k - 1]) / 2.0 / fs
        detrend(t, angle)

        val linear = linearAcceleration(a, g, fs, baseline.gravity)
        return Signal(fs, t, omega, angle, g.gap, linear, share * 100.0)
    }

    private class Grid(val x: DoubleArray, val y: DoubleArray, val z: DoubleArray, val gap: DoubleArray)

    /** Linear interpolation on real timestamps onto t0 + k / fs. Marks grid points inside dropouts. */
    private fun resample(s: List<MotionSample>, t0: Long, fs: Double, n: Int, scale: Double, gapThresholdMs: Double): Grid {
        val x = DoubleArray(n)
        val y = DoubleArray(n)
        val z = DoubleArray(n)
        val gap = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val tk = t0 + (k / fs * 1e9).toLong()
            while (j < s.size - 2 && s[j + 1].timestampNs < tk) j++
            val a = s[j]
            val b = s[min(j + 1, s.size - 1)]
            val span = (b.timestampNs - a.timestampNs).toDouble()
            val f = if (span > 0) ((tk - a.timestampNs) / span).coerceIn(0.0, 1.0) else 0.0
            x[k] = (a.x + (b.x - a.x) * f) * scale
            y[k] = (a.y + (b.y - a.y) * f) * scale
            z[k] = (a.z + (b.z - a.z) * f) * scale
            val spanMs = span / 1e6
            if (spanMs > gapThresholdMs) gap[k] = spanMs
        }
        return Grid(x, y, z, gap)
    }

    /** Dominant eigenvector (power iteration, deterministic start) and its share of the variance. */
    private fun principalAxis(x: DoubleArray, y: DoubleArray, z: DoubleArray): Pair<DoubleArray, Double> {
        val n = x.size
        val mx = x.average()
        val my = y.average()
        val mz = z.average()
        val c = Array(3) { DoubleArray(3) }
        for (k in 0 until n) {
            val v = doubleArrayOf(x[k] - mx, y[k] - my, z[k] - mz)
            for (i in 0..2) for (j in 0..2) c[i][j] += v[i] * v[j]
        }
        val trace = c[0][0] + c[1][1] + c[2][2]
        if (trace <= 0.0) return doubleArrayOf(1.0, 0.0, 0.0) to 0.0
        var v = DoubleArray(3).also { it[(0..2).maxBy { i -> c[i][i] }] = 1.0 }
        repeat(100) {
            val w = DoubleArray(3) { i -> c[i][0] * v[0] + c[i][1] * v[1] + c[i][2] * v[2] }
            val norm = sqrt(w.sumOf { it * it })
            if (norm == 0.0) return v to 0.0
            v = DoubleArray(3) { w[it] / norm }
        }
        // Deterministic sign: largest component positive.
        if (v[(0..2).maxBy { abs(v[it]) }] < 0) v = DoubleArray(3) { -v[it] }
        val lambda = (0..2).sumOf { i -> v[i] * (c[i][0] * v[0] + c[i][1] * v[1] + c[i][2] * v[2]) }
        return v to (lambda / trace).coerceIn(0.0, 1.0)
    }

    /** Zero-phase 2nd-order Butterworth low-pass (forward + backward), initialized at steady state. */
    private fun lowPass(v: DoubleArray, fc: Double, fs: Double): DoubleArray {
        if (v.size < 3 || fc <= 0.0 || fc >= fs / 2) return v.copyOf()
        val w0 = 2.0 * PI * fc / fs
        val alpha = sin(w0) / (2.0 * SQRT_HALF)
        val cw = cos(w0)
        val a0 = 1.0 + alpha
        val b0 = (1.0 - cw) / 2.0 / a0
        val b1 = (1.0 - cw) / a0
        val b2 = b0
        val a1 = -2.0 * cw / a0
        val a2 = (1.0 - alpha) / a0
        fun pass(input: DoubleArray): DoubleArray {
            val out = DoubleArray(input.size)
            var x1 = input[0]
            var x2 = input[0]
            var y1 = input[0]
            var y2 = input[0]
            for (i in input.indices) {
                val x0 = input[i]
                val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
                out[i] = y0
                x2 = x1; x1 = x0; y2 = y1; y1 = y0
            }
            return out
        }
        return pass(pass(v).reversedArray()).reversedArray()
    }

    private fun detrend(t: DoubleArray, v: DoubleArray) {
        val tm = t.average()
        val vm = v.average()
        var num = 0.0
        var den = 0.0
        for (i in t.indices) {
            num += (t[i] - tm) * (v[i] - vm)
            den += (t[i] - tm) * (t[i] - tm)
        }
        val slope = if (den > 0) num / den else 0.0
        for (i in t.indices) v[i] -= vm + slope * (t[i] - tm)
    }

    /**
     * Gravity tracked in device coordinates: rotated with the gyroscope (a world-fixed vector
     * changes as dv/dt = -w x v in the rotating frame) and slowly pulled towards the measured
     * acceleration (complementary filter). Linear acceleration = measured - gravity.
     */
    private fun linearAcceleration(a: Grid, g: Grid, fs: Double, initialGravity: List<Double>?): DoubleArray {
        val n = a.x.size
        val out = DoubleArray(n)
        val start = initialGravity ?: listOf(a.x[0], a.y[0], a.z[0])
        val mag = sqrt(start.sumOf { it * it }).takeIf { it > 0.1 } ?: STANDARD_GRAVITY
        var gx = start[0] / mag
        var gy = start[1] / mag
        var gz = start[2] / mag
        val dt = 1.0 / fs
        val k = (dt / config.gravityTimeConstantS).coerceIn(0.0, 1.0)
        for (i in 0 until n) {
            if (i > 0) {
                val wx = g.x[i] / RAD_TO_DEG
                val wy = g.y[i] / RAD_TO_DEG
                val wz = g.z[i] / RAD_TO_DEG
                val cx = wy * gz - wz * gy
                val cy = wz * gx - wx * gz
                val cz = wx * gy - wy * gx
                gx -= cx * dt; gy -= cy * dt; gz -= cz * dt
                val an = sqrt(a.x[i] * a.x[i] + a.y[i] * a.y[i] + a.z[i] * a.z[i])
                if (an > 0.1) {
                    gx = (1 - k) * gx + k * a.x[i] / an
                    gy = (1 - k) * gy + k * a.y[i] / an
                    gz = (1 - k) * gz + k * a.z[i] / an
                }
                val gn = sqrt(gx * gx + gy * gy + gz * gz)
                if (gn > 0) { gx /= gn; gy /= gn; gz /= gn }
            }
            val lx = a.x[i] - mag * gx
            val ly = a.y[i] - mag * gy
            val lz = a.z[i] - mag * gz
            out[i] = sqrt(lx * lx + ly * ly + lz * lz)
        }
        return out
    }

    // --- Movement detection --------------------------------------------------------------------

    private class Detection(val halfCycles: List<HalfCycle>, val hysteresisDeg: Double)

    private data class Extreme(val index: Int, val isMax: Boolean)

    /**
     * Two passes: the first with the absolute minimum amplitude finds the person's typical
     * movement size; the second uses a hysteresis normalized to it, so small reversals inside a
     * large movement are not counted as extra cycles, while small-amplitude movers are still seen.
     */
    private fun detect(s: Signal): Detection {
        val minHalfS = 1.0 / (2.0 * config.maxCycleFrequencyHz)
        val first = halfCycles(s, turningPoints(s, config.minAmplitudeDeg, minHalfS))
        val typical = median((first.filter { it.valid }.ifEmpty { first }).map { it.amplitudeDeg })
        val h = max(config.minAmplitudeDeg, config.relativeHysteresis * (typical ?: 0.0))
        val second = if (h > config.minAmplitudeDeg) halfCycles(s, turningPoints(s, h, minHalfS)) else first
        return Detection(second, h)
    }

    /**
     * Turning points of the rotation angle with hysteresis [h]: a maximum is confirmed once the
     * angle has fallen [h] below it (and vice versa). The movement before the first turning point
     * and after the last one is incomplete and not used. Reversals faster than [minHalfS] after
     * the previous turning point are implausible and debounced (merged).
     */
    private fun turningPoints(s: Signal, h: Double, minHalfS: Double): List<Extreme> {
        val theta = s.angle
        val ext = ArrayList<Extreme>()
        var dir = 0
        var hi = 0
        var lo = 0
        var cand = 0

        fun confirm(c: Int, isMax: Boolean) {
            val prev = ext.lastOrNull()
            if (prev != null && s.t[c] - s.t[prev.index] < minHalfS) {
                ext.removeAt(ext.size - 1)
                val before = ext.lastOrNull()
                if (before == null) {
                    ext += Extreme(c, isMax)
                } else if (if (isMax) theta[c] > theta[before.index] else theta[c] < theta[before.index]) {
                    ext[ext.size - 1] = Extreme(c, isMax)
                }
            } else {
                ext += Extreme(c, isMax)
            }
        }

        for (i in 1 until s.n) {
            when (dir) {
                0 -> {
                    if (theta[i] > theta[hi]) hi = i
                    if (theta[i] < theta[lo]) lo = i
                    if (theta[hi] - theta[lo] >= h) {
                        if (hi > lo) { dir = 1; cand = hi } else { dir = -1; cand = lo }
                    }
                }
                1 -> if (theta[i] >= theta[cand]) {
                    cand = i
                } else if (theta[cand] - theta[i] >= h) {
                    confirm(cand, isMax = true)
                    dir = -1
                    cand = i
                }
                else -> if (theta[i] <= theta[cand]) {
                    cand = i
                } else if (theta[i] - theta[cand] >= h) {
                    confirm(cand, isMax = false)
                    dir = 1
                    cand = i
                }
            }
        }
        return ext
    }

    private fun halfCycles(s: Signal, ext: List<Extreme>): List<HalfCycle> {
        val out = ArrayList<HalfCycle>(max(0, ext.size - 1))
        for (k in 0 until ext.size - 1) {
            val a = ext[k].index
            val b = ext[k + 1].index
            var peak = 0.0
            var maxGap = 0.0
            for (i in a..b) {
                peak = max(peak, abs(s.omega[i]))
                maxGap = max(maxGap, s.gapMs[i])
            }
            val duration = s.t[b] - s.t[a]
            out += HalfCycle(
                startS = s.t[a],
                endS = s.t[b],
                amplitudeDeg = abs(s.angle[b] - s.angle[a]),
                peakVelocityDegS = peak,
                valid = maxGap <= config.maxGapShareOfHalfCycle * duration * 1000.0
            )
        }
        return out
    }

    /** Cycle k = half-cycles 2k and 2k+1; only cycles made of two valid movements are kept. */
    private fun pairCycles(h: List<HalfCycle>): List<RotationCycle> {
        val out = ArrayList<RotationCycle>()
        var k = 0
        while (k + 1 < h.size) {
            val a = h[k]
            val b = h[k + 1]
            if (a.valid && b.valid) {
                out += RotationCycle(
                    startS = a.startS,
                    durationS = a.durationS + b.durationS,
                    amplitudeDeg = (a.amplitudeDeg + b.amplitudeDeg) / 2.0,
                    peakVelocityDegS = max(a.peakVelocityDegS, b.peakVelocityDegS)
                )
            }
            k += 2
        }
        return out
    }

    // --- Metrics -------------------------------------------------------------------------------

    private fun computeMetrics(
        s: Signal,
        half: List<HalfCycle>,
        cycles: List<RotationCycle>,
        accel: List<MotionSample>
    ): PronationMetrics {
        val valid = half.filter { it.valid }
        val durationsMs = cycles.map { it.durationS * 1000.0 }
        val amplitudes = valid.map { it.amplitudeDeg }
        val peaks = valid.map { it.peakVelocityDegS }
        val medianPeak = median(peaks)

        // Active period: first to last turning point (or the whole window without movement).
        val from = half.firstOrNull()?.let { index(s, it.startS) } ?: 0
        val to = half.lastOrNull()?.let { index(s, it.endS) } ?: (s.n - 1)
        var absSum = 0.0
        var sqSum = 0.0
        var count = 0
        for (i in from..to) {
            if (s.gapMs[i] > 0) continue
            absSum += abs(s.omega[i])
            sqSum += s.omega[i] * s.omega[i]
            count++
        }

        val medianDuration = median(durationsMs)
        val (pauses, pauseMs) = pauses(s, from, to, medianPeak, medianDuration)

        val consistency = if (valid.size >= 3) {
            val medAmp = median(amplitudes)!!
            val medDur = median(valid.map { it.durationS })!!
            val tol = config.consistencyTolerance
            valid.count {
                abs(it.amplitudeDeg / medAmp - 1.0) <= tol && abs(it.durationS / medDur - 1.0) <= tol
            } * 100.0 / valid.size
        } else null

        val linearRms = sqrt(s.linearAcc.sumOf { it * it } / s.n)
        val accMag = accel.map { sqrt(it.x.toDouble() * it.x + it.y.toDouble() * it.y + it.z.toDouble() * it.z) }

        val cycleCount = half.size / 2
        return PronationMetrics(
            halfCycleCount = half.size,
            cycleCount = cycleCount,
            validCycleCount = cycles.size,
            cycleRateHz = if (s.durationS > 0) half.size / 2.0 / s.durationS else 0.0,
            meanCycleDurationMs = durationsMs.takeIf { it.isNotEmpty() }?.average(),
            medianCycleDurationMs = medianDuration,
            cycleDurationCvPercent = cv(durationsMs),
            angularVelocityMeanDegS = if (count > 0) absSum / count else 0.0,
            angularVelocityPeakDegS = medianPeak,
            angularVelocityCvPercent = cv(peaks),
            angularVelocityRmsDegS = if (count > 0) sqrt(sqSum / count) else 0.0,
            movementAmplitudeDeg = median(amplitudes),
            amplitudeCvPercent = cv(amplitudes),
            movementConsistencyPercent = consistency,
            pauseCount = pauses,
            pauseDurationMs = pauseMs,
            accelerationRms = linearRms,
            dominantFrequencyHz = if (cycles.size >= 3) dominantFrequency(s, from, to) else null,
            rotationAxisSharePercent = s.axisSharePercent,
            accMagnitudeMean = if (accMag.isEmpty()) 0.0 else accMag.average()
        )
    }

    /**
     * Pauses inside the active period: angular velocity below a threshold relative to the person's
     * own peak speed, for at least max(400 ms, 30 % of a typical cycle). Dropouts break a pause.
     */
    private fun pauses(s: Signal, from: Int, to: Int, medianPeak: Double?, medianCycleMs: Double?): Pair<Int, Double> {
        if (medianPeak == null || to <= from) return 0 to 0.0
        val threshold = max(config.pauseVelocityFloorDegS, config.pauseVelocityFraction * medianPeak)
        val minMs = max(config.minPauseMs, config.pauseCycleFraction * (medianCycleMs ?: 0.0))
        var count = 0
        var total = 0.0
        var runStart = -1
        fun close(end: Int) {
            if (runStart < 0) return
            val ms = (s.t[end] - s.t[runStart]) * 1000.0
            if (ms >= minMs) {
                count++
                total += ms
            }
            runStart = -1
        }
        for (i in from..to) {
            val still = abs(s.omega[i]) < threshold && s.gapMs[i] == 0.0
            if (still && runStart < 0) runStart = i
            if (!still) close(i - 1)
        }
        close(to)
        return count to total
    }

    /** Dominant movement frequency of the rotational velocity, if one frequency clearly dominates. */
    private fun dominantFrequency(s: Signal, from: Int, to: Int): Double? {
        val n = to - from + 1
        if (n < s.fs * 4) return null
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2.0 * PI * it / (n - 1)) }
        val mean = (from..to).sumOf { s.omega[it] } / n
        val maxHz = min(config.maxFrequencyHz, s.fs / 2.0 - 0.5)
        val freqs = generateSequence(config.minFrequencyHz) { it + config.frequencyStepHz }
            .takeWhile { it <= maxHz + 1e-9 }.toList()
        if (freqs.isEmpty()) return null
        val power = DoubleArray(freqs.size)
        for ((k, f) in freqs.withIndex()) {
            val w = 2.0 * PI * f / s.fs
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val v = (s.omega[from + i] - mean) * window[i]
                re += v * cos(w * i)
                im -= v * sin(w * i)
            }
            power[k] = re * re + im * im
        }
        val total = power.sum()
        if (total <= 0.0) return null
        val peak = power.indices.maxBy { power[it] }
        val near = freqs.indices.filter { abs(freqs[it] - freqs[peak]) <= config.peakHalfWidthHz + 1e-9 }.sumOf { power[it] }
        if (near / total < config.minPeakConcentration) return null
        return (freqs[peak] * 100).roundToInt() / 100.0
    }

    // --- Trend ---------------------------------------------------------------------------------

    private fun trend(half: List<HalfCycle>, durationS: Double): PerformanceTrend? {
        if (durationS <= 0.0) return null
        val valid = half.filter { it.valid }
        val third = durationS / 3.0
        val segments = (0..2).map { seg ->
            valid.filter { it.midS >= seg * third && (it.midS < (seg + 1) * third || seg == 2) }
                .takeIf { it.size >= MIN_HALF_CYCLES_PER_SEGMENT }
        }
        fun measure(f: (List<HalfCycle>) -> Double?): MetricTrend {
            val values = segments.map { it?.let(f) }
            val early = values[0]
            val late = values[2]
            val change = if (early != null && late != null && early > 0) (late - early) / early * 100.0 else null
            val direction = change?.let {
                when {
                    abs(it) < config.trendStableBandPercent -> TrendDirection.STABLE
                    it > 0 -> TrendDirection.INCREASED
                    else -> TrendDirection.DECREASED
                }
            }
            return MetricTrend(early, values[1], late, change, direction)
        }
        return PerformanceTrend(
            amplitudeDeg = measure { seg -> median(seg.map { it.amplitudeDeg }) },
            peakVelocityDegS = measure { seg -> median(seg.map { it.peakVelocityDegS }) },
            cycleDurationMs = measure { seg -> median(seg.map { it.durationS })?.let { it * 2000.0 } }
        )
    }

    // --- Quality -------------------------------------------------------------------------------

    private fun assessQuality(
        accel: StreamStats,
        gyro: StreamStats,
        metrics: PronationMetrics?,
        offsetMs: Double
    ): PronationQualityReport {
        val th = thresholds
        val issues = linkedSetOf<PronationQualityIssue>()
        if (accel.validSamples == 0) issues += PronationQualityIssue.NO_ACCELEROMETER_DATA
        if (gyro.validSamples == 0) issues += PronationQualityIssue.NO_GYROSCOPE_DATA

        val streams = listOf(accel, gyro).filter { it.validSamples > 0 }
        for (s in streams) {
            if (s.samplingRateHz < th.minRateHz) issues += PronationQualityIssue.SAMPLING_RATE_TOO_LOW
            else if (s.samplingRateHz < th.goodRateHz) issues += PronationQualityIssue.LOW_SAMPLING_RATE

            if (s.completenessPercent < th.minCompletenessPercent) issues += PronationQualityIssue.RECORDING_INCOMPLETE

            if (s.dropoutShare > th.maxGapShare || s.longestGapMs > th.maxLongestGapMs) {
                issues += PronationQualityIssue.EXCESSIVE_GAPS
            } else if (s.dropoutShare > th.goodGapShare || s.longestGapMs > th.goodLongestGapMs) {
                issues += PronationQualityIssue.GAPS_PRESENT
            }

            if (s.validPercent < th.minValidPercent) issues += PronationQualityIssue.TOO_MANY_INVALID_SAMPLES
            else if (s.validPercent < th.goodValidPercent) issues += PronationQualityIssue.INVALID_SAMPLES_PRESENT

            if (s.validSamples < th.minSamplesPerStream) issues += PronationQualityIssue.TOO_FEW_SAMPLES
            if (s.unreliablePercent > th.maxUnreliablePercent) issues += PronationQualityIssue.SENSOR_UNRELIABLE
        }
        if (streams.size == 2 && offsetMs > th.maxStreamOffsetMs) issues += PronationQualityIssue.STREAMS_MISALIGNED

        if (metrics == null) {
            if (streams.size == 2) issues += PronationQualityIssue.TOO_FEW_SAMPLES
        } else {
            if (metrics.cycleCount < th.minCycles) {
                issues += PronationQualityIssue.NO_MOVEMENT_DETECTED
            } else {
                if (metrics.validCycleCount < th.minCycles) issues += PronationQualityIssue.TOO_FEW_VALID_CYCLES
                if (metrics.rotationAxisSharePercent < th.minAxisSharePercent) issues += PronationQualityIssue.ROTATION_AXIS_UNSTABLE
            }
            if (metrics.accMagnitudeMean !in th.minGravity..th.maxGravity) issues += PronationQualityIssue.GRAVITY_IMPLAUSIBLE
        }

        val sorted = issues.sortedWith(compareByDescending<PronationQualityIssue> { it.severity.ordinal }.thenBy { it.ordinal })
        val status = sorted.maxOfOrNull { it.severity.ordinal }?.let { QualityStatus.entries[it] } ?: QualityStatus.VALID
        return PronationQualityReport(status, sorted, qualityScore(accel, gyro))
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

    // --- helpers -------------------------------------------------------------------------------

    private fun index(s: Signal, timeS: Double): Int = (timeS * s.fs).roundToInt().coerceIn(0, s.n - 1)

    companion object {
        const val MIN_FOR_PROCESSING = 3
        const val MIN_WINDOW_S = 1.0
        const val MIN_HALF_CYCLES_PER_SEGMENT = 2
        const val STANDARD_GRAVITY = 9.80665
        private val SQRT_HALF = sqrt(0.5)

        fun median(values: List<Double>): Double? =
            if (values.isEmpty()) null else MotionStreams.percentile(values.sorted(), 0.5)

        /** Coefficient of variation (sample SD / mean) in percent; null with fewer than 3 values. */
        fun cv(values: List<Double>): Double? {
            if (values.size < 3) return null
            val mean = values.average()
            if (mean <= 0.0) return null
            val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
            return sd / mean * 100.0
        }
    }
}
