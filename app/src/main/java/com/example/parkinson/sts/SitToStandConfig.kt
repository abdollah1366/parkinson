package com.example.parkinson.sts

/**
 * Versions stored with every Sit-to-Stand result. Bump [ALGORITHM_VERSION] for any change to the features,
 * the movement score, the state machine or the metrics. See docs/sit-to-stand-algorithm.md.
 */
object SitToStandVersions {
    const val ALGORITHM_VERSION = "sts-algo-1.0.0"

    /** No score: the result carries measurements and data quality only. */
    const val SCORING_VERSION = "not-scored"
}

/**
 * A chair-stand protocol. Each protocol has its own repetition target and timing rules, so a different
 * protocol (for example a 30-second chair-stand test) is added as a new object and is never scored with
 * the rules of another one.
 */
sealed interface SitToStandProtocol {
    val id: String

    /** Repetitions that must be completed for a valid result. */
    val targetRepetitions: Int

    /** Longest allowed attempt (ms) before the test is stopped as incomplete. */
    val timeoutMs: Long

    /** The standard Five Times Sit-to-Stand test (5xSTS): five full stand-and-sit cycles, as fast as safely possible. */
    data object FiveTimesSitToStand : SitToStandProtocol {
        override val id: String = "five_times_sit_to_stand"
        override val targetRepetitions: Int = 5
        override val timeoutMs: Long = 60_000L
    }

    companion object {
        fun fromId(id: String?): SitToStandProtocol? = when (id) {
            FiveTimesSitToStand.id -> FiveTimesSitToStand
            else -> null
        }
    }
}

/** Timing of one attempt (ms). */
data class SitToStandSessionConfig(
    /** Preparation countdown before the attempt starts. */
    val countdownMs: Long = 3_000L,
    /** Seated calibration window (ms). */
    val calibrationMs: Long = 2_000L,
    /** Frames captured before the end that the model has not delivered yet are waited for. */
    val lateFrameGraceMs: Long = 200L,
    val tickMs: Long = 100L,
    /** No pose result for this long = camera pipeline stalled. */
    val frameStarvationMs: Long = 2_000L,
    /** This many pose errors in a row = model failure. */
    val maxConsecutiveErrors: Int = 5,
)

/**
 * Movement-score and state-machine parameters. ENGINEERING values, not clinically validated.
 *
 * The movement score is relative to the person's own seated calibration, so it does not assume a fixed
 * joint angle: score = max(hipRise / [hipRiseFullStand], kneeExtension / [kneeExtensionFullStand]).
 * Score 0 = seated baseline, score 1 = a full stand as defined by the two reference amounts. A person with a
 * limited range of motion reaches less than the reference and is not counted; that is documented, not hidden.
 */
data class SitToStandThresholds(
    /** Upward hip movement, in shin lengths, that counts as a full stand. */
    val hipRiseFullStand: Double = 0.6,
    /** Knee extension above the seated knee angle, in degrees, that counts as a full stand. */
    val kneeExtensionFullStand: Double = 40.0,
    /** Score that starts a movement (hysteresis: above the seated band). */
    val moveStartScore: Double = 0.2,
    /** Score at or above which the person is standing. */
    val standingScore: Double = 1.0,
    /** Score below which a standing person is considered to be sitting down. */
    val leaveStandingScore: Double = 0.8,
    /** Score at or below which the person is seated again (below [moveStartScore] for hysteresis). */
    val seatedScore: Double = 0.35,
    /** A state change must hold this long before it counts (ms). Rejects single noisy frames. */
    val minHoldMs: Long = 200L,
    /** A tracking loss longer than this invalidates the attempt (ms). */
    val maxTrackingLossMs: Long = 1_000L,
) {
    fun summary(): String =
        "stand=$hipRiseFullStand/$kneeExtensionFullStand;start=$moveStartScore;" +
            "stand>=$standingScore;leave<$leaveStandingScore;seated<=$seatedScore;hold=${minHoldMs}ms;" +
            "loss=${maxTrackingLossMs}ms"
}

/** Quality limits of the recording (technical reliability, never the person). Not clinically validated. */
data class SitToStandQualityThresholds(
    /** Valid-frame share below this = the attempt is not usable. */
    val minValidFramePercent: Double = 70.0,
    /** Valid-frame share below this = low quality (the attempt is usable). */
    val goodValidFramePercent: Double = 90.0,
    /** Calibration: fewer valid frames in the window than this = calibration failed. */
    val minCalibrationFrames: Int = 15,
    /** Calibration: the seated knee angle must vary by less than this (degrees, standard deviation). */
    val maxCalibrationKneeSdDeg: Double = 6.0,
    /** Calibration: the seated knee must be bent at least this much (a knee above it is not seated). */
    val seatedKneeMaxDeg: Double = 130.0,
)
