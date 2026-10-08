package com.example.parkinson.pronation

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
 * Pronation/supination analysis. Pure, deterministic, independent of Android UI.
 *
 * Raw sensor data -> timestamp validation (cleaning) -> synchronization (overlap window, uniform
 * grid on real timestamps) -> baseline correction (bias from the still preparation) -> noise
 * estimation -> filtering (zero-phase low-pass) -> rotational signal (main rotation axis, angle)
 * -> adaptive cycle detection -> cycle validation -> metrics -> quality -> score ->
 * interpretation.
 *
 * The gyroscope is the primary signal: it measures rotation only, so translating the phone does
 * not create cycles. The accelerometer (optional) gives the initial orientation, the linear
 * acceleration and plausibility checks.
 */
class PronationSupinationEngine(
    private val config: PronationDetectionConfig = PronationDetectionConfig(),
    private val qualityEngine: PronationSupinationQualityEngine = PronationSupinationQualityEngine(),
    private val scoreEngine: PronationSupinationScoreEngine = PronationSupinationScoreEngine()
) {

    fun analyze(recording: PronationRecording): PronationAnalysis {
        val accelRaw = recording.samples.filter { it.type == MotionSensorType.ACCELEROMETER }
        val gyroRaw = recording.samples.filter { it.type == MotionSensorType.GYROSCOPE }
        val accel = MotionStreams.clean(accelRaw, MotionStreams.ACCEL_LIMIT)
        val gyro = MotionStreams.clean(gyroRaw, MotionStreams.GYRO_LIMIT_RAD_S)
        val accelStats = MotionStreams.streamStats(MotionSensorType.ACCELEROMETER, accelRaw, accel, recording.plannedDurationMs)
        val gyroStats = MotionStreams.streamStats(MotionSensorType.GYROSCOPE, gyroRaw, gyro, recording.plannedDurationMs)
        val hasAccel = recording.accelerometerAvailable && accel.size >= MIN_FOR_PROCESSING
        val baseline = baseline(recording.baselineSamples)

        val signal = if (gyro.size >= MIN_FOR_PROCESSING) {
            buildSignal(if (hasAccel) accel else emptyList(), gyro, gyroStats, baseline, recording.hand)
        } else null

        var movements = emptyList<RotationMovement>()
        var cycles = emptyList<RotationCycle>()
        var metrics: PronationMetrics? = null
        var hysteresis = config.minAmplitudeDeg
        if (signal != null) {
            hysteresis = hysteresis(signal, baseline)
            movements = movements(signal, turningPoints(signal, hysteresis))
            cycles = pairCycles(movements)
            metrics = computeMetrics(signal, movements, cycles, if (hasAccel) accel else emptyList())
        }

        val offsetMs = if (hasAccel && gyro.isNotEmpty()) {
            max(
                abs(accel.first().timestampNs - gyro.first().timestampNs),
                abs(accel.last().timestampNs - gyro.last().timestampNs)
            ) / 1e6
        } else 0.0
        val windowMs = (signal?.durationS ?: 0.0) * 1000.0
        val quality = qualityEngine.assess(accelStats, gyroStats, hasAccel, metrics, windowMs, offsetMs)
        val score = if (quality.isUsable && metrics != null) {
            scoreEngine.score(metrics, quality, ReferenceContext(recording.hand))
        } else null
        val interpretation = PronationSupinationInterpreter.interpret(score, quality, metrics, hasAccel)

        return PronationAnalysis(
            accel = accelStats,
            gyro = gyroStats,
            accelerometerAvailable = hasAccel,
            baseline = baseline,
            movements = movements,
            cycles = cycles,
            metrics = metrics,
            quality = quality,
            score = score,
            interpretation = interpretation,
            analyzedDurationMs = windowMs,
            hysteresisDeg = hysteresis,
            velocityTrace = signal?.let { trace(it) } ?: emptyList()
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
        if (g.size < MIN_FOR_PROCESSING) return RotationBaseline(BaselineStatus.NO_DATA, zero, null, gravity)

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
        // Noise = spread around the mean (bias removed).
        val noise = sqrt(g.sumOf { s ->
            val x = s.x * RAD_TO_DEG - mean[0]
            val y = s.y * RAD_TO_DEG - mean[1]
            val z = s.z * RAD_TO_DEG - mean[2]
            x * x + y * y + z * z
        } / g.size)
        val biasNorm = sqrt(mean.sumOf { it * it })
        return if (rms <= config.stillGyroRmsDegS && biasNorm <= config.maxBiasDegS) {
            RotationBaseline(BaselineStatus.CALIBRATED, mean, noise, gravity)
        } else {
            RotationBaseline(BaselineStatus.NOT_STILL, zero, null, gravity)
        }
    }

    // --- Synchronization and preprocessing -----------------------------------------------------

    /** All arrays share one uniform time grid over the synchronized window. */
    private class Signal(
        val fs: Double,
        val t: DoubleArray,
        /** Filtered angular velocity about the main axis, deg/s (sign: see [orientAxis]). */
        val omega: DoubleArray,
        /** Rotation angle about the main axis, deg, linear drift removed. */
        val angle: DoubleArray,
        /** Length of the raw gyroscope dropout that contains this grid point, ms (0 = none). */
        val gapMs: DoubleArray,
        /** Linear acceleration magnitude, m/s^2; null without accelerometer. */
        val linearAcc: DoubleArray?,
        val axisSharePercent: Double,
        val axisAlignedWithForearm: Boolean,
        val hand: SelectedHand,
        /** RMS of (raw - filtered) rotation signal. */
        val noiseDegS: Double
    ) {
        val n: Int get() = t.size
        val durationS: Double get() = if (t.isEmpty()) 0.0 else t.last() - t.first()
    }

    private fun buildSignal(
        accel: List<MotionSample>,
        gyro: List<MotionSample>,
        gyroStats: StreamStats,
        baseline: RotationBaseline,
        hand: SelectedHand
    ): Signal? {
        val hasAccel = accel.isNotEmpty()
        val t0 = if (hasAccel) max(accel.first().timestampNs, gyro.first().timestampNs) else gyro.first().timestampNs
        val t1 = if (hasAccel) min(accel.last().timestampNs, gyro.last().timestampNs) else gyro.last().timestampNs
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

        // Main rotation axis from the movement itself (the still preparation has no rotation to
        // learn it from); validated by its share of the rotational variance.
        val (rawAxis, share) = principalAxis(g.x, g.y, g.z)
        val (axis, aligned) = orientAxis(rawAxis)
        val raw = DoubleArray(n) { g.x[it] * axis[0] + g.y[it] * axis[1] + g.z[it] * axis[2] }
        val omega = lowPass(raw, min(config.lowPassHz, config.maxLowPassFractionOfRate * fs), fs)
        val noise = sqrt((0 until n).sumOf { (raw[it] - omega[it]) * (raw[it] - omega[it]) } / n)

        val angle = DoubleArray(n)
        for (k in 1 until n) angle[k] = angle[k - 1] + (omega[k] + omega[k - 1]) / 2.0 / fs
        detrend(t, angle)

        val linear = if (hasAccel) {
            linearAcceleration(resample(accel, t0, fs, n, 1.0, Double.MAX_VALUE), g, fs, baseline.gravity)
        } else null
        return Signal(fs, t, omega, angle, g.gap, linear, share * 100.0, aligned, hand, noise)
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
        if (trace <= 0.0) return doubleArrayOf(0.0, 1.0, 0.0) to 0.0
        var v = DoubleArray(3).also { it[(0..2).maxBy { i -> c[i][i] }] = 1.0 }
        repeat(POWER_ITERATIONS) {
            val w = DoubleArray(3) { i -> c[i][0] * v[0] + c[i][1] * v[1] + c[i][2] * v[2] }
            val norm = sqrt(w.sumOf { it * it })
            if (norm == 0.0) return v to 0.0
            v = DoubleArray(3) { w[it] / norm }
        }
        val lambda = (0..2).sumOf { i -> v[i] * (c[i][0] * v[0] + c[i][1] * v[1] + c[i][2] * v[2]) }
        return v to (lambda / trace).coerceIn(0.0, 1.0)
    }

    /**
     * Deterministic axis sign. Under the standard grip (phone in the palm, top towards the
     * fingers) the forearm axis is the phone's long (y) axis: the axis then points to +y so the
     * angle sign has an anatomical meaning (see [directionOf]). Otherwise the largest component is
     * made positive and directions stay UNKNOWN.
     */
    private fun orientAxis(v: DoubleArray): Pair<DoubleArray, Boolean> {
        val aligned = abs(v[1]) >= config.forearmAxisMinCosine
        val sign = if (aligned) (if (v[1] < 0) -1.0 else 1.0) else (if (v[(0..2).maxBy { abs(v[it]) }] < 0) -1.0 else 1.0)
        return DoubleArray(3) { v[it] * sign } to aligned
    }

    /**
     * Rotation about +y (phone long axis). For the RIGHT hand (palm up, thumb on +x) pronation
     * turns the thumb up (+x towards +z) = rotation about -y = angle decreasing. The left hand is
     * the mirror image. The hand comes from the user's explicit selection.
     */
    private fun directionOf(s: Signal, angleIncreasing: Boolean): RotationDirection {
        if (!s.axisAlignedWithForearm) return RotationDirection.UNKNOWN
        val pronation = if (s.hand == SelectedHand.RIGHT) !angleIncreasing else angleIncreasing
        return if (pronation) RotationDirection.PRONATION else RotationDirection.SUPINATION
    }

    /** Zero-phase 2nd-order Butterworth low-pass (forward + backward), initialized at steady state. */
    private fun lowPass(v: DoubleArray, fc: Double, fs: Double): DoubleArray {
        if (v.size < 3 || fc <= 0.0 || fc >= fs / 2) return v.copyOf()
        val w0 = 2.0 * PI * fc / fs
        val alpha = sin(w0) / (2.0 * BUTTERWORTH_Q)
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
        val mag = sqrt(start.sumOf { it * it }).takeIf { it > MIN_GRAVITY_NORM } ?: STANDARD_GRAVITY
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
                if (an > MIN_GRAVITY_NORM) {
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

    // --- Cycle detection -----------------------------------------------------------------------

    private data class Extreme(val index: Int, val isMax: Boolean)

    /**
     * Adaptive hysteresis (degrees): the largest of
     *  - the minimum movement amplitude (tiny movements are not movements),
     *  - a noise term from the still baseline (random shaking must not create reversals),
     *  - relativeHysteresis x the person's median amplitude from a first pass (small reversals
     *    inside large movements are not extra cycles, small-amplitude movers are still detected).
     */
    private fun hysteresis(s: Signal, baseline: RotationBaseline): Double {
        val noiseTerm = config.noiseHysteresisFactor * (baseline.noiseDegS ?: 0.0) * config.noiseIntegrationS
        val floor = max(config.minAmplitudeDeg, noiseTerm)
        val first = movements(s, turningPoints(s, floor))
        val typical = median((first.filter { it.valid }.ifEmpty { first }).map { it.amplitudeDeg }) ?: 0.0
        return max(floor, config.relativeHysteresis * typical)
    }

    /**
     * Turning points of the rotation angle with hysteresis [h]: a maximum is confirmed once the
     * angle has fallen [h] below it (and vice versa) - movement direction must really reverse.
     * The movement before the first and after the last turning point is incomplete and not used.
     * A reversal sooner than the minimum peak distance after the previous turning point is
     * implausible and debounced (the pair is merged), which also prevents duplicate peaks.
     */
    private fun turningPoints(s: Signal, h: Double): List<Extreme> {
        val minDistanceS = 1.0 / (2.0 * config.maxCycleFrequencyHz)
        val theta = s.angle
        val ext = ArrayList<Extreme>()
        var dir = 0
        var hi = 0
        var lo = 0
        var cand = 0

        fun confirm(c: Int, isMax: Boolean) {
            val prev = ext.lastOrNull()
            if (prev != null && s.t[c] - s.t[prev.index] < minDistanceS) {
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

    /** Movements between consecutive turning points, validated (sensor gaps, implausibly slow). */
    private fun movements(s: Signal, ext: List<Extreme>): List<RotationMovement> {
        val out = ArrayList<RotationMovement>(max(0, ext.size - 1))
        val maxMovementS = config.maxCycleDurationS / 2.0
        for (k in 0 until ext.size - 1) {
            val a = ext[k].index
            val b = ext[k + 1].index
            var peak = 0.0
            var peakIndex = a
            var sum = 0.0
            var maxGap = 0.0
            for (i in a..b) {
                val w = abs(s.omega[i])
                if (w > peak) { peak = w; peakIndex = i }
                sum += w
                maxGap = max(maxGap, s.gapMs[i])
            }
            val duration = s.t[b] - s.t[a]
            val rejection = when {
                maxGap > config.maxGapShareOfMovement * duration * 1000.0 -> MovementRejection.SENSOR_GAP
                duration > maxMovementS -> MovementRejection.TOO_SLOW
                else -> null
            }
            out += RotationMovement(
                startS = s.t[a],
                peakS = s.t[peakIndex],
                endS = s.t[b],
                amplitudeDeg = abs(s.angle[b] - s.angle[a]),
                peakAngularVelocityDegS = peak,
                meanAngularVelocityDegS = sum / (b - a + 1),
                direction = directionOf(s, angleIncreasing = s.angle[b] > s.angle[a]),
                rejection = rejection
            )
        }
        return out
    }

    /** Cycle k = movements 2k and 2k+1 (non-overlapping); valid only when both movements are. */
    private fun pairCycles(m: List<RotationMovement>): List<RotationCycle> {
        val out = ArrayList<RotationCycle>()
        var k = 0
        while (k + 1 < m.size) {
            val a = m[k]
            val b = m[k + 1]
            val peakFirst = a.peakAngularVelocityDegS >= b.peakAngularVelocityDegS
            out += RotationCycle(
                startS = a.startS,
                peakS = if (peakFirst) a.peakS else b.peakS,
                endS = b.endS,
                amplitudeDeg = (a.amplitudeDeg + b.amplitudeDeg) / 2.0,
                peakAngularVelocityDegS = max(a.peakAngularVelocityDegS, b.peakAngularVelocityDegS),
                meanAngularVelocityDegS = (a.meanAngularVelocityDegS * a.durationS + b.meanAngularVelocityDegS * b.durationS) /
                    (a.durationS + b.durationS),
                firstDirection = a.direction,
                valid = a.valid && b.valid
            )
            k += 2
        }
        return out
    }

    // --- Metrics -------------------------------------------------------------------------------

    private fun computeMetrics(
        s: Signal,
        moves: List<RotationMovement>,
        cycles: List<RotationCycle>,
        accel: List<MotionSample>
    ): PronationMetrics {
        val valid = moves.filter { it.valid }
        val validCycles = cycles.filter { it.valid }
        val durationsMs = validCycles.map { it.durationS * 1000.0 }
        val amplitudes = valid.map { it.amplitudeDeg }
        val peaks = valid.map { it.peakAngularVelocityDegS }
        val medianPeak = median(peaks)

        val from = moves.firstOrNull()?.let { index(s, it.startS) } ?: 0
        val to = moves.lastOrNull()?.let { index(s, it.endS) } ?: (s.n - 1)
        var absSum = 0.0
        var sqSum = 0.0
        var count = 0
        if (moves.isNotEmpty()) {
            for (i in from..to) {
                if (s.gapMs[i] > 0) continue
                absSum += abs(s.omega[i])
                sqSum += s.omega[i] * s.omega[i]
                count++
            }
        }
        val medianDuration = median(durationsMs)
        val pauses = pauses(s, from, to, medianPeak, medianDuration, moves.isNotEmpty())
        val cyclesPerSecond = if (s.durationS > 0) moves.size / 2.0 / s.durationS else 0.0
        val accMag = accel.map { sqrt(it.x.toDouble() * it.x + it.y.toDouble() * it.y + it.z.toDouble() * it.z) }

        return PronationMetrics(
            movementCount = moves.size,
            cycleCount = cycles.size,
            validCycleCount = validCycles.size,
            cyclesPerSecond = cyclesPerSecond,
            cyclesPerMinute = cyclesPerSecond * 60.0,
            meanCycleDurationMs = durationsMs.takeIf { it.isNotEmpty() }?.average(),
            medianCycleDurationMs = medianDuration,
            cycleDurationCvPercent = cv(durationsMs),
            meanAmplitudeDeg = amplitudes.takeIf { it.isNotEmpty() }?.average(),
            medianAmplitudeDeg = median(amplitudes),
            amplitudeCvPercent = cv(amplitudes),
            meanAngularVelocityDegS = if (count > 0) absSum / count else 0.0,
            peakAngularVelocityDegS = medianPeak,
            maxAngularVelocityDegS = peaks.maxOrNull(),
            velocityCvPercent = cv(peaks),
            angularVelocityRmsDegS = if (count > 0) sqrt(sqSum / count) else 0.0,
            pauseCount = pauses.count,
            totalPauseMs = pauses.totalMs,
            longestPauseMs = pauses.longestMs,
            withinTolerancePercent = withinTolerance(valid),
            movementCoveragePercent = if (moves.isEmpty() || s.durationS <= 0) 0.0 else (s.t[to] - s.t[from]) / s.durationS * 100.0,
            noiseLevelDegS = s.noiseDegS,
            accelerationRms = s.linearAcc?.let { lin -> sqrt(lin.sumOf { it * it } / lin.size) },
            accMagnitudeMean = accMag.takeIf { it.isNotEmpty() }?.average(),
            dominantFrequencyHz = if (validCycles.size >= MIN_CYCLES_FOR_FREQUENCY) dominantFrequency(s, from, to) else null,
            rotationAxisSharePercent = s.axisSharePercent,
            axisAlignedWithForearm = s.axisAlignedWithForearm,
            segments = segments(valid, s.durationS),
            measureTrends = measureTrends(valid, s.durationS)
        )
    }

    /** Share of movements within the tolerance of the typical amplitude and duration; >= 3 movements. */
    private fun withinTolerance(valid: List<RotationMovement>): Double? {
        if (valid.size < MIN_VALUES_FOR_VARIABILITY) return null
        val medAmp = median(valid.map { it.amplitudeDeg })!!
        val medDur = median(valid.map { it.durationS })!!
        val tol = config.consistencyTolerance
        return valid.count {
            abs(it.amplitudeDeg / medAmp - 1.0) <= tol && abs(it.durationS / medDur - 1.0) <= tol
        } * 100.0 / valid.size
    }

    private class Pauses(val count: Int, val totalMs: Double, val longestMs: Double)

    /**
     * Pauses inside the active period: angular velocity below a threshold relative to the person's
     * own peak speed, for at least max(minPauseMs, fraction of a typical cycle). Dropouts break a pause.
     */
    private fun pauses(s: Signal, from: Int, to: Int, medianPeak: Double?, medianCycleMs: Double?, moving: Boolean): Pauses {
        if (!moving || medianPeak == null || to <= from) return Pauses(0, 0.0, 0.0)
        val threshold = max(config.pauseVelocityFloorDegS, config.pauseVelocityFraction * medianPeak)
        val minMs = max(config.minPauseMs, config.pauseCycleFraction * (medianCycleMs ?: 0.0))
        var count = 0
        var total = 0.0
        var longest = 0.0
        var runStart = -1
        fun close(end: Int) {
            if (runStart < 0) return
            val ms = (s.t[end] - s.t[runStart]) * 1000.0
            if (ms >= minMs) {
                count++
                total += ms
                longest = max(longest, ms)
            }
            runStart = -1
        }
        for (i in from..to) {
            val still = abs(s.omega[i]) < threshold && s.gapMs[i] == 0.0
            if (still && runStart < 0) runStart = i
            if (!still) close(i - 1)
        }
        close(to)
        return Pauses(count, total, longest)
    }

    private fun dominantFrequency(s: Signal, from: Int, to: Int): Double? {
        val n = to - from + 1
        if (n < s.fs * MIN_SECONDS_FOR_FREQUENCY) return null
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

    // --- Segments and trend --------------------------------------------------------------------

    private fun segmentOf(m: RotationMovement, durationS: Double): TrendSegment {
        val third = durationS / 3.0
        return when {
            m.midS < third -> TrendSegment.EARLY
            m.midS < 2 * third -> TrendSegment.MIDDLE
            else -> TrendSegment.LATE
        }
    }

    private fun segments(valid: List<RotationMovement>, durationS: Double): List<SegmentMetrics> =
        TrendSegment.entries.map { seg ->
            val m = if (durationS > 0) valid.filter { segmentOf(it, durationS) == seg } else emptyList()
            val enough = m.size >= config.minMovementsPerSegment
            SegmentMetrics(
                segment = seg,
                movementCount = m.size,
                cycleRateHz = if (enough) m.size / 2.0 / (durationS / 3.0) else null,
                medianAmplitudeDeg = if (enough) median(m.map { it.amplitudeDeg }) else null,
                amplitudeCvPercent = if (enough) cv(m.map { it.amplitudeDeg }) else null,
                medianPeakVelocityDegS = if (enough) median(m.map { it.peakAngularVelocityDegS }) else null,
                velocityCvPercent = if (enough) cv(m.map { it.peakAngularVelocityDegS }) else null,
                medianCycleDurationMs = if (enough) median(m.map { it.durationS })?.let { it * 2000.0 } else null,
                durationCvPercent = if (enough) cv(m.map { it.durationS }) else null,
                withinTolerancePercent = if (enough) withinTolerance(m) else null
            )
        }

    private fun measureTrends(valid: List<RotationMovement>, durationS: Double): MeasureTrends {
        val segs = segments(valid, durationS)
        fun trend(f: (SegmentMetrics) -> Double?): MetricTrend {
            val (early, middle, late) = segs.map(f)
            val change = if (early != null && late != null && early > 0) (late - early) / early * 100.0 else null
            val direction = change?.let {
                when {
                    abs(it) < config.trendStableBandPercent -> TrendDirection.STABLE
                    it > 0 -> TrendDirection.INCREASED
                    else -> TrendDirection.DECREASED
                }
            }
            return MetricTrend(early, middle, late, change, direction)
        }
        return MeasureTrends(
            amplitudeDeg = trend { it.medianAmplitudeDeg },
            peakVelocityDegS = trend { it.medianPeakVelocityDegS },
            cycleDurationMs = trend { it.medianCycleDurationMs }
        )
    }

    /** Filtered angular velocity resampled to the chart rate (block means). */
    private fun trace(s: Signal): List<Float> {
        val step = max(1, (s.fs / config.traceHz).roundToInt())
        return (0 until s.n step step).map { start ->
            val end = min(s.n, start + step)
            // One decimal: enough for the chart, and stored values round-trip exactly.
            (((start until end).sumOf { s.omega[it] } / (end - start)) * 10.0).roundToInt().div(10.0).toFloat()
        }
    }

    private fun index(s: Signal, timeS: Double): Int = (timeS * s.fs).roundToInt().coerceIn(0, s.n - 1)

    companion object {
        const val MIN_FOR_PROCESSING = 3
        const val MIN_WINDOW_S = 1.0
        const val MIN_VALUES_FOR_VARIABILITY = 3
        const val MIN_CYCLES_FOR_FREQUENCY = 3
        const val MIN_SECONDS_FOR_FREQUENCY = 4
        const val POWER_ITERATIONS = 100
        const val STANDARD_GRAVITY = 9.80665
        const val MIN_GRAVITY_NORM = 0.1
        private val BUTTERWORTH_Q = sqrt(0.5)

        fun median(values: List<Double>): Double? =
            if (values.isEmpty()) null else MotionStreams.percentile(values.sorted(), 0.5)

        /** Coefficient of variation (sample SD / mean) in percent; null with fewer than 3 values. */
        fun cv(values: List<Double>): Double? {
            if (values.size < MIN_VALUES_FOR_VARIABILITY) return null
            val mean = values.average()
            if (mean <= 0.0) return null
            val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / (values.size - 1))
            return sd / mean * 100.0
        }
    }
}
