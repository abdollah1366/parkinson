package com.example.parkinson.imu

import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * SYNTHETIC walking of a phone in the front trouser pocket, for deterministic algorithm tests only. It is never a
 * patient recording and does not show that real walking or real Parkinsonian gait is classified correctly.
 *
 * Model (device frame): the thigh swings by [swingDeg] about the device x axis once per stride, so gravity rotates
 * about x and the gyroscope reports the swing rate about x. Each heel strike adds a short vertical acceleration pulse;
 * the pocket leg's pulse is larger. Turns add a rotation about the gravity direction.
 */
class SyntheticThighGait(
    val durationS: Double = 30.0,
    val strideS: Double = 1.1,
    /** Fraction of the stride at which the other leg's heel strike falls; 0.5 is symmetric. */
    val stepPhase: Double = 0.5,
    /** Random stride-to-stride variation (standard deviation, fraction of [strideS]). */
    val strideJitter: Double = 0.0,
    val swingDeg: Double = 25.0,
    /** (start s, duration s, angle deg) of each turn; walking continues during a turn. */
    val turns: List<Triple<Double, Double, Double>> = emptyList(),
    /** Tremor added to the gyroscope y axis: frequency (Hz) and amplitude (deg/s). */
    val tremorHz: Double = 0.0,
    val tremorDegPerS: Double = 0.0,
    val seed: Int = 5,
) {
    private val strikes: List<Double>
    private val strideStarts: List<Double>

    init {
        val rnd = Random(seed)
        val starts = ArrayList<Double>()
        var t = 0.0
        while (t < durationS + 3) {
            starts += t
            val g = (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5) * 2.0 // about N(0, 1)
            t += strideS * (1.0 + strideJitter * g)
        }
        strideStarts = starts
        strikes = starts.zipWithNext { a, b -> listOf(a, a + stepPhase * (b - a)) }.flatten()
    }

    /** Thigh angle (rad) at t: one swing per stride, following the (possibly variable) stride boundaries. */
    private fun thigh(t: Double): Double {
        val k = strideStarts.indexOfLast { it <= t }.coerceAtLeast(0)
        val a = strideStarts[k]
        val b = strideStarts.getOrElse(k + 1) { a + strideS }
        val phase = (t - a) / (b - a)
        return Math.toRadians(swingDeg) * sin(2 * PI * phase)
    }

    private fun thighRate(t: Double): Double {
        val h = 0.002
        return (thigh(t + h) - thigh(t - h)) / (2 * h)
    }

    /** Yaw rate (rad/s) at t: raised-cosine profile for each turn. */
    private fun yawRate(t: Double): Double = turns.sumOf { (start, dur, angle) ->
        if (t < start || t > start + dur) 0.0 else Math.toRadians(angle) / dur * (1 - cos(2 * PI * (t - start) / dur))
    }

    private fun verticalPulse(t: Double): Double = strikes.withIndex().sumOf { (i, s) ->
        val amp = if (i % 2 == 0) 3.0 else 2.0
        val d = (t - s) / 0.04
        if (kotlin.math.abs(d) > 5) 0.0 else amp * exp(-0.5 * d * d)
    }

    fun samples(rateHz: Double = 100.0): List<MotionSample> {
        val out = ArrayList<MotionSample>()
        val n = (durationS * rateHz).toInt()
        val g = SyntheticImu.G
        for (k in 0 until n) {
            val t = k / rateHz
            val th = thigh(t)
            // Unit "up" direction in the device frame (gravity reaction), rotated about x by the thigh angle.
            val up = doubleArrayOf(0.0, -sin(th), cos(th))
            val a = g + verticalPulse(t) - 0.6
            val yaw = yawRate(t)
            val tremor = if (tremorHz > 0) Math.toRadians(tremorDegPerS) * sin(2 * PI * tremorHz * t) else 0.0
            val ts = SyntheticImu.BASE_NS + (t * 1e9).toLong()
            out += MotionSample(MotionSensorType.ACCELEROMETER, ts, (a * up[0]).toFloat(), (a * up[1]).toFloat(), (a * up[2]).toFloat())
            out += MotionSample(
                MotionSensorType.GYROSCOPE, ts,
                (thighRate(t) + yaw * up[0]).toFloat(),
                (yaw * up[1] + tremor).toFloat(),
                (yaw * up[2]).toFloat(),
            )
        }
        return out
    }

    fun grid(): ImuGrid = requireNotNull(ImuGridBuilder.build(ImuCleaning.clean(samples())))

    companion object {
        /** Standing still for [durationS], with an optional tremor about the y axis. */
        fun standing(durationS: Double = 3.0, tremorHz: Double = 0.0, tremorDegPerS: Double = 0.0): ImuGrid {
            val samples = SyntheticImu.samples(
                durationS = durationS,
                gyroAt = { t ->
                    doubleArrayOf(0.0, if (tremorHz > 0) Math.toRadians(tremorDegPerS) * sin(2 * PI * tremorHz * t) else 0.0, 0.0)
                },
            )
            return requireNotNull(ImuGridBuilder.build(ImuCleaning.clean(samples)))
        }
    }
}
