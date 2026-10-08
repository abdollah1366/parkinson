package com.example.parkinson.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.quality.QualityIssue
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.tapping.result.FingerTappingAssessment
import com.example.parkinson.tapping.scoring.PerformanceScore

/**
 * Stored Finger Tapping assessment. Measurements only: no raw camera frames, no images and no
 * personal identifiers. Enums are stored by name so a reordering can never change old rows.
 */
@Entity(tableName = "finger_tapping_assessments", indices = [Index("timestampEpochMs")])
data class AssessmentEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val hand: String,
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
    val qualityStatus: String,
    /** Comma-separated QualityIssue names. */
    val qualityIssues: String,
    val qualityScore: Int,
    val scoreTotal: Int?,
    val scoreRate: Int?,
    val scoreRhythm: Int?,
    val scoreAmplitude: Int?,
    val scoreAmplitudeTrend: Int?,
    val scoreConsistency: Int?,
    val scoreDataQuality: Int?,
    val algorithmVersion: String,
    val scoringVersion: String
)

fun FingerTappingAssessment.toEntity(): AssessmentEntity = AssessmentEntity(
    assessmentId = assessmentId,
    timestampEpochMs = timestampEpochMs,
    hand = hand.name,
    plannedDurationMs = plannedDurationMs,
    recordingDurationMs = recordingDurationMs,
    tapCount = tapCount,
    tapRateHz = tapRateHz,
    tapsPer10Seconds = tapsPer10Seconds,
    meanIntervalMs = meanIntervalMs,
    medianIntervalMs = medianIntervalMs,
    intervalSdMs = intervalSdMs,
    intervalCvPercent = intervalCvPercent,
    tapToTapVariabilityPercent = tapToTapVariabilityPercent,
    pauseCount = pauseCount,
    meanAmplitude = meanAmplitude,
    medianAmplitude = medianAmplitude,
    amplitudeSd = amplitudeSd,
    amplitudeCvPercent = amplitudeCvPercent,
    minAmplitude = minAmplitude,
    maxAmplitude = maxAmplitude,
    meanTapDurationMs = meanTapDurationMs,
    amplitudeEarly = amplitudeEarly,
    amplitudeMiddle = amplitudeMiddle,
    amplitudeLate = amplitudeLate,
    amplitudeTrendPercent = amplitudeTrendPercent,
    rateEarlyHz = rateEarlyHz,
    rateLateHz = rateLateHz,
    rateTrendPercent = rateTrendPercent,
    movementConsistencyPercent = movementConsistencyPercent,
    fps = fps,
    validFramePercent = validFramePercent,
    dropoutCount = dropoutCount,
    dropoutDurationMs = dropoutDurationMs,
    longestDropoutMs = longestDropoutMs,
    recordingCompletenessPercent = recordingCompletenessPercent,
    qualityStatus = qualityStatus.name,
    qualityIssues = qualityIssues.joinToString(",") { it.name },
    qualityScore = qualityScore,
    scoreTotal = performanceScore?.total,
    scoreRate = performanceScore?.rate,
    scoreRhythm = performanceScore?.rhythm,
    scoreAmplitude = performanceScore?.amplitude,
    scoreAmplitudeTrend = performanceScore?.amplitudeTrend,
    scoreConsistency = performanceScore?.consistency,
    scoreDataQuality = performanceScore?.dataQuality,
    algorithmVersion = algorithmVersion,
    scoringVersion = scoringVersion
)

fun AssessmentEntity.toDomain(): FingerTappingAssessment {
    val score = if (
        scoreTotal != null && scoreRate != null && scoreRhythm != null && scoreAmplitude != null &&
        scoreConsistency != null && scoreDataQuality != null
    ) {
        PerformanceScore(
            total = scoreTotal,
            rate = scoreRate,
            rhythm = scoreRhythm,
            amplitude = scoreAmplitude,
            amplitudeTrend = scoreAmplitudeTrend,
            consistency = scoreConsistency,
            dataQuality = scoreDataQuality,
            scoringVersion = scoringVersion
        )
    } else null

    return FingerTappingAssessment(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        hand = SelectedHand.entries.firstOrNull { it.name == hand } ?: SelectedHand.RIGHT,
        plannedDurationMs = plannedDurationMs,
        recordingDurationMs = recordingDurationMs,
        tapCount = tapCount,
        tapRateHz = tapRateHz,
        tapsPer10Seconds = tapsPer10Seconds,
        meanIntervalMs = meanIntervalMs,
        medianIntervalMs = medianIntervalMs,
        intervalSdMs = intervalSdMs,
        intervalCvPercent = intervalCvPercent,
        tapToTapVariabilityPercent = tapToTapVariabilityPercent,
        pauseCount = pauseCount,
        meanAmplitude = meanAmplitude,
        medianAmplitude = medianAmplitude,
        amplitudeSd = amplitudeSd,
        amplitudeCvPercent = amplitudeCvPercent,
        minAmplitude = minAmplitude,
        maxAmplitude = maxAmplitude,
        meanTapDurationMs = meanTapDurationMs,
        amplitudeEarly = amplitudeEarly,
        amplitudeMiddle = amplitudeMiddle,
        amplitudeLate = amplitudeLate,
        amplitudeTrendPercent = amplitudeTrendPercent,
        rateEarlyHz = rateEarlyHz,
        rateLateHz = rateLateHz,
        rateTrendPercent = rateTrendPercent,
        movementConsistencyPercent = movementConsistencyPercent,
        fps = fps,
        validFramePercent = validFramePercent,
        dropoutCount = dropoutCount,
        dropoutDurationMs = dropoutDurationMs,
        longestDropoutMs = longestDropoutMs,
        recordingCompletenessPercent = recordingCompletenessPercent,
        // Unknown names (e.g. written by a newer app version) degrade to the safe side.
        qualityStatus = QualityStatus.entries.firstOrNull { it.name == qualityStatus } ?: QualityStatus.INVALID,
        qualityIssues = qualityIssues.split(',').mapNotNull { n -> QualityIssue.entries.firstOrNull { it.name == n } },
        qualityScore = qualityScore,
        performanceScore = score,
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion
    )
}
