package com.example.parkinson.imu

import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import com.example.parkinson.sensors.MotionStreams
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * ENGINEERING parameters of the inertial signal. They are not clinical values.
 * - [gridHz]: the uniform analysis grid. Sensor timestamps are irregular; the grid makes the filters independent of
 *   the measured rate.
 * - [maxGapMs]: a gap between two raw events longer than this is not bridged; the grid points inside it are invalid.
 * - [gravityTauS]: time constant of the low-pass gravity estimate (seconds). Short enough to follow a 1 s chair transfer
 *   (the posture angle lags the true angle by about this much), long enough to average the walking acceleration. It follows the slow change of the
 *   phone's orientation and leaves the fast movement in the linear part of the acceleration.
 */
data class ImuSignalConfig(
    val gridHz: Double = 50.0,
    val maxGapMs: Double = 200.0,
    val gravityTauS: Double = 0.3,
    val samplingPeriodUs: Int = 10_000,
) {
    fun summary(): String = "grid=${gridHz}Hz;gap=${maxGapMs}ms;gravityTau=${gravityTauS}s;period=${samplingPeriodUs}us"
}

const val RAD_TO_DEG: Double = 180.0 / PI

/**
 * Uniform grid of the phone's motion, all in the DEVICE frame (the phone's own axes; the screen orientation and the
 * pocket orientation are not assumed). Acceleration in m/s^2 including gravity, gyroscope in rad/s, gravity estimate
 * in m/s^2. Invalid points (inside a gap longer than the limit, or outside the data) are NaN and marked in [valid].
 */
class ImuGrid(
    /** Grid times in ms from the first sample of the recording. */
    val timeMs: DoubleArray,
    val valid: BooleanArray,
    val ax: DoubleArray,
    val ay: DoubleArray,
    val az: DoubleArray,
    val wx: DoubleArray,
    val wy: DoubleArray,
    val wz: DoubleArray,
    val gx: DoubleArray,
    val gy: DoubleArray,
    val gz: DoubleArray,
    val gridHz: Double,
) {
    val size: Int get() = timeMs.size

    val validCount: Int get() = valid.count { it }

    /** Share of grid points with data (percent). */
    val coveragePercent: Double get() = if (size == 0) 0.0 else 100.0 * validCount / size

    /** Gyroscope magnitude at grid point [i] (deg/s). NaN when invalid. */
    fun gyroDegPerSec(i: Int): Double = if (!valid[i]) Double.NaN else sqrt(wx[i] * wx[i] + wy[i] * wy[i] + wz[i] * wz[i]) * RAD_TO_DEG

    /** Gravity magnitude at grid point [i] (m/s^2). NaN when invalid. */
    fun gravityMagnitude(i: Int): Double = if (!valid[i]) Double.NaN else sqrt(gx[i] * gx[i] + gy[i] * gy[i] + gz[i] * gz[i])

    /**
     * Rotation rate about the gravity direction at [i] (deg/s): the yaw of the phone, which is the turning rate of the
     * person when the phone is worn at a fixed position. Independent of how the phone is oriented.
     */
    fun yawDegPerSec(i: Int): Double {
        if (!valid[i]) return Double.NaN
        val g = gravityMagnitude(i)
        if (g <= 0.0) return Double.NaN
        return (wx[i] * gx[i] + wy[i] * gy[i] + wz[i] * gz[i]) / g * RAD_TO_DEG
    }

    /** Angle (deg) between the gravity direction at [i] and the unit direction [reference]. NaN when invalid. */
    fun gravityAngleDeg(i: Int, reference: Vec3): Double {
        if (!valid[i]) return Double.NaN
        val g = gravityMagnitude(i)
        if (g <= 0.0) return Double.NaN
        val dot = (gx[i] * reference.x + gy[i] * reference.y + gz[i] * reference.z) / g
        return acos(dot.coerceIn(-1.0, 1.0)) * RAD_TO_DEG
    }

    /** Vertical component of the linear acceleration at [i] (m/s^2): acceleration projected on gravity, minus g. */
    fun verticalLinearAcceleration(i: Int): Double {
        if (!valid[i]) return Double.NaN
        val g = gravityMagnitude(i)
        if (g <= 0.0) return Double.NaN
        return (ax[i] * gx[i] + ay[i] * gy[i] + az[i] * gz[i]) / g - g
    }
}

/** Unit vector or any 3D vector in the device frame. */
data class Vec3(val x: Double, val y: Double, val z: Double) {
    val norm: Double get() = sqrt(x * x + y * y + z * z)

    fun normalized(): Vec3? = norm.takeIf { it > 0.0 }?.let { Vec3(x / it, y / it, z / it) }

    companion object {
        fun mean(values: List<Vec3>): Vec3? {
            if (values.isEmpty()) return null
            val n = values.size.toDouble()
            return Vec3(values.sumOf { it.x } / n, values.sumOf { it.y } / n, values.sumOf { it.z } / n)
        }
    }
}

/** Builds [ImuGrid] from cleaned accelerometer and gyroscope events. Pure functions; no Android calls. */
object ImuGridBuilder {

    fun build(samples: List<MotionSample>, config: ImuSignalConfig = ImuSignalConfig()): ImuGrid? {
        val acc = samples.filter { it.type == MotionSensorType.ACCELEROMETER }.sortedBy { it.timestampNs }
        val gyro = samples.filter { it.type == MotionSensorType.GYROSCOPE }.sortedBy { it.timestampNs }
        if (acc.size < 2 || gyro.size < 2) return null

        val origin = maxOf(acc.first().timestampNs, gyro.first().timestampNs)
        val end = minOf(acc.last().timestampNs, gyro.last().timestampNs)
        if (end <= origin) return null

        val accTrack = Track(
            t = DoubleArray(acc.size) { (acc[it].timestampNs - origin) / 1e6 },
            a = DoubleArray(acc.size) { acc[it].x.toDouble() },
            b = DoubleArray(acc.size) { acc[it].y.toDouble() },
            c = DoubleArray(acc.size) { acc[it].z.toDouble() },
        )
        val gyroTrack = Track(
            t = DoubleArray(gyro.size) { (gyro[it].timestampNs - origin) / 1e6 },
            a = DoubleArray(gyro.size) { gyro[it].x.toDouble() },
            b = DoubleArray(gyro.size) { gyro[it].y.toDouble() },
            c = DoubleArray(gyro.size) { gyro[it].z.toDouble() },
        )

        val durationMs = (end - origin) / 1e6
        val n = (durationMs * config.gridHz / 1000.0).toInt() + 1
        val dtMs = 1000.0 / config.gridHz
        val time = DoubleArray(n) { it * dtMs }
        val valid = BooleanArray(n)
        val ax = DoubleArray(n) { Double.NaN }
        val ay = DoubleArray(n) { Double.NaN }
        val az = DoubleArray(n) { Double.NaN }
        val wx = DoubleArray(n) { Double.NaN }
        val wy = DoubleArray(n) { Double.NaN }
        val wz = DoubleArray(n) { Double.NaN }
        val gx = DoubleArray(n) { Double.NaN }
        val gy = DoubleArray(n) { Double.NaN }
        val gz = DoubleArray(n) { Double.NaN }

        val out = DoubleArray(3)
        var lastValidTime = Double.NaN
        var gravity: DoubleArray? = null
        for (i in 0 until n) {
            val t = time[i]
            if (!accTrack.at(t, config.maxGapMs, out)) continue
            val a0 = out[0]; val a1 = out[1]; val a2 = out[2]
            if (!gyroTrack.at(t, config.maxGapMs, out)) continue
            valid[i] = true
            ax[i] = a0; ay[i] = a1; az[i] = a2
            wx[i] = out[0]; wy[i] = out[1]; wz[i] = out[2]

            // Low-pass gravity estimate, with the real elapsed time since the previous valid point.
            val g = gravity
            if (g == null) {
                gravity = doubleArrayOf(a0, a1, a2)
            } else {
                val dt = (t - lastValidTime) / 1000.0
                val alpha = 1.0 - exp(-dt / config.gravityTauS)
                g[0] += alpha * (a0 - g[0])
                g[1] += alpha * (a1 - g[1])
                g[2] += alpha * (a2 - g[2])
            }
            val gNow = gravity!!
            gx[i] = gNow[0]; gy[i] = gNow[1]; gz[i] = gNow[2]
            lastValidTime = t
        }
        return ImuGrid(time, valid, ax, ay, az, wx, wy, wz, gx, gy, gz, config.gridHz)
    }

    /**
     * Linear interpolation of one sensor track. [t] must be non-decreasing across calls (the grid is). Returns false
     * outside the track, or inside a gap between two events longer than [maxGapMs].
     */
    private class Track(val t: DoubleArray, val a: DoubleArray, val b: DoubleArray, val c: DoubleArray) {
        private var j = 0

        fun at(time: Double, maxGapMs: Double, out: DoubleArray): Boolean {
            if (time < t[0] || time > t[t.size - 1]) return false
            while (j + 1 < t.size && t[j + 1] < time) j++
            if (j + 1 >= t.size) {
                out[0] = a[j]; out[1] = b[j]; out[2] = c[j]
                return true
            }
            val dt = t[j + 1] - t[j]
            if (dt > maxGapMs) return false
            val w = if (dt > 0.0) ((time - t[j]) / dt).coerceIn(0.0, 1.0) else 0.0
            out[0] = a[j] + (a[j + 1] - a[j]) * w
            out[1] = b[j] + (b[j + 1] - b[j]) * w
            out[2] = c[j] + (c[j + 1] - c[j]) * w
            return true
        }
    }
}

/**
 * Cleaning of a recording before the grid is built. Each sensor is cleaned as its own stream (the accelerometer and
 * the gyroscope often share a timestamp, so cleaning them together would drop one of them). Non-finite, physically
 * implausible, duplicated and out-of-order events are removed per stream.
 */
object ImuCleaning {
    fun clean(samples: List<MotionSample>): List<MotionSample> {
        val acc = MotionStreams.clean(samples.filter { it.type == MotionSensorType.ACCELEROMETER }, MotionStreams.limitFor(MotionSensorType.ACCELEROMETER))
        val gyro = MotionStreams.clean(samples.filter { it.type == MotionSensorType.GYROSCOPE }, MotionStreams.limitFor(MotionSensorType.GYROSCOPE))
        return acc + gyro
    }
}
