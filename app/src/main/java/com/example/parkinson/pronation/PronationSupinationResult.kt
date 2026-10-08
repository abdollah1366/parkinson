package com.example.parkinson.pronation

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand

/**
 * Immutable result of one Pronation/Supination recording, as stored and shown. Derived
 * measurements only: no raw sensor stream, no personal identifier. Units: deg, deg/s, ms, Hz,
 * m/s^2. The hand is the one the user selected (sensor data cannot tell left from right).
 * Never a diagnosis.
 */
data class PronationSupinationResult(
    override val assessmentId: String,
    override val timestampEpochMs: Long,
    override val hand: SelectedHand,
    val plannedDurationMs: Long,
    /** Synchronized window covered by both sensors. */
    val durationMs: Long,

    val cycleCount: Int,
    val validCycleCount: Int,
    val cycleRateHz: Double,
    val meanCycleDurationMs: Double?,
    val medianCycleDurationMs: Double?,
    /** Cycle-time variability, CV %. */
    val cycleVariability: Double?,
    val angularVelocityMean: Double,
    val angularVelocityPeak: Double?,
    /** CV % of the per-movement peak angular velocity. */
    val angularVelocityVariability: Double?,
    val angularVelocityRms: Double,
    val movementAmplitude: Double?,
    /** CV % of the per-movement rotation angle. */
    val amplitudeVariability: Double?,
    /** % of movements within +-30 % of the typical amplitude and duration. */
    val movementConsistency: Double?,
    val pauseCount: Int,
    val pauseDurationMs: Long,
    val accelerationRms: Double,
    val dominantFrequencyHz: Double?,
    val rotationAxisSharePercent: Double,

    val trend: PerformanceTrend?,

    val effectiveSamplingRate: Double,
    val accSamplingRateHz: Double,
    val validSamplePercentage: Double,
    val dropoutCount: Int,
    val completenessPercent: Double,

    val qualityStatus: QualityStatus,
    val qualityIssues: List<PronationQualityIssue>,
    val qualityScore: Int,

    /** null unless quality was VALID. */
    val performanceScore: PronationSupinationPerformanceScore?,

    override val algorithmVersion: String,
    override val scoringVersion: String
) : AssessmentResult {
    override val type: AssessmentType get() = AssessmentType.PRONATION_SUPINATION
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, qualityScore)
    override val performanceIndex: Int? get() = performanceScore?.total

    companion object {
        /** Builds the stored result; requires metrics (i.e. a usable analysis). */
        fun from(
            analysis: PronationAnalysis,
            id: String,
            timestampEpochMs: Long,
            hand: SelectedHand,
            plannedMs: Long
        ): PronationSupinationResult {
            val m = requireNotNull(analysis.metrics) { "A result needs metrics" }
            val a = analysis.accel
            val g = analysis.gyro
            val received = a.receivedSamples + g.receivedSamples
            return PronationSupinationResult(
                assessmentId = id,
                timestampEpochMs = timestampEpochMs,
                hand = hand,
                plannedDurationMs = plannedMs,
                durationMs = Math.round(analysis.analyzedDurationMs),
                cycleCount = m.cycleCount,
                validCycleCount = m.validCycleCount,
                cycleRateHz = m.cycleRateHz,
                meanCycleDurationMs = m.meanCycleDurationMs,
                medianCycleDurationMs = m.medianCycleDurationMs,
                cycleVariability = m.cycleDurationCvPercent,
                angularVelocityMean = m.angularVelocityMeanDegS,
                angularVelocityPeak = m.angularVelocityPeakDegS,
                angularVelocityVariability = m.angularVelocityCvPercent,
                angularVelocityRms = m.angularVelocityRmsDegS,
                movementAmplitude = m.movementAmplitudeDeg,
                amplitudeVariability = m.amplitudeCvPercent,
                movementConsistency = m.movementConsistencyPercent,
                pauseCount = m.pauseCount,
                pauseDurationMs = Math.round(m.pauseDurationMs),
                accelerationRms = m.accelerationRms,
                dominantFrequencyHz = m.dominantFrequencyHz,
                rotationAxisSharePercent = m.rotationAxisSharePercent,
                trend = analysis.trend,
                effectiveSamplingRate = g.samplingRateHz,
                accSamplingRateHz = a.samplingRateHz,
                validSamplePercentage = if (received == 0) 0.0 else (a.validSamples + g.validSamples) * 100.0 / received,
                dropoutCount = a.dropoutCount + g.dropoutCount,
                completenessPercent = minOf(a.completenessPercent, g.completenessPercent),
                qualityStatus = analysis.quality.status,
                qualityIssues = analysis.quality.issues,
                qualityScore = analysis.quality.qualityScore,
                performanceScore = analysis.score,
                algorithmVersion = PronationSupinationVersions.ALGORITHM_VERSION,
                scoringVersion = PronationSupinationVersions.SCORING_VERSION
            )
        }
    }
}
