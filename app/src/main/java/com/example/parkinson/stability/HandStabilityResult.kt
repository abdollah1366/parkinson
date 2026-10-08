package com.example.parkinson.stability

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand

/**
 * Immutable result of one Hand Stability recording, as stored and shown. Measurements only: no
 * raw sensor stream and no personal identifier. Units: m/s^2, deg/s, degrees, Hz, ms.
 * Never a diagnosis: the index describes how still the phone was held.
 */
data class HandStabilityResult(
    override val assessmentId: String,
    override val timestampEpochMs: Long,
    override val hand: SelectedHand,
    val plannedDurationMs: Long,
    /** Synchronized window covered by both sensors. */
    val recordingDurationMs: Long,

    val accSampleCount: Int,
    val accSamplingRateHz: Double,
    val accMedianIntervalMs: Double,
    val gyroSampleCount: Int,
    val gyroSamplingRateHz: Double,
    val gyroMedianIntervalMs: Double,
    val validSamplePercent: Double,
    val dropoutCount: Int,
    val dropoutDurationMs: Long,
    val longestGapMs: Long,
    val completenessPercent: Double,

    val accMagnitudeMean: Double,
    val accMagnitudeSd: Double,
    val accMagnitudeVariance: Double,
    val accMagnitudeRange: Double,
    val accDynamicRms: Double,
    val accDynamicVariance: Double,
    val gyroMagnitudeMean: Double,
    val gyroMagnitudeRms: Double,
    val gyroMagnitudeSd: Double,
    val gyroMagnitudeVariance: Double,
    val gyroMagnitudeMax: Double,
    val gyroDynamicRms: Double,
    val rotationRangeDeg: Double,
    val tiltChangeDeg: Double,
    val dominantFrequencyHz: Double?,
    val oscillationBandPowerPercent: Double?,
    val frequencyStatus: FrequencyStatus,

    val qualityStatus: QualityStatus,
    val qualityIssues: List<StabilityQualityIssue>,
    val qualityScore: Int,

    /** null when quality was not VALID. */
    val stabilityIndex: StabilityIndex?,

    override val algorithmVersion: String,
    override val scoringVersion: String
) : AssessmentResult {
    override val type: AssessmentType get() = AssessmentType.HAND_STABILITY
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, qualityScore)
    override val performanceIndex: Int? get() = stabilityIndex?.total

    companion object {
        /** Builds the stored result; requires metrics (i.e. a usable analysis). */
        fun from(analysis: StabilityAnalysis, id: String, timestampEpochMs: Long, hand: SelectedHand, plannedMs: Long): HandStabilityResult {
            val m = requireNotNull(analysis.metrics) { "A result needs metrics" }
            val a = analysis.accel
            val g = analysis.gyro
            val received = a.receivedSamples + g.receivedSamples
            return HandStabilityResult(
                assessmentId = id,
                timestampEpochMs = timestampEpochMs,
                hand = hand,
                plannedDurationMs = plannedMs,
                recordingDurationMs = Math.round(analysis.analyzedDurationMs),
                accSampleCount = a.validSamples,
                accSamplingRateHz = a.samplingRateHz,
                accMedianIntervalMs = a.medianIntervalMs,
                gyroSampleCount = g.validSamples,
                gyroSamplingRateHz = g.samplingRateHz,
                gyroMedianIntervalMs = g.medianIntervalMs,
                validSamplePercent = if (received == 0) 0.0 else (a.validSamples + g.validSamples) * 100.0 / received,
                dropoutCount = a.dropoutCount + g.dropoutCount,
                dropoutDurationMs = Math.round(a.dropoutTotalMs + g.dropoutTotalMs),
                longestGapMs = Math.round(maxOf(a.longestGapMs, g.longestGapMs)),
                completenessPercent = minOf(a.completenessPercent, g.completenessPercent),
                accMagnitudeMean = m.accMagnitudeMean,
                accMagnitudeSd = m.accMagnitudeSd,
                accMagnitudeVariance = m.accMagnitudeVariance,
                accMagnitudeRange = m.accMagnitudeRange,
                accDynamicRms = m.accDynamicRms,
                accDynamicVariance = m.accDynamicVariance,
                gyroMagnitudeMean = m.gyroMagnitudeMean,
                gyroMagnitudeRms = m.gyroMagnitudeRms,
                gyroMagnitudeSd = m.gyroMagnitudeSd,
                gyroMagnitudeVariance = m.gyroMagnitudeVariance,
                gyroMagnitudeMax = m.gyroMagnitudeMax,
                gyroDynamicRms = m.gyroDynamicRms,
                rotationRangeDeg = m.rotationRangeDeg,
                tiltChangeDeg = m.tiltChangeDeg,
                dominantFrequencyHz = m.dominantFrequencyHz,
                oscillationBandPowerPercent = m.oscillationBandPowerPercent,
                frequencyStatus = m.frequencyStatus,
                qualityStatus = analysis.quality.status,
                qualityIssues = analysis.quality.issues,
                qualityScore = analysis.quality.qualityScore,
                stabilityIndex = analysis.index,
                algorithmVersion = HandStabilityVersions.ALGORITHM_VERSION,
                scoringVersion = HandStabilityVersions.SCORING_VERSION
            )
        }
    }
}
