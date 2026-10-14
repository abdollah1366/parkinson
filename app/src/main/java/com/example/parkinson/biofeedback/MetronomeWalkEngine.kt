package com.example.parkinson.biofeedback

/**
 * Walking to a metronome: a beat at [bpm] for [durationSec], one step on each beat. Steps are found in the
 * accelerometer (phone in a front trouser pocket or held at the chest): peaks of the acceleration's size above its
 * running mean. The cadence is compared with the beat.
 */
class MetronomeWalkEngine(level: Int) {
    val bpm = BiofeedbackLevels.walkBpm(level)
    val durationSec = BiofeedbackLevels.walkDurationSec(level)
    val beatMs: Long get() = (60_000 / bpm).toLong()

    data class Snapshot(val elapsedSec: Double, val steps: Int, /** Steps a minute over the last 10 s; 0 until known. */ val cadence: Double)

    private var startNs = -1L
    private var mean = 9.81
    private var smooth = 9.81
    private var rising = false
    private var peak = 0.0
    private var peakNs = 0L
    private var lastStepNs = -1L
    private val stepTimes = ArrayDeque<Long>()
    private var steps = 0
    private var elapsed = 0.0
    /** Steps after the first [WARMUP_SEC] seconds, for the cadence of the whole walk. */
    private var countedSteps = 0

    var snapshot = Snapshot(0.0, 0, 0.0)
        private set

    fun onAccelerometer(timestampNs: Long, x: Float, y: Float, z: Float): Snapshot {
        if (startNs < 0) startNs = timestampNs
        elapsed = (timestampNs - startNs) / 1e9
        if (elapsed > durationSec) return snapshot
        val magnitude = Math.sqrt((x * x + y * y + z * z).toDouble())
        mean += 0.01 * (magnitude - mean)
        val previous = smooth
        smooth += 0.3 * (magnitude - smooth)
        if (smooth > previous) {
            rising = true
            if (smooth > peak) { peak = smooth; peakNs = timestampNs }
        } else if (rising) {
            // A peak just passed.
            rising = false
            if (peak - mean > THRESHOLD && (lastStepNs < 0 || peakNs - lastStepNs >= MIN_STEP_NS)) {
                lastStepNs = peakNs
                steps++
                if (elapsed >= WARMUP_SEC) countedSteps++
                stepTimes.addLast(peakNs)
            }
            peak = 0.0
        }
        while (stepTimes.isNotEmpty() && timestampNs - stepTimes.first() > WINDOW_NS) stepTimes.removeFirst()
        val cadence = if (elapsed < 5 || stepTimes.size < 3) 0.0
        else (stepTimes.size - 1) * 60e9 / (stepTimes.last() - stepTimes.first()).coerceAtLeast(1)
        snapshot = Snapshot(minOf(elapsed, durationSec), steps, cadence)
        return snapshot
    }

    val done: Boolean get() = elapsed >= durationSec

    val elapsedMs: Long get() = (minOf(elapsed, durationSec) * 1000).toLong()

    /** Steps a minute after the warm-up. */
    fun cadence(): Double {
        val walked = minOf(elapsed, durationSec) - WARMUP_SEC
        return if (walked <= 5) 0.0 else countedSteps * 60 / walked
    }

    /** 100 when the cadence matches the beat; 0 at 50% off or with too few steps. */
    fun score(): Int {
        val c = cadence()
        if (steps < MIN_STEPS || c <= 0) return 0
        return (100 - Math.abs(c - bpm) / bpm * 200).toInt().coerceIn(0, 100)
    }

    fun metrics(): Map<String, Double> = mapOf(
        "steps" to steps.toDouble(),
        "cadence" to cadence().round1(),
        "targetCadence" to bpm,
    )

    companion object {
        const val THRESHOLD = 1.2
        const val MIN_STEP_NS = 300_000_000L
        const val WINDOW_NS = 10_000_000_000L
        const val WARMUP_SEC = 5.0
        const val MIN_STEPS = 10
    }
}
