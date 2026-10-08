package com.example.parkinson.tapping.result

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.quality.QualityIssue
import com.example.parkinson.tapping.scoring.PerformanceScore

/**
 * Immutable result of one Finger Tapping assessment, as stored and shown.
 * Contains measurements only; no name, birth date or other personal identifier.
 * Amplitudes are in palm sizes (thumb-index distance / palm size). Null = not measurable.
 */
data class FingerTappingAssessment(
    override val assessmentId: String,
    override val timestampEpochMs: Long,
    override val hand: SelectedHand,
    val plannedDurationMs: Long,
    val recordingDurationMs: Long,

    val tapCount: Int,
    val tapRateHz: Double,
    val tapsPer10Seconds: Double,
    val meanIntervalMs: Double?,
    val medianIntervalMs: Double?,
    val intervalSdMs: Double?,
    val intervalCvPercent: Double?,
    val tapToTapVariabilityPercent: Double?,
    val pauseCount: Int,

    val meanAmplitude: Double?,
    val medianAmplitude: Double?,
    val amplitudeSd: Double?,
    val amplitudeCvPercent: Double?,
    val minAmplitude: Double?,
    val maxAmplitude: Double?,
    val meanTapDurationMs: Double?,

    val amplitudeEarly: Double?,
    val amplitudeMiddle: Double?,
    val amplitudeLate: Double?,
    /** Measured amplitude trend, late vs early third, in %. Not a clinical finding. */
    val amplitudeTrendPercent: Double?,
    val rateEarlyHz: Double?,
    val rateLateHz: Double?,
    val rateTrendPercent: Double?,
    val movementConsistencyPercent: Double?,

    val fps: Double,
    val validFramePercent: Double,
    val dropoutCount: Int,
    val dropoutDurationMs: Long,
    val longestDropoutMs: Long,
    val recordingCompletenessPercent: Double,

    val qualityStatus: QualityStatus,
    val qualityIssues: List<QualityIssue>,
    val qualityScore: Int,

    /** null when the quality was not VALID: no score is given for an unreliable recording. */
    val performanceScore: PerformanceScore?,

    override val algorithmVersion: String,
    override val scoringVersion: String
) : AssessmentResult {
    override val type: AssessmentType get() = AssessmentType.FINGER_TAPPING
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, qualityScore)
    override val performanceIndex: Int? get() = performanceScore?.total
}
