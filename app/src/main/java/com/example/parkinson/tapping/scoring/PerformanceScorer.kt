package com.example.parkinson.tapping.scoring

import com.example.parkinson.tapping.FingerTappingVersions
import com.example.parkinson.tapping.metrics.MotorMetrics
import com.example.parkinson.tapping.quality.QualityReport
import com.example.parkinson.tapping.quality.QualityStatus

/**
 * PRELIMINARY ENGINEERING reference values. They are NOT clinical cutoffs and have NOT been
 * validated against normative or patient data. They only map each measurement to 0..100 in a
 * transparent, reproducible way so a person can be compared with their own earlier tests.
 */
data class ScoringConfig(
    /** Rate mapped to 100. */
    val referenceRateHz: Double = 5.0,
    /** Interval CV mapped to 0 (0 % CV = 100). */
    val maxIntervalCvPercent: Double = 50.0,
    /** Mean amplitude (palm sizes) mapped to 100. */
    val referenceAmplitude: Double = 1.0,
    /** Amplitude decrease (late vs early) mapped to 0; no decrease or an increase = 100. */
    val maxAmplitudeDecreasePercent: Double = 50.0,
    val weightRate: Double = 0.30,
    val weightRhythm: Double = 0.20,
    val weightAmplitude: Double = 0.20,
    val weightAmplitudeTrend: Double = 0.15,
    val weightConsistency: Double = 0.10,
    val weightDataQuality: Double = 0.05
)

/** Each component is 0..100; higher = faster / more regular / larger / steadier movement. */
data class PerformanceScore(
    /** Finger Tapping Performance Score, 0..100. NOT a diagnosis or a disease probability. */
    val total: Int,
    val rate: Int,
    val rhythm: Int,
    val amplitude: Int,
    /** null when the trend could not be measured (too few taps in the early or late third). */
    val amplitudeTrend: Int?,
    val consistency: Int,
    val dataQuality: Int,
    val scoringVersion: String = FingerTappingVersions.SCORING_VERSION,
    val isClinicallyValidated: Boolean = false
)

/** SCORING layer. Pure and deterministic: identical inputs always give identical scores. */
object PerformanceScorer {

    /** Returns null unless the recording quality is VALID: a poor recording gets no score. */
    fun score(metrics: MotorMetrics, quality: QualityReport, config: ScoringConfig = ScoringConfig()): PerformanceScore? {
        if (quality.status != QualityStatus.VALID) return null
        val intervalCv = metrics.intervalCvPercent ?: return null
        val amplitude = metrics.meanAmplitude ?: return null
        val consistency = metrics.movementConsistencyPercent ?: return null

        val rate = linear(metrics.tapRateHz / config.referenceRateHz)
        val rhythm = linear(1.0 - intervalCv / config.maxIntervalCvPercent)
        val amp = linear(amplitude / config.referenceAmplitude)
        val trend = metrics.amplitudeTrend.relativeChangePercent?.let { change ->
            linear(1.0 + minOf(change, 0.0) / config.maxAmplitudeDecreasePercent)
        }
        val cons = linear(consistency / 100.0)
        val dataQuality = quality.qualityScore.coerceIn(0, 100)

        val parts = mutableListOf(
            rate to config.weightRate,
            rhythm to config.weightRhythm,
            amp to config.weightAmplitude,
            cons to config.weightConsistency,
            dataQuality to config.weightDataQuality
        )
        if (trend != null) parts += trend to config.weightAmplitudeTrend
        // Missing components are left out and the remaining weights renormalized.
        val weightSum = parts.sumOf { it.second }
        val total = parts.sumOf { it.first * it.second } / weightSum

        return PerformanceScore(
            total = Math.round(total).toInt().coerceIn(0, 100),
            rate = rate,
            rhythm = rhythm,
            amplitude = amp,
            amplitudeTrend = trend,
            consistency = cons,
            dataQuality = dataQuality
        )
    }

    private fun linear(ratio: Double): Int = Math.round(ratio.coerceIn(0.0, 1.0) * 100.0).toInt()
}
