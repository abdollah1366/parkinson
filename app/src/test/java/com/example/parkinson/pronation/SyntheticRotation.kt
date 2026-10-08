package com.example.parkinson.pronation

import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Deterministic synthetic pronation/supination recordings (fixed seed).
 *
 * The phone rotates by angle(t) (degrees) about [axis] (device coordinates; NOT aligned with a
 * device axis by default, so the engine must find it). Gyroscope = d(angle)/dt about the axis
 * (+ bias + noise). Accelerometer = gravity rotated with the phone (+ optional translation + noise).
 * The preparation period (baseline) is [baselineMs] of a still phone.
 */
class SyntheticRotation(
    var durationMs: Long = 10_000,
    var plannedMs: Long = 10_000,
    var accelHz: Double = 100.0,
    var gyroHz: Double = 100.0,
    var gyroNoiseDegS: Double = 0.2,
    var accelNoise: Double = 0.02,
    var gyroBiasDegS: Double = 0.0,
    var baselineMs: Long = 5_000,
    /** false = the device has no accelerometer: only gyroscope events are produced. */
    var accelerometer: Boolean = true,
    axis: DoubleArray = doubleArrayOf(0.3, 0.9, 0.3),
    seed: Int = 11
) {
    private val random = Random(seed)
    private val axis: DoubleArray = axis.let { a -> val n = sqrt(a.sumOf { it * it }); DoubleArray(3) { a[it] / n } }
    private var angle: (Double) -> Double = { 0.0 }
    private val gaps = mutableListOf<LongRange>()
    private var duplicateEvery = 0
    private var corruptEvery = 0
    private var translationAmp = 0.0
    private var translationHz = 0.0
    private val startNs = 2_000_000_000L

    /** Regular rotation: peak-to-peak [ppDeg] at [hz]. */
    fun sinusoid(hz: Double, ppDeg: Double) = apply { angle = { t -> ppDeg / 2 * sin(2 * PI * hz * t) } }

    /** Arbitrary angle profile in degrees as a function of time in seconds. */
    fun profile(f: (Double) -> Double) = apply { angle = f }

    /** Rotation stops (angle held) from [fromS] for [lengthS], then continues. */
    fun sinusoidWithPause(hz: Double, ppDeg: Double, fromS: Double, lengthS: Double) = apply {
        angle = { t ->
            val effective = t - (t - fromS).coerceIn(0.0, lengthS)
            ppDeg / 2 * sin(2 * PI * hz * effective)
        }
    }

    /** Deterministic irregular rhythm and amplitude (frequency and amplitude modulation). */
    fun irregular(baseHz: Double, ppDeg: Double) = apply {
        angle = { t ->
            // phase = integral of 2*pi*f(t) with f(t) = baseHz * (1 + 0.35 sin(2 pi 0.23 t))
            val phase = 2 * PI * baseHz * (t - 0.35 / (2 * PI * 0.23) * (cos(2 * PI * 0.23 * t) - 1))
            val amp = ppDeg / 2 * (1 + 0.45 * sin(2 * PI * 0.31 * t + 1.0))
            amp * sin(phase)
        }
    }

    /** Pure translation of the phone (no rotation): linear acceleration on x. */
    fun translation(hz: Double, amplitude: Double) = apply { translationHz = hz; translationAmp = amplitude }

    /** No samples of either sensor in [fromMs, toMs) of the recording. */
    fun gap(fromMs: Long, toMs: Long) = apply { gaps += fromMs until toMs }

    /** Every [every]-th gyroscope sample of the recording becomes NaN (invalid data). */
    fun corruptGyroEvery(every: Int) = apply { corruptEvery = every }

    /** Every [every]-th event is delivered twice (same timestamp). */
    fun duplicates(every: Int) = apply { duplicateEvery = every }

    fun recording(hand: SelectedHand = SelectedHand.RIGHT) =
        PronationRecording(samples(), baselineSamples(), plannedMs, hand, accelerometerAvailable = accelerometer)

    fun samples(): List<MotionSample> {
        val out = ArrayList<MotionSample>()
        if (accelerometer) stream(MotionSensorType.ACCELEROMETER, accelHz, out, recording = true)
        stream(MotionSensorType.GYROSCOPE, gyroHz, out, recording = true)
        return out.sortedBy { it.timestampNs }
    }

    fun baselineSamples(): List<MotionSample> {
        val out = ArrayList<MotionSample>()
        if (accelerometer) stream(MotionSensorType.ACCELEROMETER, accelHz, out, recording = false)
        stream(MotionSensorType.GYROSCOPE, gyroHz, out, recording = false)
        return out.sortedBy { it.timestampNs }
    }

    private fun stream(type: MotionSensorType, hz: Double, out: MutableList<MotionSample>, recording: Boolean) {
        val length = if (recording) durationMs else baselineMs
        // The baseline ends 3 s (countdown) before the recording starts.
        val offsetNs = if (recording) startNs else startNs - (3_000L + baselineMs) * 1_000_000L
        val n = (length / 1000.0 * hz).toInt()
        var count = 0
        for (i in 0 until n) {
            val tMs = i * 1000.0 / hz
            if (recording && gaps.any { tMs.toLong() in it }) continue
            val t = tMs / 1000.0
            val ns = offsetNs + (tMs * 1e6).toLong()
            var s = if (type == MotionSensorType.ACCELEROMETER) accel(t, ns, recording) else gyro(t, ns, recording)
            if (recording && type == MotionSensorType.GYROSCOPE && corruptEvery > 0 && i % corruptEvery == 0) {
                s = s.copy(x = Float.NaN)
            }
            out += s
            count++
            if (duplicateEvery > 0 && recording && count % duplicateEvery == 0) out += s
        }
    }

    private fun theta(t: Double, recording: Boolean) = if (recording) angle(t) else 0.0

    private fun gyro(t: Double, ns: Long, recording: Boolean): MotionSample {
        val h = 1e-4
        val omega = if (recording) (theta(t + h, true) - theta(t - h, true)) / (2 * h) else 0.0
        val v = DoubleArray(3) { (axis[it] * omega + gyroBiasDegS + gauss() * gyroNoiseDegS) * PI / 180 }
        return MotionSample(MotionSensorType.GYROSCOPE, ns, v[0].toFloat(), v[1].toFloat(), v[2].toFloat())
    }

    private fun accel(t: Double, ns: Long, recording: Boolean): MotionSample {
        // Gravity starts on +z (phone face-up) and is seen rotating by -angle about the axis.
        val g = rotate(doubleArrayOf(0.0, 0.0, 9.80665), -theta(t, recording) * PI / 180)
        val lin = if (recording) translationAmp * sin(2 * PI * translationHz * t) else 0.0
        return MotionSample(
            MotionSensorType.ACCELEROMETER, ns,
            (g[0] + lin + gauss() * accelNoise).toFloat(),
            (g[1] + gauss() * accelNoise).toFloat(),
            (g[2] + gauss() * accelNoise).toFloat()
        )
    }

    /** Rodrigues rotation of [v] by [rad] about [axis]. */
    private fun rotate(v: DoubleArray, rad: Double): DoubleArray {
        val k = axis
        val c = cos(rad)
        val s = sin(rad)
        val dot = k[0] * v[0] + k[1] * v[1] + k[2] * v[2]
        val cross = doubleArrayOf(k[1] * v[2] - k[2] * v[1], k[2] * v[0] - k[0] * v[2], k[0] * v[1] - k[1] * v[0])
        return DoubleArray(3) { v[it] * c + cross[it] * s + k[it] * dot * (1 - c) }
    }

    private fun gauss(): Double {
        var u = 0.0
        while (u == 0.0) u = random.nextDouble()
        val v = random.nextDouble()
        return sqrt(-2.0 * ln(u)) * cos(2.0 * PI * v)
    }
}
