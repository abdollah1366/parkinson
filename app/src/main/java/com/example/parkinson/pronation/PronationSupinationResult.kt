package com.example.parkinson.pronation

import com.example.parkinson.assessment.PerformanceTrendState
import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.assessment.MotorPerformanceBand
import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import kotlin.math.roundToInt

/**
 * Immutable stored result of one Pronation/Supination recording: derived measurements only, no
 * raw sensor stream, no personal identifier. Units: deg, deg/s, ms, Hz. The hand is the user's
 * explicit selection (sensor data cannot tell left from right). Never a diagnosis.
 *
 * Everything needed for later comparisons (left vs right, previous vs current, score / speed /
 * amplitude / regularity trends) is stored per result.
 */
data class PronationSupinationResult(
    override val assessmentId: String,
    /** One run of the session (a retry gets a new session id). */
    val sessionId: String,
    override val timestampEpochMs: Long,
    override val hand: SelectedHand,
    val plannedDurationMs: Long,
    /** Synchronized analysis window. */
    val durationMs: Long,

    val cycleCount: Int,
    val validCycleCount: Int,
    val cyclesPerSecond: Double,
    val cyclesPerMinute: Double,
    val meanCycleDurationMs: Double?,
    val medianCycleDurationMs: Double?,
    /** CV % of cycle durations. */
    val cycleDurationVariability: Double?,
    val meanAmplitude: Double?,
    val medianAmplitude: Double?,
    /** CV % of movement amplitudes. */
    val amplitudeVariability: Double?,
    val meanAngularVelocity: Double,
    /** Typical (median) per-movement peak. */
    val peakAngularVelocity: Double?,
    val maxAngularVelocity: Double?,
    /** CV % of per-movement peaks. */
    val velocityVariability: Double?,
    val angularVelocityRms: Double,
    val pauseCount: Int,
    val totalPauseDurationMs: Long,
    val longestPauseMs: Long,
    val movementCoveragePercent: Double,
    val noiseLevelDegS: Double,
    val accelerationRms: Double?,
    val dominantFrequencyHz: Double?,
    val rotationAxisSharePercent: Double,

    /** Component scores 0..100 (null = not measurable or no score). regularity = rhythm. */
    val speedScore: Int?,
    val regularityScore: Int?,
    val amplitudeScore: Int?,
    val consistencyScore: Int?,
    val trendScore: Int?,
    val earlyScore: Int?,
    val middleScore: Int?,
    val lateScore: Int?,
    /** "شاخص عملکرد حرکتی" 0..100; null when quality did not allow a score. */
    val performanceScore: Int?,
    val performanceTrend: PerformanceTrendState,
    val measureTrends: MeasureTrends?,

    val qualityStatus: QualityStatus,
    val qualityIssues: List<PronationQualityIssue>,
    val qualityPercentage: Int,
    val validSamplePercentage: Double,
    /** Measured gyroscope rate (primary sensor). */
    val samplingRate: Double,
    val accSamplingRate: Double?,
    val accelerometerAvailable: Boolean,
    val dropoutCount: Int,
    val estimatedMissingSamples: Int,
    val longestGapMs: Long,
    val completenessPercent: Double,

    val interpretationBand: MotorPerformanceBand?,
    val reliability: ReliabilityLevel,
    val interpretationNotes: List<InterpretationNote>,

    /** Filtered angular velocity about the main axis (deg/s) at [traceHz], for the chart. */
    val velocityTrace: List<Float>,
    val traceHz: Double,

    val referenceName: String,
    override val algorithmVersion: String,
    override val scoringVersion: String
) : AssessmentResult {
    override val type: AssessmentType get() = AssessmentType.PRONATION_SUPINATION
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, qualityPercentage)
    override val performanceIndex: Int? get() = performanceScore

    /** Same as [qualityPercentage] (the shared "quality score" of every assessment). */
    val qualityScore: Int get() = qualityPercentage

    companion object {
        /** Builds the stored result; requires metrics (i.e. a usable analysis). */
        fun from(
            analysis: PronationAnalysis,
            id: String,
            sessionId: String,
            timestampEpochMs: Long,
            hand: SelectedHand,
            plannedMs: Long,
            traceHz: Double = PronationDetectionConfig().traceHz
        ): PronationSupinationResult {
            val m = requireNotNull(analysis.metrics) { "A result needs metrics" }
            val a = analysis.accel
            val g = analysis.gyro
            val s = analysis.score
            val received = g.receivedSamples + if (analysis.accelerometerAvailable) a.receivedSamples else 0
            val valid = g.validSamples + if (analysis.accelerometerAvailable) a.validSamples else 0
            return PronationSupinationResult(
                assessmentId = id,
                sessionId = sessionId,
                timestampEpochMs = timestampEpochMs,
                hand = hand,
                plannedDurationMs = plannedMs,
                durationMs = analysis.analyzedDurationMs.roundToInt().toLong(),
                cycleCount = m.cycleCount,
                validCycleCount = m.validCycleCount,
                cyclesPerSecond = m.cyclesPerSecond,
                cyclesPerMinute = m.cyclesPerMinute,
                meanCycleDurationMs = m.meanCycleDurationMs,
                medianCycleDurationMs = m.medianCycleDurationMs,
                cycleDurationVariability = m.cycleDurationCvPercent,
                meanAmplitude = m.meanAmplitudeDeg,
                medianAmplitude = m.medianAmplitudeDeg,
                amplitudeVariability = m.amplitudeCvPercent,
                meanAngularVelocity = m.meanAngularVelocityDegS,
                peakAngularVelocity = m.peakAngularVelocityDegS,
                maxAngularVelocity = m.maxAngularVelocityDegS,
                velocityVariability = m.velocityCvPercent,
                angularVelocityRms = m.angularVelocityRmsDegS,
                pauseCount = m.pauseCount,
                totalPauseDurationMs = m.totalPauseMs.roundToInt().toLong(),
                longestPauseMs = m.longestPauseMs.roundToInt().toLong(),
                movementCoveragePercent = m.movementCoveragePercent,
                noiseLevelDegS = m.noiseLevelDegS,
                accelerationRms = m.accelerationRms,
                dominantFrequencyHz = m.dominantFrequencyHz,
                rotationAxisSharePercent = m.rotationAxisSharePercent,
                speedScore = s?.components?.speed,
                regularityScore = s?.components?.rhythm,
                amplitudeScore = s?.components?.amplitude,
                consistencyScore = s?.components?.consistency,
                trendScore = s?.components?.trend,
                earlyScore = s?.trend?.earlyScore,
                middleScore = s?.trend?.middleScore,
                lateScore = s?.trend?.lateScore,
                performanceScore = s?.total,
                performanceTrend = s?.trend?.state ?: PerformanceTrendState.INSUFFICIENT_DATA,
                measureTrends = m.measureTrends,
                qualityStatus = analysis.quality.status,
                qualityIssues = analysis.quality.issues,
                qualityPercentage = analysis.quality.qualityPercentage,
                validSamplePercentage = if (received == 0) 0.0 else valid * 100.0 / received,
                samplingRate = g.samplingRateHz,
                accSamplingRate = a.samplingRateHz.takeIf { analysis.accelerometerAvailable },
                accelerometerAvailable = analysis.accelerometerAvailable,
                dropoutCount = g.dropoutCount + if (analysis.accelerometerAvailable) a.dropoutCount else 0,
                estimatedMissingSamples = if (g.medianIntervalMs > 0) (g.dropoutTotalMs / g.medianIntervalMs).roundToInt() else 0,
                longestGapMs = g.longestGapMs.roundToInt().toLong(),
                completenessPercent = if (analysis.accelerometerAvailable) minOf(a.completenessPercent, g.completenessPercent) else g.completenessPercent,
                interpretationBand = analysis.interpretation.band,
                reliability = analysis.interpretation.reliability,
                interpretationNotes = analysis.interpretation.notes,
                velocityTrace = analysis.velocityTrace,
                traceHz = traceHz,
                referenceName = s?.referenceName ?: InternalSoftwareReference.name,
                algorithmVersion = PronationSupinationVersions.ALGORITHM_VERSION,
                scoringVersion = PronationSupinationVersions.SCORING_VERSION
            )
        }
    }
}
