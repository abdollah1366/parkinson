package com.example.parkinson.tapping

import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.raw.FrameStatus
import com.example.parkinson.tapping.raw.TapFrame
import kotlin.math.PI
import kotlin.math.cos
import kotlin.random.Random

/**
 * Deterministic synthetic Finger Tapping recordings for unit tests.
 *
 * The opening (thumb-index distance / palm size) of each tap cycle follows
 *   base + amplitude * (1 - cos(2 pi phase)) / 2
 * so every cycle starts and ends with closed fingers and is fully open in the middle.
 * A tap (closing) is therefore completed once per cycle.
 */
class SyntheticTapping(
    val fps: Double = 30.0,
    val durationMs: Long = 10_000L,
    val startMs: Long = 1_000L,
    val handScalePx: Double = 120.0,
    val baseOpening: Double = 0.08,
    val noiseSigma: Double = 0.0,
    seed: Int = 42
) {
    private val random = Random(seed)

    /** Cycle periods in ms; the cycles are laid back to back from the start. */
    var periodsMs: List<Double> = emptyList()

    /** Amplitude per cycle (palm sizes); the last value is reused when the list is shorter. */
    var amplitudes: List<Double> = listOf(0.9)

    /** Phase offset of the first cycle, 0..1. 0.5 = recording starts with the fingers open. */
    var startPhase: Double = 0.0

    /** Time ranges (relative to start, ms) with a non-VALID status. */
    val statusRanges = mutableListOf<Triple<Long, Long, FrameStatus>>()

    /** Constant opening (no tapping) when set. */
    var constantOpening: Double? = null

    fun regular(hz: Double): SyntheticTapping = apply {
        val period = 1000.0 / hz
        periodsMs = List(((durationMs / period) + 2).toInt()) { period }
    }

    fun status(fromMs: Long, toMs: Long, status: FrameStatus): SyntheticTapping = apply {
        statusRanges += Triple(fromMs, toMs, status)
    }

    /**
     * Number of closings inside the recording. A closing is detected when the opening falls
     * below the close threshold, at phase ~0.8 of the cycle.
     */
    fun expectedTaps(closingPhase: Double = 0.8): Int {
        if (constantOpening != null) return 0
        var t = -startPhase * (periodsMs.firstOrNull() ?: 0.0)
        var count = 0
        for (p in periodsMs) {
            val closing = t + closingPhase * p
            if (closing > 0 && closing <= durationMs) count++
            t += p
        }
        return count
    }

    fun openingAt(relMs: Double): Double {
        constantOpening?.let { return it }
        var t = -startPhase * (periodsMs.firstOrNull() ?: 0.0)
        periodsMs.forEachIndexed { i, p ->
            if (relMs < t + p) {
                val phase = (relMs - t) / p
                val amp = amplitudes.getOrElse(i) { amplitudes.last() }
                return baseOpening + amp * (1 - cos(2 * PI * phase)) / 2
            }
            t += p
        }
        return baseOpening
    }

    fun frames(): List<TapFrame> {
        val frames = ArrayList<TapFrame>()
        val step = 1000.0 / fps
        var i = 0
        while (true) {
            val rel = i * step
            if (rel > durationMs) break
            val ts = startMs + Math.round(rel)
            val status = statusRanges.firstOrNull { rel >= it.first && rel < it.second }?.third ?: FrameStatus.VALID
            frames += if (status == FrameStatus.VALID) {
                val opening = (openingAt(rel) + random.nextGaussian() * noiseSigma).coerceAtLeast(0.0)
                TapFrame(i, ts, FrameStatus.VALID, opening * handScalePx, handScalePx, 0.95)
            } else {
                TapFrame(i, ts, status)
            }
            i++
        }
        return frames
    }

    fun recording(
        endMs: Long = startMs + durationMs,
        plannedDurationMs: Long = 10_000L,
        hand: SelectedHand = SelectedHand.RIGHT
    ) = TapRecording(frames(), startMs, endMs, plannedDurationMs, hand)
}

private fun Random.nextGaussian(): Double {
    // Box-Muller, deterministic for a seeded Random.
    var u1 = nextDouble()
    while (u1 <= 1e-12) u1 = nextDouble()
    val u2 = nextDouble()
    return kotlin.math.sqrt(-2.0 * kotlin.math.ln(u1)) * cos(2 * PI * u2)
}
