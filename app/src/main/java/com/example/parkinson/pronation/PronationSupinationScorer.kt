package com.example.parkinson.pronation

import com.example.parkinson.assessment.QualityStatus

/**
 * ENGINEERING normalization ranges, NOT clinical cutoffs and NOT normative values: no validated
 * normative dataset exists for this app. They only map each raw metric to 0..100 transparently and
 * reproducibly, so a person can follow their own results over time ("نسخه پژوهشی / غیرتشخیصی").
 */
data class PronationScoringConfig(
    /** Cycle rate mapped to 100 (upper end of the range the detector is designed for). */
    val referenceCycleRateHz: Double = 3.0,
    /** Cycle-duration CV mapped to 0 (0 % = 100). */
    val maxCycleCvPercent: Double = 50.0,
    /** Median rotation per movement mapped to 100. */
    val referenceAmplitudeDeg: Double = 150.0,
    /** Median peak angular velocity mapped to 100. */
    val referencePeakVelocityDegS: Double = 600.0,
    val weightRate: Double = 0.25,
    val weightRhythm: Double = 0.20,
    val weightAmplitude: Double = 0.20,
    val weightVelocity: Double = 0.15,
    val weightConsistency: Double = 0.15,
    val weightDataQuality: Double = 0.05
)

/**
 * "شاخص عملکرد حرکتی" for the pronation/supination test, 0..100. Each component is 0..100;
 * higher = faster / more regular / larger / more consistent rotation. NOT a diagnosis, NOT a
 * disease probability and NOT clinically validated.
 */
data class PronationSupinationPerformanceScore(
    val total: Int,
    val rate: Int,
    /** null with fewer than 3 valid cycles (component left out, weights renormalized). */
    val rhythm: Int?,
    val amplitude: Int,
    val velocity: Int,
    /** null with fewer than 3 valid movements. */
    val consistency: Int?,
    val dataQuality: Int,
    val scoringVersion: String = PronationSupinationVersions.SCORING_VERSION,
    val isClinicallyValidated: Boolean = false
)

/** SCORING layer, kept separate from the raw metrics. Pure and deterministic. */
object PronationSupinationScorer {

    /** Returns null unless the recording quality is VALID: a poor recording gets no score. */
    fun score(
        metrics: PronationMetrics,
        quality: PronationQualityReport,
        config: PronationScoringConfig = PronationScoringConfig()
    ): PronationSupinationPerformanceScore? {
        if (quality.status != QualityStatus.VALID) return null
        val amplitudeDeg = metrics.movementAmplitudeDeg ?: return null
        val peak = metrics.angularVelocityPeakDegS ?: return null

        val rate = linear(metrics.cycleRateHz / config.referenceCycleRateHz)
        val rhythm = metrics.cycleDurationCvPercent?.let { linear(1.0 - it / config.maxCycleCvPercent) }
        val amplitude = linear(amplitudeDeg / config.referenceAmplitudeDeg)
        val velocity = linear(peak / config.referencePeakVelocityDegS)
        val consistency = metrics.movementConsistencyPercent?.let { linear(it / 100.0) }
        val dataQuality = quality.qualityScore.coerceIn(0, 100)

        val parts = mutableListOf(
            rate to config.weightRate,
            amplitude to config.weightAmplitude,
            velocity to config.weightVelocity,
            dataQuality to config.weightDataQuality
        )
        if (rhythm != null) parts += rhythm to config.weightRhythm
        if (consistency != null) parts += consistency to config.weightConsistency
        val total = parts.sumOf { it.first * it.second } / parts.sumOf { it.second }

        return PronationSupinationPerformanceScore(
            total = Math.round(total).toInt().coerceIn(0, 100),
            rate = rate,
            rhythm = rhythm,
            amplitude = amplitude,
            velocity = velocity,
            consistency = consistency,
            dataQuality = dataQuality
        )
    }

    private fun linear(ratio: Double): Int = Math.round(ratio.coerceIn(0.0, 1.0) * 100.0).toInt()
}
