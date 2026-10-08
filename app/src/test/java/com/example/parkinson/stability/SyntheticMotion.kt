package com.example.parkinson.stability

import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic synthetic accelerometer + gyroscope recordings (fixed seed).
 * Phone held face-up: gravity on +z. Rates and gaps are explicit so tests never rely on luck.
 */
class SyntheticMotion(
    var durationMs: Long = 15_000,
    var accelHz: Double = 100.0,
    var gyroHz: Double = 100.0,
    /** Gaussian noise SD per axis. */
    var accelNoise: Double = 0.01,
    var gyroNoiseDegS: Double = 0.1,
    seed: Int = 7
) {
    private val random = Random(seed)
    private val gaps = mutableListOf<LongRange>()
    private val corrupt = mutableSetOf<Int>()
    private var startNs = 1_000_000_000L

    /** Rotational oscillation: amplitude in deg/s about the x axis at [hz]. */
    private var oscHz = 0.0
    private var oscAmpDegS = 0.0

    /** Slow sway: angular velocity amplitude (deg/s) at [hz] about y. */
    private var swayHz = 0.0
    private var swayAmpDegS = 0.0

    /** Tilt the gravity vector from +z towards +y by this many degrees over the recording. */
    private var tiltDeg = 0.0

    /** Linear acceleration oscillation amplitude (m/s^2) on x at [oscHz]. */
    private var linAmp = 0.0

    fun oscillation(hz: Double, gyroAmpDegS: Double, linearAmp: Double = 0.0) = apply {
        oscHz = hz; oscAmpDegS = gyroAmpDegS; linAmp = linearAmp
    }

    fun sway(hz: Double, gyroAmpDegS: Double) = apply { swayHz = hz; swayAmpDegS = gyroAmpDegS }
    fun tilt(degrees: Double) = apply { tiltDeg = degrees }

    /** No samples of either sensor in [fromMs, toMs). */
    fun gap(fromMs: Long, toMs: Long) = apply { gaps += fromMs until toMs }

    /** Every [every]-th accelerometer sample becomes NaN. */
    fun corruptEvery(every: Int) = apply { for (i in 0 until 10_000 step every) corrupt += i }

    fun samples(): List<MotionSample> {
        val out = ArrayList<MotionSample>()
        stream(MotionSensorType.ACCELEROMETER, accelHz, out)
        stream(MotionSensorType.GYROSCOPE, gyroHz, out)
        // Interleave by time, like arriving events.
        return out.sortedBy { it.timestampNs }
    }

    fun recording(hand: SelectedHand = SelectedHand.RIGHT) = StabilityRecording(samples(), 15_000L, hand)

    private fun stream(type: MotionSensorType, hz: Double, out: MutableList<MotionSample>) {
        val n = (durationMs / 1000.0 * hz).toInt()
        for (i in 0 until n) {
            val tMs = i * 1000.0 / hz
            if (gaps.any { tMs.toLong() in it }) continue
            val t = tMs / 1000.0
            val ns = startNs + (tMs * 1e6).toLong()
            out += if (type == MotionSensorType.ACCELEROMETER) accel(i, t, ns) else gyro(t, ns)
        }
    }

    private fun accel(i: Int, t: Double, ns: Long): MotionSample {
        val tilt = tiltDeg * t / (durationMs / 1000.0) * PI / 180
        val g = 9.80665
        var x = linAmp * sin(2 * PI * oscHz * t) + gauss() * accelNoise
        val y = g * sin(tilt) + gauss() * accelNoise
        val z = g * cos(tilt) + gauss() * accelNoise
        if (i in corrupt) x = Double.NaN
        return MotionSample(MotionSensorType.ACCELEROMETER, ns, x.toFloat(), y.toFloat(), z.toFloat())
    }

    private fun gyro(t: Double, ns: Long): MotionSample {
        val d2r = PI / 180
        val tiltRate = tiltDeg / (durationMs / 1000.0)
        val x = (oscAmpDegS * sin(2 * PI * oscHz * t) + tiltRate + gauss() * gyroNoiseDegS) * d2r
        val y = (swayAmpDegS * sin(2 * PI * swayHz * t) + gauss() * gyroNoiseDegS) * d2r
        val z = gauss() * gyroNoiseDegS * d2r
        return MotionSample(MotionSensorType.GYROSCOPE, ns, x.toFloat(), y.toFloat(), z.toFloat())
    }

    private fun gauss(): Double {
        var u = 0.0
        while (u == 0.0) u = random.nextDouble()
        val v = random.nextDouble()
        return sqrt(-2.0 * kotlin.math.ln(u)) * cos(2.0 * PI * v)
    }
}
