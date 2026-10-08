package com.example.parkinson.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.parkinson.assessment.PerformanceTrendState
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.mediapipe.HandTrackingQuality
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.quality.QualityIssue
import com.example.parkinson.tapping.result.FingerTappingAssessment
import com.example.parkinson.tapping.result.TapPayload
import com.example.parkinson.tapping.scoring.PerformanceScore
import com.example.parkinson.tapping.scoring.TappingNote
import java.util.Locale

/**
 * Stored Finger Tapping assessment. Measurements only: no raw camera frames, no images and no
 * personal identifiers. Enums are stored by name so a reordering can never change old rows.
 *
 * DB v5 only ADDS columns (with defaults) for algorithm 1.1 / scoring 1.0, so rows of earlier
 * versions survive the automatic migration. Reused v1 columns: scoreAmplitudeTrend = the trend
 * component; scoreDataQuality is v1-only (written as null).
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
    /** Performance-trend component. */
    val scoreAmplitudeTrend: Int?,
    val scoreConsistency: Int?,
    /** v1 only. */
    val scoreDataQuality: Int?,
    val algorithmVersion: String,
    val scoringVersion: String,

    // --- added in v5 -------------------------------------------------------------------------
    @ColumnInfo(defaultValue = "INSUFFICIENT_DATA") val trendState: String = PerformanceTrendState.INSUFFICIENT_DATA.name,
    val earlyScore: Int? = null,
    val middleScore: Int? = null,
    val lateScore: Int? = null,
    /** Empty in older rows: derived from quality when read. */
    @ColumnInfo(defaultValue = "") val reliability: String = "",
    /** Comma-separated TappingNote names. */
    @ColumnInfo(defaultValue = "") val interpretationNotes: String = "",
    @ColumnInfo(defaultValue = "") val referenceName: String = "",
    @ColumnInfo(defaultValue = "") val cameraQuality: String = "",
    @ColumnInfo(defaultValue = "") val handTrackingQuality: String = "",
    val trackingRatePercent: Double? = null,
    val usableDurationMs: Long? = null,
    val framesAnalyzed: Int? = null,
    val validLandmarkFrames: Int? = null,
    val meanLuma: Double? = null,
    val cameraFramesSkipped: Int? = null,
    val pipelineFramesDropped: Int? = null,
    val meanClosingVelocity: Double? = null,
    val closingVelocityCvPercent: Double? = null,
    /** Per-tap payload, comma-separated. */
    @ColumnInfo(defaultValue = "") val tapTimesMs: String = "",
    @ColumnInfo(defaultValue = "") val tapAmplitudes: String = "",
    @ColumnInfo(defaultValue = "") val tapVelocities: String = "",
    @ColumnInfo(defaultValue = "") val tapDurationsMs: String = ""
)

private fun List<Double>.csv(): String = joinToString(",") { String.format(Locale.US, "%.4f", it) }
private fun String.doubles(): List<Double> = if (isBlank()) emptyList() else split(',').mapNotNull { it.toDoubleOrNull() }
private fun String.longs(): List<Long> = if (isBlank()) emptyList() else split(',').mapNotNull { it.toLongOrNull() }

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

fun FingerTappingAssessment.toEntity(): AssessmentEntity {
    val s = performanceScore
    return AssessmentEntity(
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
        scoreTotal = s?.total,
        scoreRate = s?.rate,
        scoreRhythm = s?.rhythm,
        scoreAmplitude = s?.amplitude,
        scoreAmplitudeTrend = s?.trend,
        scoreConsistency = s?.consistency,
        scoreDataQuality = null,
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion,
        trendState = (s?.trendState ?: PerformanceTrendState.INSUFFICIENT_DATA).name,
        earlyScore = s?.earlyScore,
        middleScore = s?.middleScore,
        lateScore = s?.lateScore,
        reliability = reliability.name,
        interpretationNotes = interpretationNotes.joinToString(",") { it.name },
        referenceName = s?.referenceName.orEmpty(),
        cameraQuality = cameraQuality?.name.orEmpty(),
        handTrackingQuality = handTrackingQuality?.name.orEmpty(),
        trackingRatePercent = trackingRatePercent,
        usableDurationMs = usableDurationMs,
        framesAnalyzed = framesAnalyzed,
        validLandmarkFrames = validLandmarkFrames,
        meanLuma = meanLuma?.toDouble(),
        cameraFramesSkipped = cameraFramesSkipped,
        pipelineFramesDropped = pipelineFramesDropped,
        meanClosingVelocity = meanClosingVelocity,
        closingVelocityCvPercent = closingVelocityCvPercent,
        tapTimesMs = payload.timesMs.joinToString(","),
        tapAmplitudes = payload.amplitudes.csv(),
        tapVelocities = payload.velocities.csv(),
        tapDurationsMs = payload.durationsMs.joinToString(",")
    )
}

fun AssessmentEntity.toDomain(): FingerTappingAssessment {
    // Unknown names (e.g. written by a newer app version) degrade to the safe side.
    val quality = enumOrNull<QualityStatus>(qualityStatus) ?: QualityStatus.INVALID
    val storedReliability = enumOrNull<ReliabilityLevel>(reliability)
    val score = if (scoreTotal != null && scoreTotal in 0..100 && scoreRate != null && scoreAmplitude != null) {
        PerformanceScore(
            total = scoreTotal,
            rate = scoreRate,
            rhythm = scoreRhythm,
            amplitude = scoreAmplitude,
            consistency = scoreConsistency,
            trend = scoreAmplitudeTrend,
            trendState = enumOrNull<PerformanceTrendState>(trendState) ?: PerformanceTrendState.INSUFFICIENT_DATA,
            earlyScore = earlyScore,
            middleScore = middleScore,
            lateScore = lateScore,
            // Older rows: only VALID recordings were scored.
            reliability = storedReliability?.takeIf { it != ReliabilityLevel.NOT_RELIABLE }
                ?: if (quality == QualityStatus.VALID) ReliabilityLevel.RELIABLE else ReliabilityLevel.LIMITED,
            referenceName = referenceName,
            scoringVersion = scoringVersion
        )
    } else null

    return FingerTappingAssessment(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        hand = enumOrNull<SelectedHand>(hand) ?: SelectedHand.RIGHT,
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
        qualityStatus = quality,
        qualityIssues = qualityIssues.split(',').mapNotNull { n -> enumOrNull<QualityIssue>(n) },
        qualityScore = qualityScore,
        performanceScore = score,
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion,
        cameraQuality = enumOrNull<CameraQuality>(cameraQuality),
        handTrackingQuality = enumOrNull<HandTrackingQuality>(handTrackingQuality),
        trackingRatePercent = trackingRatePercent,
        usableDurationMs = usableDurationMs,
        framesAnalyzed = framesAnalyzed,
        validLandmarkFrames = validLandmarkFrames,
        meanLuma = meanLuma?.toFloat(),
        cameraFramesSkipped = cameraFramesSkipped,
        pipelineFramesDropped = pipelineFramesDropped,
        meanClosingVelocity = meanClosingVelocity,
        closingVelocityCvPercent = closingVelocityCvPercent,
        reliability = score?.reliability ?: ReliabilityLevel.NOT_RELIABLE,
        interpretationNotes = interpretationNotes.split(',').mapNotNull { enumOrNull<TappingNote>(it) },
        payload = TapPayload(tapTimesMs.longs(), tapAmplitudes.doubles(), tapVelocities.doubles(), tapDurationsMs.longs())
    )
}
