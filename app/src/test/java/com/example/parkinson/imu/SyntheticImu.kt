package com.example.parkinson.imu

import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * SYNTHETIC inertial signals for deterministic algorithm tests only. They are never a patient recording and do not
 * show that real pocket data or real Parkinsonian movement is classified correctly.
 *
 * Frame: the device frame, gravity 9.81 m/s^2 along +z at rest. A posture change of [theta] radians is a rotation of
 * the gravity direction about the x axis, and the gyroscope reports the matching rate (dtheta/dt about x).
 */
object SyntheticImu {

    const val G = 9.81
    const val BASE_NS = 1_000_000_000_000L

    /** Gravity acceleration for posture angle [theta] (rad): rotated by theta about the x axis. */
    fun gravityAt(thetaRad: Double): DoubleArray = doubleArrayOf(0.0, -G * sin(thetaRad), G * cos(thetaRad))

    /**
     * Accelerometer and gyroscope events at [rateHz] for [durationS] seconds.
     * [accelAt] returns the total acceleration (gravity included) at time t (s); [gyroAt] the angular rate (rad/s).
     * [jitterNs] adds a uniform timestamp jitter (seeded). [dropFrom]/[dropTo] (s) remove both sensors' events.
     */
    fun samples(
        durationS: Double,
        rateHz: Double = 100.0,
        accelAt: (Double) -> DoubleArray = { doubleArrayOf(0.0, 0.0, G) },
        gyroAt: (Double) -> DoubleArray = { doubleArrayOf(0.0, 0.0, 0.0) },
        jitterNs: Long = 0L,
        dropFrom: Double = -1.0,
        dropTo: Double = -1.0,
        seed: Int = 3,
    ): List<MotionSample> {
        val rnd = Random(seed)
        val out = ArrayList<MotionSample>()
        val n = (durationS * rateHz).toInt()
        for (k in 0 until n) {
            val t = k / rateHz
            if (dropFrom >= 0 && t >= dropFrom && t < dropTo) continue
            val jitter = if (jitterNs == 0L) 0L else (rnd.nextDouble() * 2 * jitterNs - jitterNs).toLong()
            val ts = BASE_NS + (t * 1e9).toLong() + jitter
            val a = accelAt(t)
            val w = gyroAt(t)
            out += MotionSample(MotionSensorType.ACCELEROMETER, ts, a[0].toFloat(), a[1].toFloat(), a[2].toFloat())
            out += MotionSample(MotionSensorType.GYROSCOPE, ts, w[0].toFloat(), w[1].toFloat(), w[2].toFloat())
        }
        return out
    }

    /**
     * Sit-to-stand posture profile: [calibrationS] seated still, then [cycles] transfers. Each transfer: rise over
     * [riseS] to [peakDeg], hold [standS], sit over [sitS] back to zero, seated [seatedS].
     */
    class TransferProfile(
        val calibrationS: Double = 3.0,
        val cycles: Int = 5,
        val riseS: Double = 1.0,
        val standS: Double = 1.0,
        val sitS: Double = 1.0,
        val seatedS: Double = 1.0,
        val peakDeg: Double = 80.0,
    ) {
        /** Total duration (s) of the profile. */
        val durationS: Double get() = calibrationS + cycles * (riseS + standS + sitS + seatedS)

        /** Posture angle (deg) at time t (s). */
        fun postureDeg(t: Double): Double {
            var local = t - calibrationS
            if (local < 0) return 0.0
            for (c in 0 until cycles) {
                val cycle = riseS + standS + sitS + seatedS
                if (local < cycle) {
                    return when {
                        local < riseS -> peakDeg * local / riseS
                        local < riseS + standS -> peakDeg
                        local < riseS + standS + sitS -> peakDeg * (1 - (local - riseS - standS) / sitS)
                        else -> 0.0
                    }
                }
                local -= cycle
            }
            return 0.0
        }

        /** Angular rate about x (deg/s) at time t (s): the derivative of the posture. */
        fun rateDegPerS(t: Double): Double {
            val h = 0.005
            return (postureDeg(t + h) - postureDeg(t - h)) / (2 * h)
        }

        fun accel(t: Double): DoubleArray = gravityAt(Math.toRadians(postureDeg(t)))

        fun gyro(t: Double): DoubleArray = doubleArrayOf(Math.toRadians(rateDegPerS(t)), 0.0, 0.0)
    }

    /** Walking: vertical acceleration oscillating at [strideHz] (two steps per stride), amplitude [amp] m/s^2. */
    fun walkingAccel(t: Double, strideHz: Double = 1.0, amp: Double = 2.5, noise: Double = 0.0, seed: Int = 11): DoubleArray {
        val rnd = Random((t * 1000).toInt() + seed)
        // |cos(2 pi f t)| has two peaks per period: two steps per stride, so the step rate is 2 f.
        val v = amp * cos(2 * PI * strideHz * t) + if (noise > 0) (rnd.nextDouble() * 2 - 1) * noise else 0.0
        return doubleArrayOf(0.0, 0.0, G + v)
    }

    /** Yaw rate (rad/s) about the gravity axis (z) during the given turn windows (s). */
    fun turnGyro(t: Double, turns: List<Pair<Double, Double>>, degPerS: Double = 60.0): DoubleArray {
        val turning = turns.any { (a, b) -> t >= a && t < b }
        return doubleArrayOf(0.0, 0.0, if (turning) Math.toRadians(degPerS) else 0.0)
    }
}
