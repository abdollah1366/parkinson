package com.example.parkinson.imu

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * ENGINEERING parameters of the walking feature extraction (phone in the front trouser pocket). They are not
 * clinically validated. The turn parameters follow El-Gohary et al., Sensors 2014;14(1):356-369 (1.5 Hz low-pass of
 * the vertical rotation rate, peak >= 15 deg/s, turn bounds at 5 deg/s, 0.5-10 s, angle > 45 deg).
 */
data class GaitFeatureConfig(
    /** A grid point is "moving" when the 1 s RMS of the rotation rate is above this (deg/s). */
    val movingRmsDegPerSec: Double = 25.0,
    /** Shortest straight-walking segment analysed (ms). */
    val minSegmentMs: Double = 4_000.0,
    /** Straight walking excludes this much time around each turn (ms). */
    val turnMarginMs: Double = 500.0,
    /** Low-pass of the thigh rotation used for stride detection (Hz). */
    val strideLowPassHz: Double = 4.0,
    /** Smallest thigh swing peak accepted (deg/s), and the fraction of the 95th percentile used as threshold. */
    val minSwingDegPerSec: Double = 50.0,
    val swingPeakFraction: Double = 0.5,
    /** Plausible stride (two steps) duration (ms): about 60 to 170 steps per minute. */
    val minStrideMs: Double = 700.0,
    val maxStrideMs: Double = 2_000.0,
    /** An interval longer than this multiple of the median stride is a missed stride and is not used. */
    val missedStrideFactor: Double = 1.6,
    val minStridesForTime: Int = 3,
    val minStridesForVariability: Int = 5,
    val turnLowPassHz: Double = 1.5,
    val turnPeakDegPerSec: Double = 15.0,
    val turnBoundDegPerSec: Double = 5.0,
    val minTurnDeg: Double = 45.0,
    val minTurnMs: Double = 500.0,
    val maxTurnMs: Double = 10_000.0,
    /** Tremor band (Hz) and the bins kept away from gait harmonics (Hz). */
    val tremorLowHz: Double = 3.5,
    val tremorHighHz: Double = 7.5,
    val standingTremorHighHz: Double = 18.0,
    val harmonicGuardHz: Double = 0.3,
    val spectrumWindow: Int = 256,
    /** A tremor peak is compared with at least the power of a sinusoid of this amplitude (deg/s), the sensor noise. */
    val tremorNoiseFloorDegPerSec: Double = 1.0,
) {
    fun summary(): String =
        "moving>$movingRmsDegPerSec;seg>=$minSegmentMs;stride=$minStrideMs-$maxStrideMs;" +
            "turn=lp$turnLowPassHz,peak$turnPeakDegPerSec,>$minTurnDeg;tremor=$tremorLowHz-$tremorHighHz"
}

/** One detected turn (grid time, ms). [angleDeg] is signed (direction of rotation about gravity). */
data class GaitTurn(
    val startMs: Double,
    val endMs: Double,
    val angleDeg: Double,
    val peakDegPerSec: Double,
) {
    val durationMs: Double get() = endMs - startMs
    val meanDegPerSec: Double get() = if (durationMs > 0) abs(angleDeg) / (durationMs / 1000.0) else 0.0
}

/**
 * Walking features from the phone in the trouser pocket. Every value is null when it could not be measured; a null is
 * never replaced by zero.
 * - Strides: one thigh swing per stride (two steps), from the dominant rotation axis of the pocket thigh.
 * - Regularity and step asymmetry: autocorrelation of the vertical acceleration (Moe-Nilssen and Helbostad 2004).
 *   The asymmetry is the timing difference between the two steps of a stride, so the louder heel strike of the pocket
 *   leg does not bias it.
 * - Tremor indices: spectral peak prominence in the tremor band, away from gait harmonics. EXPERIMENTAL.
 */
data class GaitFeatures(
    val strides: Int,
    val strideTimeMs: Double?,
    val strideCvPercent: Double?,
    val stepRegularity: Double?,
    val strideRegularity: Double?,
    val stepAsymmetryPercent: Double?,
    val turns: List<GaitTurn>,
    val walkingTremorIndex: Double?,
    val walkingTremorHz: Double?,
    val standingTremorIndex: Double?,
    val standingTremorHz: Double?,
) {
    /** Two steps per stride. */
    val strideCadenceStepsPerMinute: Double? get() = strideTimeMs?.let { 120_000.0 / it }

    /** Turns of at least 90 degrees, the ones compared with the reference turning speed. */
    val largeTurns: List<GaitTurn> get() = turns.filter { abs(it.angleDeg) >= 90.0 }

    val meanTurnVelocityDegPerSec: Double? get() = largeTurns.takeIf { it.isNotEmpty() }?.map { it.meanDegPerSec }?.average()
    val peakTurnVelocityDegPerSec: Double? get() = largeTurns.takeIf { it.isNotEmpty() }?.maxOf { it.peakDegPerSec }
    val meanTurnDurationMs: Double? get() = largeTurns.takeIf { it.isNotEmpty() }?.map { it.durationMs }?.average()
}

/** Pure functions; no Android calls. */
object GaitFeatureExtractor {

    fun extract(walk: ImuGrid, standing: ImuGrid? = null, config: GaitFeatureConfig = GaitFeatureConfig()): GaitFeatures {
        val n = walk.size
        val dt = 1000.0 / walk.gridHz
        val turns = detectTurns(walk, config)

        // Straight walking: valid, moving, and away from every turn.
        val gyroMag = DoubleArray(n) { walk.gyroDegPerSec(it).let { v -> if (v.isFinite()) v else 0.0 } }
        val rms = movingRms(gyroMag, walk.valid, (1000.0 / dt).toInt().coerceAtLeast(1))
        val straight = BooleanArray(n) { i ->
            walk.valid[i] && rms[i] >= config.movingRmsDegPerSec && turns.none { t ->
                walk.timeMs[i] >= t.startMs - config.turnMarginMs && walk.timeMs[i] <= t.endMs + config.turnMarginMs
            }
        }
        val segments = runs(straight).filter { (a, b) -> walk.timeMs[b] - walk.timeMs[a] >= config.minSegmentMs }

        val strideIntervals = strideIntervals(walk, segments, config)
        val strideMedian = median(strideIntervals)
        val usable = strideMedian?.let { m -> strideIntervals.filter { it <= config.missedStrideFactor * m } } ?: emptyList()
        val strideTime = usable.takeIf { it.size >= config.minStridesForTime }?.average()
        val strideCv = usable.takeIf { it.size >= config.minStridesForVariability }?.let { ImuStats.cv(it) }

        val regularity = regularity(walk, segments, strideTime, config)
        val strideHz = strideTime?.let { 1000.0 / it }
        val walkingTremor = if (strideHz == null) null else tremorPeak(walk, segments, config.tremorLowHz, config.tremorHighHz, strideHz, config)
        val standingTremor = standing?.let { g ->
            val all = runs(BooleanArray(g.size) { g.valid[it] }).filter { (a, b) -> b - a + 1 >= 32 }
            tremorPeak(g, all, config.tremorLowHz, config.standingTremorHighHz, null, config)
        }

        return GaitFeatures(
            strides = usable.size,
            strideTimeMs = strideTime,
            strideCvPercent = strideCv,
            stepRegularity = regularity?.stepRegularity,
            strideRegularity = regularity?.strideRegularity,
            stepAsymmetryPercent = regularity?.asymmetryPercent,
            turns = turns,
            walkingTremorIndex = walkingTremor?.first,
            walkingTremorHz = walkingTremor?.second,
            standingTremorIndex = standingTremor?.first,
            standingTremorHz = standingTremor?.second,
        )
    }

    // ---------------------------------------------------------------- turns

    fun detectTurns(grid: ImuGrid, config: GaitFeatureConfig = GaitFeatureConfig()): List<GaitTurn> {
        val n = grid.size
        if (n < 3) return emptyList()
        val raw = DoubleArray(n) { grid.yawDegPerSec(it).let { v -> if (v.isFinite()) v else 0.0 } }
        val yaw = zeroPhaseLowPass(raw, grid.gridHz, config.turnLowPassHz)
        val dtS = 1.0 / grid.gridHz
        val out = ArrayList<GaitTurn>()
        var i = 0
        while (i < n) {
            if (!grid.valid[i] || abs(yaw[i]) < config.turnPeakDegPerSec) {
                i++
                continue
            }
            val sign = if (yaw[i] > 0) 1.0 else -1.0
            var a = i
            while (a > 0 && grid.valid[a - 1] && yaw[a - 1] * sign > config.turnBoundDegPerSec) a--
            var b = i
            while (b < n - 1 && grid.valid[b + 1] && yaw[b + 1] * sign > config.turnBoundDegPerSec) b++
            var angle = 0.0
            var peak = 0.0
            for (k in a..b) {
                angle += yaw[k] * dtS
                peak = maxOf(peak, abs(yaw[k]))
            }
            val turn = GaitTurn(grid.timeMs[a], grid.timeMs[b], angle, peak)
            val previous = out.lastOrNull()
            if (previous != null && previous.angleDeg * angle > 0 && turn.startMs - previous.endMs < 50.0) {
                out[out.size - 1] = GaitTurn(previous.startMs, turn.endMs, previous.angleDeg + angle, maxOf(previous.peakDegPerSec, peak))
            } else {
                out += turn
            }
            i = b + 1
        }
        return out.filter {
            abs(it.angleDeg) > config.minTurnDeg && it.durationMs >= config.minTurnMs && it.durationMs <= config.maxTurnMs
        }
    }

    // ---------------------------------------------------------------- strides

    /** Intervals (ms) between thigh swing peaks inside each straight segment; implausible intervals are dropped. */
    private fun strideIntervals(grid: ImuGrid, segments: List<Pair<Int, Int>>, config: GaitFeatureConfig): List<Double> {
        if (segments.isEmpty()) return emptyList()
        val axis = principalGyroAxis(grid, segments) ?: return emptyList()
        val n = grid.size
        val raw = DoubleArray(n) {
            if (!grid.valid[it]) 0.0 else (grid.wx[it] * axis.x + grid.wy[it] * axis.y + grid.wz[it] * axis.z) * RAD_TO_DEG
        }
        var s = zeroPhaseLowPass(raw, grid.gridHz, config.strideLowPassHz)
        // The swing peak is the larger of the two signs; orient the signal so it is positive.
        val inside = segments.flatMap { (a, b) -> (a..b).toList() }
        val pos = percentile(inside.map { s[it] }.sorted(), 0.95)
        val neg = percentile(inside.map { -s[it] }.sorted(), 0.95)
        if (neg > pos) s = DoubleArray(n) { -s[it] }
        val p95 = maxOf(pos, neg)
        val threshold = maxOf(config.minSwingDegPerSec, config.swingPeakFraction * p95)
        val refractoryMs = config.minStrideMs * 0.8

        val out = ArrayList<Double>()
        for ((a, b) in segments) {
            val peaks = ArrayList<Pair<Int, Double>>()
            for (i in maxOf(a, 1)..minOf(b, n - 2)) {
                if (s[i] >= threshold && s[i] >= s[i - 1] && s[i] > s[i + 1]) peaks += i to refine(s, i, grid)
            }
            val kept = ArrayList<Pair<Int, Double>>()
            for (p in peaks.sortedByDescending { s[it.first] }) {
                if (kept.none { abs(it.second - p.second) < refractoryMs }) kept += p
            }
            kept.sortBy { it.second }
            kept.zipWithNext { p, q -> q.second - p.second }
                .filter { it in config.minStrideMs..config.maxStrideMs }
                .forEach { out += it }
        }
        return out
    }

    /** Dominant rotation axis of the gyroscope during straight walking (the pocket thigh's swing axis). */
    private fun principalGyroAxis(grid: ImuGrid, segments: List<Pair<Int, Int>>): Vec3? {
        val c = Array(3) { DoubleArray(3) }
        var count = 0
        for ((a, b) in segments) for (i in a..b) {
            if (!grid.valid[i]) continue
            val v = doubleArrayOf(grid.wx[i], grid.wy[i], grid.wz[i])
            for (p in 0..2) for (q in 0..2) c[p][q] += v[p] * v[q]
            count++
        }
        if (count < 10) return null
        var x = doubleArrayOf(1.0, 0.7, 0.3)
        repeat(50) {
            val y = DoubleArray(3) { p -> (0..2).sumOf { q -> c[p][q] * x[q] } }
            val norm = sqrt(y.sumOf { it * it })
            if (norm <= 0.0) return null
            x = DoubleArray(3) { y[it] / norm }
        }
        return Vec3(x[0], x[1], x[2])
    }

    // ---------------------------------------------------------------- regularity and asymmetry

    private class Regularity(val stepRegularity: Double, val strideRegularity: Double, val asymmetryPercent: Double)

    private fun regularity(grid: ImuGrid, segments: List<Pair<Int, Int>>, strideTimeMs: Double?, config: GaitFeatureConfig): Regularity? {
        if (segments.isEmpty()) return null
        val dt = 1000.0 / grid.gridHz
        var weight = 0.0
        var ad1 = 0.0
        var ad2 = 0.0
        var asym = 0.0
        for ((a, b) in segments) {
            // First difference of the vertical acceleration: it keeps the sharp heel strikes and removes the slow
            // stride-rate component that the thigh swing leaves in the gravity estimate, so the step timing is not biased.
            val v = DoubleArray(b - a + 1) { grid.verticalLinearAcceleration(a + it).let { x -> if (x.isFinite()) x else 0.0 } }
            if (v.size < 3) continue
            val x = DoubleArray(v.size - 1) { v[it + 1] - v[it] }
            val mean = x.average()
            for (k in x.indices) x[k] -= mean
            val variance = x.sumOf { it * it } / x.size
            if (variance <= 1e-6) continue
            val maxLag = minOf(x.size / 2, (config.maxStrideMs * 1.2 / dt).toInt())
            if (maxLag < 4) continue
            val r = DoubleArray(maxLag + 1) { k -> (0 until x.size - k).sumOf { x[it] * x[it + k] } / ((x.size - k) * variance) }
            // Stride lag: around the measured stride time, or the strongest peak in the plausible stride band.
            val (lo2, hi2) = if (strideTimeMs != null) (0.8 * strideTimeMs / dt) to (1.2 * strideTimeMs / dt)
            else (config.minStrideMs / dt) to (config.maxStrideMs / dt)
            val lag2 = peakLag(r, lo2.toInt(), minOf(hi2.toInt(), maxLag)) ?: continue
            val lag1 = peakLag(r, (0.3 * lag2.first).toInt(), (0.7 * lag2.first).toInt()) ?: continue
            val w = x.size.toDouble()
            weight += w
            ad1 += w * lag1.second
            ad2 += w * lag2.second
            asym += w * 100.0 * abs(2.0 * lag1.first / lag2.first - 1.0)
        }
        if (weight <= 0.0) return null
        return Regularity(ad1 / weight, ad2 / weight, asym / weight)
    }

    /** Lag (fractional, by parabolic interpolation) and value of the highest local maximum of [r] in [lo, hi]. */
    private fun peakLag(r: DoubleArray, lo: Int, hi: Int): Pair<Double, Double>? {
        var best = -1
        for (k in maxOf(lo, 1)..minOf(hi, r.size - 2)) {
            if (r[k] >= r[k - 1] && r[k] >= r[k + 1] && (best < 0 || r[k] > r[best])) best = k
        }
        if (best < 0) return null
        val y0 = r[best - 1]
        val y1 = r[best]
        val y2 = r[best + 1]
        val den = y0 - 2 * y1 + y2
        val shift = if (den != 0.0) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
        return (best + shift) to y1
    }

    // ---------------------------------------------------------------- tremor (experimental)

    /**
     * Prominence of the largest spectral peak of the rotation rate in [lowHz, highHz]: its power divided by the median
     * power of the 1-20 Hz bins. Bins within the guard of a gait harmonic (multiples of [strideHz]) are skipped.
     * Returns (index, frequency) or null when there is too little data.
     */
    private fun tremorPeak(
        grid: ImuGrid,
        segments: List<Pair<Int, Int>>,
        lowHz: Double,
        highHz: Double,
        strideHz: Double?,
        config: GaitFeatureConfig,
    ): Pair<Double, Double>? {
        val nyquist = grid.gridHz / 2
        val windows = ArrayList<IntRange>()
        for ((a, b) in segments) {
            val len = b - a + 1
            val size = minOf(config.spectrumWindow, len)
            if (size < 32) continue
            var start = a
            while (start + size - 1 <= b) {
                windows += start until start + size
                start += size / 2
            }
        }
        if (windows.isEmpty()) return null
        val size = windows.minOf { it.count() }
        val bins = size / 2
        val psd = DoubleArray(bins + 1)
        for (w in windows) {
            val first = w.first
            for (axis in 0..2) {
                val v = DoubleArray(size) {
                    val i = first + it
                    when (axis) { 0 -> grid.wx[i]; 1 -> grid.wy[i]; else -> grid.wz[i] } * RAD_TO_DEG
                }
                val mean = v.average()
                for (k in 0..bins) {
                    var re = 0.0
                    var im = 0.0
                    for (t in 0 until size) {
                        val hann = 0.5 - 0.5 * cos(2 * PI * t / (size - 1))
                        val x = (v[t] - mean) * hann
                        val ang = 2 * PI * k * t / size
                        re += x * cos(ang)
                        im -= x * sin(ang)
                    }
                    psd[k] += re * re + im * im
                }
            }
        }
        val df = grid.gridHz / size
        fun freq(k: Int) = k * df
        fun harmonic(f: Double): Boolean {
            if (strideHz == null) return false
            val nearest = Math.round(f / strideHz) * strideHz
            return nearest > 0 && abs(f - nearest) <= config.harmonicGuardHz
        }
        val reference = (0..bins).filter { freq(it) in 1.0..minOf(20.0, nyquist) && !harmonic(freq(it)) }.map { psd[it] }.sorted()
        if (reference.size < 5) return null
        // Power of a sinusoid of the noise-floor amplitude in one bin: |X| = A N / 4 with a Hann window, per window.
        val noise = config.tremorNoiseFloorDegPerSec * size / 4.0
        val floor = maxOf(reference[reference.size / 2], noise * noise * windows.size)
        val band = (0..bins).filter { freq(it) in lowHz..minOf(highHz, nyquist) && !harmonic(freq(it)) }
        val peak = band.maxByOrNull { psd[it] } ?: return null
        return (psd[peak] / floor) to freq(peak)
    }

    // ---------------------------------------------------------------- helpers

    private fun refine(s: DoubleArray, i: Int, grid: ImuGrid): Double {
        val y0 = s[i - 1]
        val y1 = s[i]
        val y2 = s[i + 1]
        val den = y0 - 2 * y1 + y2
        val shift = if (den != 0.0) (0.5 * (y0 - y2) / den).coerceIn(-0.5, 0.5) else 0.0
        return grid.timeMs[i] + shift * (1000.0 / grid.gridHz)
    }

    /** Forward-backward one-pole low-pass (no phase lag). */
    private fun zeroPhaseLowPass(x: DoubleArray, fs: Double, cutoffHz: Double): DoubleArray {
        if (x.isEmpty()) return x
        val alpha = 1.0 - exp(-2 * PI * cutoffHz / fs)
        val f = DoubleArray(x.size)
        var y = x[0]
        for (i in x.indices) {
            y += alpha * (x[i] - y)
            f[i] = y
        }
        y = f[f.size - 1]
        for (i in f.indices.reversed()) {
            y += alpha * (f[i] - y)
            f[i] = y
        }
        return f
    }

    private fun movingRms(x: DoubleArray, valid: BooleanArray, window: Int): DoubleArray {
        val n = x.size
        val prefix = DoubleArray(n + 1)
        val count = IntArray(n + 1)
        for (i in 0 until n) {
            prefix[i + 1] = prefix[i] + if (valid[i]) x[i] * x[i] else 0.0
            count[i + 1] = count[i] + if (valid[i]) 1 else 0
        }
        val half = window / 2
        return DoubleArray(n) {
            val a = maxOf(0, it - half)
            val b = minOf(n, it + half + 1)
            val c = count[b] - count[a]
            if (c == 0) 0.0 else sqrt((prefix[b] - prefix[a]) / c)
        }
    }

    private fun runs(flags: BooleanArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (i in 0..flags.size) {
            val on = i < flags.size && flags[i]
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                out += start to i - 1
                start = -1
            }
        }
        return out
    }

    private fun median(values: List<Double>): Double? = values.takeIf { it.isNotEmpty() }?.sorted()?.let { it[it.size / 2] }

    private fun percentile(sorted: List<Double>, p: Double): Double =
        if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()]
}
