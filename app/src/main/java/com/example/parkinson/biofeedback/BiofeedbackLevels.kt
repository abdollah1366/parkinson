package com.example.parkinson.biofeedback

/**
 * The biofeedback exercises the doctor can prescribe, by the code the server uses (server/.../Prescriptions.kt,
 * BiofeedbackExercise). [standing]: done standing, so a safety check comes first and a large stop button stays shown.
 */
enum class BioExercise(val standing: Boolean = false) {
    LOUD_VOICE, BALANCE(standing = true), METRONOME_WALK(standing = true), MEMORY_SEQUENCE, TAP_RHYTHM;

    companion object {
        fun of(code: String?): BioExercise? = entries.firstOrNull { it.name == code }
    }
}

/** What each level asks for. Keep in step with BIO_LEVELS in the doctor's panel (server/.../static/app.js). */
object BiofeedbackLevels {
    private fun pick(level: Int, a: Double, b: Double, c: Double) = when (level.coerceIn(1, 3)) {
        1 -> a
        2 -> b
        else -> c
    }

    // Loud voice: how much louder than the person's own normal voice, held for how long, five times.
    fun loudTargetDb(level: Int) = pick(level, 4.0, 7.0, 10.0)
    fun loudHoldSec(level: Int) = pick(level, 3.0, 5.0, 8.0)
    const val LOUD_REPS = 5

    // Balance: the circle the tilt of the trunk must stay in, and for how long.
    fun balanceRadiusDeg(level: Int) = pick(level, 5.0, 3.5, 2.5)
    fun balanceDurationSec(level: Int) = pick(level, 20.0, 30.0, 45.0)

    // Walking with a metronome: beats per minute (a step on each beat) and how long.
    fun walkBpm(level: Int) = pick(level, 90.0, 100.0, 110.0)
    fun walkDurationSec(level: Int) = pick(level, 60.0, 120.0, 180.0)

    // Memory: the length of the first sequence; it grows by one after each correct answer.
    fun memoryStartLength(level: Int) = pick(level, 3.0, 4.0, 5.0).toInt()
    const val MEMORY_MAX_LENGTH = 9
    const val MEMORY_MAX_MISTAKES = 2

    // Tapping: each hand for this long, aiming at this many taps a second on two alternating targets.
    fun tapDurationSec(level: Int) = pick(level, 15.0, 20.0, 30.0)
    fun tapTargetRate(level: Int) = pick(level, 2.0, 3.0, 4.0)
}

/** The outcome of one session, as the doctor's server keeps it. */
data class BioSession(
    val prescriptionId: Long,
    val startedAtEpochMs: Long,
    val durationMs: Int,
    /** 0-100. */
    val score: Int,
    /** False when the person stopped early. */
    val completed: Boolean,
    /** Measurements by the names the doctor's panel shows (METRIC_LABELS in app.js). */
    val metrics: Map<String, Double>,
) {
    val key: String get() = "$prescriptionId@$startedAtEpochMs"
}

internal fun Double.round1(): Double = Math.round(this * 10) / 10.0
