package com.example.parkinson.imu

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp

/**
 * ENGINEERING parameters of the gait step detector. They are not clinically validated. They are defaults for a phone
 * worn in a front trouser pocket; the detector adapts the amplitude threshold to each recording.
 */
data class GaitImuThresholds(
    /** Length of the timed walking trial (ms). Configurable; 30 s by default. */
    val walkingMs: Double = 30_000.0,
    /** Band of the vertical acceleration used for steps (Hz). */
    val highPassHz: Double = 0.5,
    val lowPassHz: Double = 5.0,
    /** Smallest peak amplitude accepted (m/s^2). */
    val minAmplitudeMs2: Double = 0.35,
    /** Adaptive threshold: this fraction of the 95th-percentile amplitude of the band-passed signal (adapts to the walker). */
    val peakFraction: Double = 0.6,
    /** Refractory interval: a second peak this close to a kept peak is not a new step (ms). */
    val refractoryMs: Double = 250.0,
    /** Plausible interval between two consecutive steps (ms): about 40 to 200 steps per minute. */
    val minIntervalMs: Double = 300.0,
    val maxIntervalMs: Double = 1_500.0,
    /** A walking bout needs at least this many consecutive plausible steps. */
    val boutMinSteps: Int = 4,
    /** Rotation about the gravity direction above this (deg/s) is a turn; steps near a turn are excluded. */
    val turnDegPerSec: Double = 40.0,
    /** Steps closer than this to a turn are excluded (ms). */
    val turnExclusionMs: Double = 500.0,
    /** Minimum duration of a turn (ms). */
    val minTurnMs: Double = 300.0,
    /** A data gap longer than this breaks walking bouts (ms). */
    val maxGapMs: Double = 300.0,
    /** Steps needed before cadence and step-interval statistics are reported. */
    val minStepsForMetrics: Int = 6,
) {
    fun summary(): String =
        "bp=$highPassHz-${lowPassHz}Hz;amp>=$minAmplitudeMs2;frac=$peakFraction;refr=$refractoryMs;" +
            "interval=$minIntervalMs-${maxIntervalMs}ms;bout>=$boutMinSteps;turn>$turnDegPerSec"
}

/** One step accepted into a walking bout (grid time, ms). */
data class DetectedStep(val timeMs: Double, val amplitudeMs2: Double)

/** A run of consecutive plausible steps, without data gaps between them. */
data class WalkingBout(val startMs: Double, val endMs: Double, val steps: Int)

data class GaitImuAnalysis(
    val threshold: Double?,
    /** Peaks above the threshold after the refractory rule (before plausibility and turn checks). */
    val candidatePeaks: Int,
    /** Steps that belong to walking bouts. */
    val steps: List<DetectedStep>,
    val bouts: List<WalkingBout>,
    /** Steps removed because they lie near a turn. */
    val turnRejected: Int,
    /** Peaks removed because they were isolated or their interval was implausible. */
    val implausibleRejected: Int,
    /** Intervals between consecutive steps inside bouts (ms). */
    val intervalsMs: List<Double>,
    val turningMs: Double,
    val coveragePercent: Double,
    val invalidIntervals: List<InvalidInterval>,
)

object GaitStepDetector {

    fun analyze(grid: ImuGrid, thresholds: GaitImuThresholds = GaitImuThresholds()): GaitImuAnalysis {
        val n = grid.size
        val v = DoubleArray(n) { grid.verticalLinearAcceleration(it).let { x -> if (x.isFinite()) x else 0.0 } }
        val band = bandPass(v, grid, thresholds)
        val s = DoubleArray(n) { abs(band[it]) }

        // Adaptive threshold: a fraction of the recording's own 95th-percentile amplitude, never below the floor.
        // (A median plus deviations rule suits noise, not the peaks of a periodic signal.)
        val validValues = (0 until n).filter { grid.valid[it] }.map { s[it] }.sorted()
        if (validValues.size < 8) {
            return GaitImuAnalysis(null, 0, emptyList(), emptyList(), 0, 0, emptyList(), 0.0, grid.coveragePercent, invalidIntervals(grid, thresholds.maxGapMs))
        }
        val p95 = validValues[((validValues.size - 1) * 0.95).toInt()]
        val threshold = maxOf(thresholds.minAmplitudeMs2, thresholds.peakFraction * p95)

        // Candidate peaks: local maxima above the threshold on valid points, refractory rule kept greedy by amplitude.
        val peaks = ArrayList<Int>()
        for (i in 1 until n - 1) {
            if (!grid.valid[i] || s[i] < threshold) continue
            if (!grid.valid[i - 1] || !grid.valid[i + 1]) continue
            if (s[i] >= s[i - 1] && s[i] > s[i + 1]) peaks += i
        }
        val kept = ArrayList<Int>()
        for (i in peaks.sortedByDescending { s[it] }) {
            if (kept.none { abs(grid.timeMs[it] - grid.timeMs[i]) < thresholds.refractoryMs }) kept += i
        }
        kept.sort()

        // Turns: rotation about the gravity direction above the turn rate.
        val turning = BooleanArray(n) { grid.valid[it] && abs(grid.yawDegPerSec(it)) >= thresholds.turnDegPerSec }
        val turnRuns = runsOf(turning, grid, thresholds.minTurnMs)
        val turningMs = turnRuns.sumOf { (a, b) -> grid.timeMs[b] - grid.timeMs[a] }
        fun nearTurn(t: Double): Boolean = turnRuns.any { (a, b) ->
            t >= grid.timeMs[a] - thresholds.turnExclusionMs && t <= grid.timeMs[b] + thresholds.turnExclusionMs
        }

        val notTurn = kept.filter { !nearTurn(grid.timeMs[it]) }
        val turnRejected = kept.size - notTurn.size

        // Plausibility: consecutive steps form bouts when the interval is plausible and no data gap lies between them.
        val invalidPrefix = IntArray(n + 1)
        for (i in 0 until n) invalidPrefix[i + 1] = invalidPrefix[i] + if (grid.valid[i]) 0 else 1
        fun gapBetween(a: Int, b: Int): Boolean = (invalidPrefix[b] - invalidPrefix[a + 1]) > 0 &&
            grid.timeMs[b] - grid.timeMs[a] > thresholds.maxGapMs

        val bouts = ArrayList<WalkingBout>()
        val steps = ArrayList<DetectedStep>()
        val intervals = ArrayList<Double>()
        var run = ArrayList<Int>()
        fun flush() {
            if (run.size >= thresholds.boutMinSteps) {
                bouts += WalkingBout(grid.timeMs[run.first()], grid.timeMs[run.last()], run.size)
                run.forEach { steps += DetectedStep(grid.timeMs[it], s[it]) }
                run.zipWithNext().forEach { (a, b) -> intervals += grid.timeMs[b] - grid.timeMs[a] }
            }
            run = ArrayList()
        }
        for (idx in notTurn) {
            if (run.isEmpty()) {
                run += idx
                continue
            }
            val last = run.last()
            val gap = grid.timeMs[idx] - grid.timeMs[last]
            val plausible = gap in thresholds.minIntervalMs..thresholds.maxIntervalMs && !gapBetween(last, idx)
            if (plausible) run += idx else {
                flush()
                run += idx
            }
        }
        flush()

        val implausible = notTurn.size - steps.size
        return GaitImuAnalysis(
            threshold = threshold,
            candidatePeaks = kept.size,
            steps = steps,
            bouts = bouts,
            turnRejected = turnRejected,
            implausibleRejected = implausible,
            intervalsMs = intervals,
            turningMs = turningMs,
            coveragePercent = grid.coveragePercent,
            invalidIntervals = invalidIntervals(grid, thresholds.maxGapMs),
        )
    }

    /** High-pass (removes the slow component) followed by a low-pass (removes sensor noise), one-pole filters on the grid. */
    private fun bandPass(x: DoubleArray, grid: ImuGrid, t: GaitImuThresholds): DoubleArray {
        val dt = 1.0 / grid.gridHz
        val aLow = 1.0 - exp(-2 * PI * t.highPassHz * dt)
        val aSmooth = 1.0 - exp(-2 * PI * t.lowPassHz * dt)
        val out = DoubleArray(x.size)
        var slow = x.firstOrNull() ?: 0.0
        var smooth = 0.0
        for (i in x.indices) {
            slow += aLow * (x[i] - slow)
            val highPassed = x[i] - slow
            smooth += aSmooth * (highPassed - smooth)
            out[i] = smooth
        }
        return out
    }

    /** Index ranges [first, last] of runs of true values that last at least [minMs]. */
    private fun runsOf(flags: BooleanArray, grid: ImuGrid, minMs: Double): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        var start = -1
        for (i in 0..flags.size) {
            val on = i < flags.size && flags[i]
            if (on && start < 0) start = i
            if (!on && start >= 0) {
                val end = i - 1
                if (grid.timeMs[end] - grid.timeMs[start] >= minMs) out += start to end
                start = -1
            }
        }
        return out
    }

    private fun invalidIntervals(grid: ImuGrid, maxGapMs: Double): List<InvalidInterval> {
        val out = ArrayList<InvalidInterval>()
        var start: Double? = null
        for (i in 0 until grid.size) {
            if (!grid.valid[i]) {
                if (start == null) start = grid.timeMs[i]
            } else if (start != null) {
                val end = grid.timeMs[i]
                if (end - start > maxGapMs) out += InvalidInterval(start, end)
                start = null
            }
        }
        if (start != null && grid.size > 0 && grid.timeMs[grid.size - 1] - start > maxGapMs) {
            out += InvalidInterval(start, grid.timeMs[grid.size - 1])
        }
        return out
    }
}
