package com.example.parkinson.imu

import kotlin.math.roundToInt

/**
 * Reference values from published studies of real walkers, used to place each walking measure on a 0-100 scale.
 * They come from other sensors and protocols (foot switches, a lumbar sensor), not from a phone in the pocket, so the
 * comparison is PRELIMINARY. See docs/gait-scoring.md for the sources and the reasoning behind each bound.
 */
object GaitReference {
    /**
     * Cadence (steps/min) at a comfortable pace. Healthy adults usually walk at 100-120 steps/min
     * (Tudor-Locke et al., IJBNPA 2019, 16:8). Bounds: full score at or above 100, zero at or below 70.
     */
    const val CADENCE_HEALTHY = 100.0
    const val CADENCE_ZERO = 70.0

    /**
     * Stride time variability (CV, %). A meta-analysis of 21 studies (519 people with PD, 574 controls) found
     * 2.4 % (95 % CI 1.9-3.9 %) separates the groups (König et al., Front Hum Neurosci 2016, 10:319).
     * Full score at or below 2.4 %, a sign above 3.9 % (the upper confidence bound), zero at or above 8 %.
     */
    const val STRIDE_CV_HEALTHY = 2.4
    const val STRIDE_CV_SIGN = 3.9
    const val STRIDE_CV_ZERO = 8.0

    /**
     * Step timing asymmetry (%). Healthy older adults walk with left/right swing-time asymmetry of a few percent;
     * it is higher in PD and in elderly fallers (Yogev et al., Exp Brain Res 2007, 177:336). An antalgic (limping)
     * gait shortens one step. Full score at or below 5 %, a sign above 10 %, zero at or above 20 % (engineering bounds).
     */
    const val ASYMMETRY_HEALTHY = 5.0
    const val ASYMMETRY_SIGN = 10.0
    const val ASYMMETRY_ZERO = 20.0

    /**
     * Mean turning velocity (deg/s) of turns of at least 90 degrees at normal speed: controls 78.0 (CV 0.15),
     * PD 69.7 (CV 0.18) (El-Gohary et al., Sensors 2014, 14:356). Full score at or above 78, a sign below 66
     * (one SD under the control mean), zero at or below 40.
     */
    const val TURN_VELOCITY_HEALTHY = 78.0
    const val TURN_VELOCITY_SIGN = 66.0
    const val TURN_VELOCITY_ZERO = 40.0

    /** Tremor band peak prominence (power over the median 1-20 Hz power). EXPERIMENTAL, not part of the score. */
    const val TREMOR_PROMINENCE_SIGN = 8.0
}

enum class GaitDomain { RHYTHM, VARIABILITY, SYMMETRY, TURNING }

/** Signs reported to the user. None of them is a diagnosis. */
enum class GaitSign {
    LOW_CADENCE,
    HIGH_VARIABILITY,
    ASYMMETRY,
    SLOW_TURNING,
    WALKING_TREMOR,
    STANDING_TREMOR,

    /** Several signs that are reported more often in people with Parkinson's disease appear together. */
    PARKINSONIAN_PATTERN,
}

data class GaitDomainScore(val domain: GaitDomain, val value: Double, val score: Int)

/** The preliminary overall walking score (0-100) and the signs. [overall] is null with fewer than two domains. */
data class GaitScore(
    val overall: Int?,
    val domains: List<GaitDomainScore>,
    val signs: List<GaitSign>,
)

object GaitScoring {
    const val VERSION = "gait-score-0.1-preliminary"

    /** Linear between [healthy] (100) and [zero] (0); works for both directions. */
    fun linear(value: Double, healthy: Double, zero: Double): Int {
        val t = (value - zero) / (healthy - zero)
        return (100.0 * t.coerceIn(0.0, 1.0)).roundToInt()
    }

    fun score(
        cadence: Double?,
        strideCvPercent: Double?,
        stepAsymmetryPercent: Double?,
        meanTurnVelocityDegPerSec: Double?,
        walkingTremorIndex: Double?,
        standingTremorIndex: Double?,
    ): GaitScore {
        val r = GaitReference
        val domains = buildList {
            cadence?.let { add(GaitDomainScore(GaitDomain.RHYTHM, it, linear(it, r.CADENCE_HEALTHY, r.CADENCE_ZERO))) }
            strideCvPercent?.let { add(GaitDomainScore(GaitDomain.VARIABILITY, it, linear(it, r.STRIDE_CV_HEALTHY, r.STRIDE_CV_ZERO))) }
            stepAsymmetryPercent?.let { add(GaitDomainScore(GaitDomain.SYMMETRY, it, linear(it, r.ASYMMETRY_HEALTHY, r.ASYMMETRY_ZERO))) }
            meanTurnVelocityDegPerSec?.let {
                add(GaitDomainScore(GaitDomain.TURNING, it, linear(it, r.TURN_VELOCITY_HEALTHY, r.TURN_VELOCITY_ZERO)))
            }
        }
        val signs = buildList {
            if (cadence != null && cadence < r.CADENCE_HEALTHY) add(GaitSign.LOW_CADENCE)
            if (strideCvPercent != null && strideCvPercent > r.STRIDE_CV_SIGN) add(GaitSign.HIGH_VARIABILITY)
            if (stepAsymmetryPercent != null && stepAsymmetryPercent > r.ASYMMETRY_SIGN) add(GaitSign.ASYMMETRY)
            if (meanTurnVelocityDegPerSec != null && meanTurnVelocityDegPerSec < r.TURN_VELOCITY_SIGN) add(GaitSign.SLOW_TURNING)
            if (walkingTremorIndex != null && walkingTremorIndex >= r.TREMOR_PROMINENCE_SIGN) add(GaitSign.WALKING_TREMOR)
            if (standingTremorIndex != null && standingTremorIndex >= r.TREMOR_PROMINENCE_SIGN) add(GaitSign.STANDING_TREMOR)
        }
        val pdLike = listOf(GaitSign.LOW_CADENCE, GaitSign.HIGH_VARIABILITY, GaitSign.SLOW_TURNING, GaitSign.WALKING_TREMOR, GaitSign.STANDING_TREMOR)
            .count { it in signs }
        val withPattern = if (GaitSign.HIGH_VARIABILITY in signs && pdLike >= 2 || pdLike >= 3) signs + GaitSign.PARKINSONIAN_PATTERN else signs
        val overall = domains.takeIf { it.size >= 2 }?.map { it.score }?.average()?.roundToInt()
        return GaitScore(overall, domains, withPattern)
    }
}
