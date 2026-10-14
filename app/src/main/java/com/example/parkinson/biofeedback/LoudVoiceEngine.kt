package com.example.parkinson.biofeedback

/**
 * Loud voice practice (like LSVT LOUD): first a few seconds of a normal "aaa" to learn the person's own level, then
 * [BiofeedbackLevels.LOUD_REPS] long loud "aaa"s, each aimed at [targetDb] above that level for [holdSec]. Fed with the
 * microphone level in dB (relative; the phone's microphone is not calibrated) every few tens of milliseconds.
 */
class LoudVoiceEngine(level: Int) {
    val targetDb = BiofeedbackLevels.loudTargetDb(level)
    val holdSec = BiofeedbackLevels.loudHoldSec(level)
    val reps = BiofeedbackLevels.LOUD_REPS

    enum class Phase { CALIBRATING, WAITING, HOLDING, RESTING, DONE }

    data class Snapshot(
        val phase: Phase,
        val rep: Int,
        /** The current level above the normal voice, dB (0 while calibrating). */
        val aboveDb: Double,
        /** Seconds held in the target in the current repetition. */
        val heldSec: Double,
        val successes: Int,
        /** Seconds of normal voice heard while calibrating. */
        val calibratedSec: Double,
    )

    private var phase = Phase.CALIBRATING
    private var rep = 0
    private var baseline = 0.0
    private val calibration = mutableListOf<Double>()
    private var lastMs = -1L
    private var held = 0.0
    private var best = 0.0
    private var quietFor = 0.0
    private var restLeft = 0.0
    private var sawSilence = false
    private val bestHolds = mutableListOf<Double>()
    private val loudLevels = mutableListOf<Double>()

    var snapshot = Snapshot(Phase.CALIBRATING, 0, 0.0, 0.0, 0, 0.0)
        private set

    /** One microphone frame at [timeMs] with level [db] (dBFS). */
    fun onLevel(timeMs: Long, db: Double): Snapshot {
        val dt = if (lastMs < 0) 0.0 else ((timeMs - lastMs).coerceIn(0, 500)) / 1000.0
        lastMs = timeMs
        val voiced = db > SILENCE_DB
        when (phase) {
            Phase.CALIBRATING -> {
                if (voiced) calibration += db
                if (calibration.size * FRAME_SEC >= CALIBRATION_SEC) {
                    // The median of the voiced frames: a cough or a pause does not move it much.
                    baseline = calibration.sorted()[calibration.size / 2]
                    phase = Phase.RESTING
                    restLeft = REST_SEC
                }
            }
            // A voice after a pause starts the repetition; a soft one simply does not count.
            Phase.WAITING -> if (!voiced) sawSilence = true else if (sawSilence) {
                phase = Phase.HOLDING
                held = 0.0; best = 0.0; quietFor = 0.0
            }
            Phase.HOLDING -> {
                if (db - baseline >= targetDb) {
                    held += dt
                    loudLevels += db - baseline
                } else {
                    best = maxOf(best, held)
                    held = 0.0
                }
                quietFor = if (voiced) 0.0 else quietFor + dt
                best = maxOf(best, held)
                if (best >= holdSec || quietFor >= END_SILENCE_SEC) finishRep()
            }
            Phase.RESTING -> {
                restLeft -= dt
                if (restLeft <= 0) {
                    phase = Phase.WAITING
                    sawSilence = false
                }
            }
            Phase.DONE -> Unit
        }
        snapshot = Snapshot(
            phase, rep, if (phase == Phase.CALIBRATING) 0.0 else db - baseline, if (phase == Phase.HOLDING) held else 0.0,
            bestHolds.count { it >= holdSec }, calibration.size * FRAME_SEC,
        )
        return snapshot
    }

    private fun finishRep() {
        bestHolds += minOf(best, holdSec)
        rep++
        phase = if (rep >= reps) Phase.DONE else Phase.RESTING
        restLeft = REST_SEC
    }

    val done: Boolean get() = phase == Phase.DONE

    /** 0-100: how much of the hold time each repetition reached, on average (an unfinished one counts as 0). */
    fun score(): Int = (bestHolds.sumOf { it / holdSec } / reps * 100).toInt().coerceIn(0, 100)

    fun metrics(): Map<String, Double> = mapOf(
        "baselineDb" to baseline.round1(),
        "meanAboveBaselineDb" to (if (loudLevels.isEmpty()) 0.0 else loudLevels.average()).round1(),
        "repsInTarget" to bestHolds.count { it >= holdSec }.toDouble(),
        "reps" to bestHolds.size.toDouble(),
        "longestHoldSec" to (bestHolds.maxOrNull() ?: 0.0).round1(),
    )

    companion object {
        /** Below this level (dBFS) there is no voice. */
        const val SILENCE_DB = -50.0
        const val FRAME_SEC = 0.05
        const val CALIBRATION_SEC = 3.0
        const val END_SILENCE_SEC = 1.5
        const val REST_SEC = 3.0

        /** dBFS of 16-bit samples (RMS). */
        fun levelDb(samples: ShortArray, count: Int): Double {
            if (count <= 0) return -100.0
            var sum = 0.0
            for (i in 0 until count) {
                val v = samples[i] / 32768.0
                sum += v * v
            }
            val rms = Math.sqrt(sum / count)
            return if (rms <= 1e-5) -100.0 else 20 * Math.log10(rms)
        }
    }
}
