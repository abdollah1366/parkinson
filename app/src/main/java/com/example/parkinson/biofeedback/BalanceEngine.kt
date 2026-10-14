package com.example.parkinson.biofeedback

import kotlin.math.asin
import kotlin.math.sqrt

/**
 * Standing balance: the phone is held in both hands in front of the chest, screen up, elbows against the body, so it
 * tilts with the trunk. The tilt moves a dot; the person keeps it inside a circle of [radiusDeg] for [durationSec]. The first second, held
 * still, is the upright position. Fed with accelerometer samples (m/s², with gravity).
 */
class BalanceEngine(level: Int) {
    val radiusDeg = BiofeedbackLevels.balanceRadiusDeg(level)
    val durationSec = BiofeedbackLevels.balanceDurationSec(level)

    /** [sideDeg]: to the right is positive; [forwardDeg]: forward is positive. */
    data class Snapshot(val calibrating: Boolean, val sideDeg: Double, val forwardDeg: Double, val inside: Boolean, val elapsedSec: Double)

    private var startNs = -1L
    private var lastNs = -1L
    private val g = DoubleArray(3)
    private var filtered = false
    private val upright = DoubleArray(3)
    private var uprightCount = 0
    private var calibrated = false
    private var elapsed = 0.0
    private var insideSec = 0.0
    private var swaySum = 0.0
    private var swayN = 0
    private var swayMax = 0.0

    var snapshot = Snapshot(true, 0.0, 0.0, true, 0.0)
        private set

    fun onAccelerometer(timestampNs: Long, x: Float, y: Float, z: Float): Snapshot {
        if (startNs < 0) startNs = timestampNs
        val dt = if (lastNs < 0) 0.0 else ((timestampNs - lastNs) / 1e9).coerceIn(0.0, 0.2)
        lastNs = timestampNs
        // Low-pass: the trunk's tilt, not the hands' jitter.
        if (!filtered) {
            g[0] = x.toDouble(); g[1] = y.toDouble(); g[2] = z.toDouble(); filtered = true
        } else {
            g[0] += ALPHA * (x - g[0]); g[1] += ALPHA * (y - g[1]); g[2] += ALPHA * (z - g[2])
        }
        if (!calibrated) {
            if ((timestampNs - startNs) / 1e9 >= SETTLE_SEC) {
                for (i in 0..2) upright[i] += g[i]
                uprightCount++
            }
            if ((timestampNs - startNs) / 1e9 >= SETTLE_SEC + CALIBRATION_SEC && uprightCount > 0) {
                for (i in 0..2) upright[i] /= uprightCount.toDouble()
                calibrated = true
            }
            snapshot = Snapshot(true, 0.0, 0.0, true, 0.0)
            return snapshot
        }
        // Leaning right lowers the phone's right edge (x falls); leaning forward lowers its top edge (y falls).
        val side = tilt(g, 0) - tilt(upright, 0)
        val forward = -(tilt(g, 1) - tilt(upright, 1))
        val sway = sqrt(side * side + forward * forward)
        val inside = sway <= radiusDeg
        if (elapsed < durationSec) {
            elapsed += dt
            if (inside) insideSec += dt
            swaySum += sway; swayN++
            swayMax = maxOf(swayMax, sway)
        }
        snapshot = Snapshot(false, -side, forward, inside, minOf(elapsed, durationSec))
        return snapshot
    }

    private fun tilt(v: DoubleArray, axis: Int): Double {
        val n = sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2])
        return if (n < 1e-6) 0.0 else Math.toDegrees(asin((v[axis] / n).coerceIn(-1.0, 1.0)))
    }

    val done: Boolean get() = calibrated && elapsed >= durationSec

    val elapsedMs: Long get() = (elapsed * 1000).toLong()

    /** The share of the time inside the circle, 0-100. */
    fun score(): Int = if (elapsed <= 0) 0 else (insideSec / maxOf(elapsed, durationSec) * 100).toInt().coerceIn(0, 100)

    fun metrics(): Map<String, Double> = mapOf(
        "insidePercent" to (if (elapsed <= 0) 0.0 else insideSec / elapsed * 100).round1(),
        "meanSwayDeg" to (if (swayN == 0) 0.0 else swaySum / swayN).round1(),
        "maxSwayDeg" to swayMax.round1(),
    )

    companion object {
        const val ALPHA = 0.1
        const val SETTLE_SEC = 1.0
        const val CALIBRATION_SEC = 1.0
    }
}
