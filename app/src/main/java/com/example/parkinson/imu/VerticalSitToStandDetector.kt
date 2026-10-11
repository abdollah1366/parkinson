package com.example.parkinson.imu

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * ENGINEERING thresholds of the vertical-movement sit-to-stand detector. Not clinically validated; documented in
 * docs/imu-assessments-algorithm.md.
 */
data class VerticalSitToStandThresholds(
    /** Vertical travel (m) that counts as standing up or sitting down. The trunk rises about 0.3-0.5 m. */
    val minTravelM: Double = 0.15,
    /** Travel above which a lobe is not a chair transfer (the phone was moved by the arm). */
    val maxTravelM: Double = 1.0,
    /** Peak vertical speed (m/s) a transfer must reach. */
    val minPeakSpeedMs: Double = 0.1,
    /** A transfer lasts at least this long (ms). */
    val minLobeMs: Double = 300.0,
    /** Speed (m/s) under which the phone counts as not moving up or down: the edges of a transfer. */
    val edgeSpeedMs: Double = 0.04,
    /** Low-pass cutoff (Hz) of the vertical acceleration: removes hand tremor and footsteps. */
    val lowPassHz: Double = 3.0,
    /** Window (s) of the moving means that remove the acceleration bias and the speed drift (a few transfers). */
    val detrendWindowS: Double = 10.0,
    /** Time constant (s) of the correction of the gyroscope-tracked gravity direction toward the accelerometer. */
    val gravityTauS: Double = 2.0,
) {
    fun summary(): String =
        "vtravel=$minTravelM-$maxTravelM;vpeak=$minPeakSpeedMs;vlobe=$minLobeMs;" +
            "edge=$edgeSpeedMs;lp=$lowPassHz;detrend=$detrendWindowS;gtau=$gravityTauS"
}

/**
 * Sit-to-stand detector on the vertical movement of the phone, for any placement that moves up and down with the body:
 * held in the hand against the chest, in a shirt pocket, or in a trouser pocket. The posture detector
 * ([SitToStandImuDetector]) needs the tilt of the thigh; a phone held at the chest is upright both when seated and when
 * standing, so its tilt does not show the transfer. This one does not use the tilt.
 *
 * 1. The gravity direction is tracked with the gyroscope and slowly corrected toward the accelerometer, so it follows
 *    the phone's rotation during the transfer.
 * 2. The vertical acceleration (acceleration along gravity, minus g), low-passed, is integrated to a vertical speed.
 *    The phone is at rest before and after each transfer, so the acceleration of a whole transfer sums to zero: a
 *    moving mean over a few transfers is the sensor bias and is removed before the integration, and a moving mean
 *    of the speed (the remaining drift) is removed after it. A still phone cannot be told from a phone moving at a
 *    steady speed, so the speed is not reset at "still" moments.
 * 3. A stretch of upward speed that travels [VerticalSitToStandThresholds.minTravelM] or more is standing up; a stretch
 *    of downward speed that travels as far is sitting down. Standing up followed by sitting down is one repetition.
 *
 * Arm movements that lift the phone by a similar distance can be counted too; the instructions ask to keep the phone
 * still against the chest.
 */
object VerticalSitToStandDetector {

    /** One stretch of vertical speed with a single sign. */
    private class Lobe(val start: Int, val end: Int, val travelM: Double, val peakSpeed: Double)

    fun analyze(
        grid: ImuGrid,
        reference: Vec3,
        thresholds: VerticalSitToStandThresholds = VerticalSitToStandThresholds(),
        maxGapMs: Double = SitToStandImuThresholds().maxGapMs,
    ): SitToStandImuAnalysis {
        val n = grid.size
        val maxPosture = (0 until n).map { grid.gravityAngleDeg(it, reference) }.filter { it.isFinite() }.maxOrNull()
        val empty = SitToStandImuAnalysis(emptyList(), emptyList(), emptyList(), maxPosture, grid.coveragePercent)
        if (n < 3) return empty
        val dtS = (grid.timeMs[1] - grid.timeMs[0]) / 1000.0
        val gNorm = medianAccelNorm(grid) ?: return empty

        val vertical = verticalAcceleration(grid, gNorm, dtS, thresholds.gravityTauS)
        val speed = verticalSpeed(vertical, dtS, thresholds)
        val lobes = lobes(speed, dtS, thresholds.edgeSpeedMs)

        val repetitions = ArrayList<ImuRepetition>()
        val rejections = ArrayList<Rejection>()
        var rise: Lobe? = null
        for (lobe in lobes) {
            val travel = abs(lobe.travelM)
            val durationMs = (lobe.end - lobe.start + 1) * dtS * 1000.0
            if (travel < thresholds.minTravelM || travel > thresholds.maxTravelM ||
                abs(lobe.peakSpeed) < thresholds.minPeakSpeedMs || durationMs < thresholds.minLobeMs
            ) continue
            val current = rise
            if (lobe.travelM > 0) {
                // A second rise without sitting down in between: the first one was not followed by a sit (lost data or
                // the phone was moved); the newer rise is kept.
                if (current != null) rejections += Rejection(RejectionReason.PARTIAL_RISE, grid.timeMs[current.start], grid.timeMs[lobe.start])
                rise = lobe
            } else if (current != null) {
                if (hasGap(grid, current.start, lobe.end)) {
                    rejections += Rejection(RejectionReason.SENSOR_GAP, grid.timeMs[current.start], grid.timeMs[lobe.end])
                } else {
                    repetitions += ImuRepetition(
                        index = repetitions.size + 1,
                        standOnsetMs = grid.timeMs[current.start],
                        standReachedMs = grid.timeMs[current.end],
                        sitOnsetMs = grid.timeMs[lobe.start],
                        seatedReachedMs = grid.timeMs[lobe.end],
                        peakPostureDeg = peakOf(current.start, lobe.end) { grid.gravityAngleDeg(it, reference) },
                        peakRotationDegPerSec = peakOf(current.start, lobe.end) { grid.gyroDegPerSec(it) },
                    )
                }
                rise = null
            }
        }
        return empty.copy(
            repetitions = repetitions,
            rejections = rejections,
            invalidIntervals = SitToStandImuDetector.invalidIntervals(grid, maxGapMs),
            byVerticalMovement = true,
        )
    }

    private fun medianAccelNorm(grid: ImuGrid): Double? {
        val norms = (0 until grid.size).filter { grid.valid[it] }
            .map { sqrt(grid.ax[it] * grid.ax[it] + grid.ay[it] * grid.ay[it] + grid.az[it] * grid.az[it]) }
            .sorted()
        return if (norms.isEmpty()) null else norms[norms.size / 2]
    }

    /**
     * Vertical acceleration per grid point (m/s^2), NaN where invalid. The gravity direction is propagated with the
     * gyroscope (a fixed world vector seen from the rotating phone changes as g x w) and pulled toward the measured
     * acceleration with time constant [tauS]; it restarts from the accelerometer after a gap.
     */
    private fun verticalAcceleration(grid: ImuGrid, gNorm: Double, dtS: Double, tauS: Double): DoubleArray {
        val out = DoubleArray(grid.size) { Double.NaN }
        val k = (dtS / tauS).coerceAtMost(1.0)
        var gx = Double.NaN
        var gy = 0.0
        var gz = 0.0
        for (i in 0 until grid.size) {
            if (!grid.valid[i]) {
                gx = Double.NaN
                continue
            }
            val ax = grid.ax[i]
            val ay = grid.ay[i]
            val az = grid.az[i]
            if (gx.isNaN()) {
                gx = ax; gy = ay; gz = az
            } else {
                val wx = grid.wx[i]
                val wy = grid.wy[i]
                val wz = grid.wz[i]
                val rx = gx + (gy * wz - gz * wy) * dtS
                val ry = gy + (gz * wx - gx * wz) * dtS
                val rz = gz + (gx * wy - gy * wx) * dtS
                gx = rx + k * (ax - rx)
                gy = ry + k * (ay - ry)
                gz = rz + k * (az - rz)
            }
            val norm = sqrt(gx * gx + gy * gy + gz * gz)
            if (norm <= 0.0) continue
            out[i] = (ax * gx + ay * gy + az * gz) / norm - gNorm
        }
        return out
    }

    /**
     * Vertical speed (m/s) per grid point, zero at gaps. Each stretch of valid data is processed on its own: low-pass,
     * remove the moving-mean bias, integrate, remove the moving-mean drift.
     */
    private fun verticalSpeed(vertical: DoubleArray, dtS: Double, t: VerticalSitToStandThresholds): DoubleArray {
        val n = vertical.size
        val speed = DoubleArray(n)
        val half = (t.detrendWindowS / 2 / dtS).toInt().coerceAtLeast(1)
        val alpha = 1.0 - kotlin.math.exp(-2 * Math.PI * t.lowPassHz * dtS)
        var i = 0
        while (i < n) {
            if (!vertical[i].isFinite()) { i++; continue }
            var j = i
            while (j + 1 < n && vertical[j + 1].isFinite()) j++
            val a = lowPassZeroPhase(vertical.copyOfRange(i, j + 1), alpha)
            val unbiased = minusMovingMean(a, half)
            val v = DoubleArray(unbiased.size)
            var sum = 0.0
            for (k in unbiased.indices) {
                sum += unbiased[k] * dtS
                v[k] = sum
            }
            minusMovingMean(v, half).copyInto(speed, i)
            i = j + 1
        }
        return speed
    }

    /** One-pole low-pass run forward then backward (no delay). */
    private fun lowPassZeroPhase(x: DoubleArray, alpha: Double): DoubleArray {
        val y = x.copyOf()
        for (k in 1 until y.size) y[k] = y[k - 1] + alpha * (y[k] - y[k - 1])
        for (k in y.size - 2 downTo 0) y[k] = y[k + 1] + alpha * (y[k] - y[k + 1])
        return y
    }

    /** x minus its centred moving mean over 2 * [half] + 1 points (truncated at the ends). */
    private fun minusMovingMean(x: DoubleArray, half: Int): DoubleArray {
        val prefix = DoubleArray(x.size + 1)
        for (k in x.indices) prefix[k + 1] = prefix[k] + x[k]
        return DoubleArray(x.size) { k ->
            val lo = (k - half).coerceAtLeast(0)
            val hi = (k + half).coerceAtMost(x.size - 1)
            x[k] - (prefix[hi + 1] - prefix[lo]) / (hi - lo + 1)
        }
    }

    /** Stretches where the speed stays above [edge] with one sign, with their travel (m, signed) and peak speed (signed). */
    private fun lobes(speed: DoubleArray, dtS: Double, edge: Double): List<Lobe> {
        val out = ArrayList<Lobe>()
        var i = 0
        while (i < speed.size) {
            if (abs(speed[i]) <= edge) { i++; continue }
            val positive = speed[i] > 0
            var j = i
            var travel = 0.0
            var peak = 0.0
            while (j < speed.size && abs(speed[j]) > edge && (speed[j] > 0) == positive) {
                travel += speed[j] * dtS
                if (abs(speed[j]) > abs(peak)) peak = speed[j]
                j++
            }
            out += Lobe(i, j - 1, travel, peak)
            i = j
        }
        return out
    }

    private fun hasGap(grid: ImuGrid, from: Int, to: Int): Boolean = (from..to).any { !grid.valid[it] }

    private inline fun peakOf(from: Int, to: Int, value: (Int) -> Double): Double {
        var best = Double.NaN
        for (i in from..to) {
            val v = value(i)
            if (v.isFinite() && (best.isNaN() || v > best)) best = v
        }
        return best
    }

}
