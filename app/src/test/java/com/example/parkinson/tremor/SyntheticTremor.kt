package com.example.parkinson.tremor

import com.example.parkinson.tapping.raw.FrameStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random

/**
 * SYNTHETIC palm trajectories for unit tests only. Never used as an assessment result.
 * Amplitudes and noise are in hand lengths; the hand size is [SCALE] px and the palm starts at (CX, CY).
 */
object SyntheticTremor {
    const val SCALE = 100.0
    private const val CX = 400.0
    private const val CY = 300.0

    /**
     * Frames at about 30 fps (33.3 ms apart). A circular movement of radius [amplitude] at [frequencyHz]
     * (null = no periodic movement), uniform noise of +-[noise], and a linear drift of [driftPerSecond]
     * along x.
     */
    fun frames(
        durationMs: Long = 15_000L,
        frequencyHz: Double? = 5.0,
        amplitude: Double = 0.05,
        noise: Double = 0.0,
        driftPerSecond: Double = 0.0,
        seed: Long = 1L,
        stepMs: Double = 1000.0 / 30.0,
    ): List<RestingTremorFrame> {
        val rnd = Random(seed)
        val n = (durationMs / stepMs).toInt() + 1
        return (0 until n).map { k ->
            val tS = k * stepMs / 1000.0
            val (px, py) = if (frequencyHz == null) 0.0 to 0.0 else {
                val phase = 2 * PI * frequencyHz * tS
                amplitude * cos(phase) to amplitude * sin(phase)
            }
            val nx = if (noise == 0.0) 0.0 else (rnd.nextDouble() * 2 - 1) * noise
            val ny = if (noise == 0.0) 0.0 else (rnd.nextDouble() * 2 - 1) * noise
            RestingTremorFrame(
                index = k,
                timestampMs = (k * stepMs).roundToLong(),
                status = FrameStatus.VALID,
                palmXPx = CX + (px + nx + driftPerSecond * tS) * SCALE,
                palmYPx = CY + (py + ny) * SCALE,
                handScalePx = SCALE,
            )
        }
    }

    /** Replaces the frames for which [predicate] holds by a frame with [status] (no palm position). */
    fun withStatus(
        frames: List<RestingTremorFrame>,
        status: FrameStatus,
        predicate: (RestingTremorFrame) -> Boolean,
    ): List<RestingTremorFrame> = frames.map { f ->
        if (predicate(f)) f.copy(status = status, palmXPx = Double.NaN, palmYPx = Double.NaN, handScalePx = Double.NaN) else f
    }

    /** Same frames, re-timed by [timestamps] (same count). */
    fun withTimestamps(frames: List<RestingTremorFrame>, timestamps: List<Long>): List<RestingTremorFrame> =
        frames.mapIndexed { i, f -> f.copy(timestampMs = timestamps[i]) }

    fun recording(frames: List<RestingTremorFrame>, plannedDurationMs: Long = 15_000L) =
        RestingTremorRecording(frames, plannedDurationMs)
}
