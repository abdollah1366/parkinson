package com.example.parkinson.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tremor.RestingTremorMetrics
import com.example.parkinson.tremor.RestingTremorQualityIssue
import com.example.parkinson.tremor.RestingTremorResult
import com.example.parkinson.tremor.RestingTremorSeries
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * Stored Resting Hand Tremor result: the metrics, the data quality, and the timestamped palm series they
 * were computed from, so every metric can be recalculated later. No camera images and no personal
 * identifiers. Enums are stored by name; nullable metrics are NULL when they could not be calculated.
 */
@Entity(tableName = "resting_tremor_assessments", indices = [Index("timestampEpochMs")])
data class RestingTremorEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val hand: String,
    val plannedDurationMs: Long,

    // --- metrics
    val actualDurationMs: Long,
    val totalFrames: Int,
    val validFrames: Int,
    val noHandFrames: Int,
    val outOfFrameFrames: Int,
    val multipleHandsFrames: Int,
    val wrongHandFrames: Int,
    val errorFrames: Int,
    val validFramePercent: Double,
    val interruptionCount: Int,
    val interruptionTotalMs: Long,
    val longestInterruptionMs: Long,
    val medianFrameIntervalMs: Double,
    val frameRateHz: Double,
    val irregularIntervalPercent: Double,
    val medianHandScalePx: Double,
    val signalCoveragePercent: Double,
    val slowDriftPercent: Double,
    val amplitudeRmsPercent: Double?,
    val dominantFrequencyHz: Double?,
    val spectralProminence: Double?,
    val periodicityPercent: Double?,

    // --- quality
    val qualityStatus: String,
    /** Comma-separated RestingTremorQualityIssue names. */
    val qualityIssues: String,
    val qualityScore: Int,

    // --- timestamped palm series (comma-separated, one entry per frame)
    val frameTimesMs: String,
    /** One letter per frame (RestingTremorSeries.statusCode). */
    val frameStatuses: String,
    val palmXPx: String,
    val palmYPx: String,
    val handScalePx: String,

    val algorithmVersion: String,
    val configSummary: String,
    val scoringVersion: String,
)

@Dao
interface RestingTremorDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RestingTremorEntity)

    @Query("SELECT * FROM resting_tremor_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<RestingTremorEntity>>

    @Query("SELECT * FROM resting_tremor_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<RestingTremorEntity?>
}

private fun List<Double>.tremorCsv(): String = joinToString(",") { String.format(Locale.US, "%.3f", it) }
private fun String.tremorDoubles(): List<Double> = if (isBlank()) emptyList() else split(',').map { it.toDouble() }
private fun String.tremorLongs(): List<Long> = if (isBlank()) emptyList() else split(',').map { it.toLong() }

private inline fun <reified E : Enum<E>> tremorEnumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

fun RestingTremorResult.toEntity(): RestingTremorEntity {
    val m = metrics
    val s = series
    return RestingTremorEntity(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        hand = hand.name,
        plannedDurationMs = plannedDurationMs,
        actualDurationMs = m.actualDurationMs,
        totalFrames = m.totalFrames,
        validFrames = m.validFrames,
        noHandFrames = m.noHandFrames,
        outOfFrameFrames = m.outOfFrameFrames,
        multipleHandsFrames = m.multipleHandsFrames,
        wrongHandFrames = m.wrongHandFrames,
        errorFrames = m.errorFrames,
        validFramePercent = m.validFramePercent,
        interruptionCount = m.interruptionCount,
        interruptionTotalMs = m.interruptionTotalMs,
        longestInterruptionMs = m.longestInterruptionMs,
        medianFrameIntervalMs = m.medianFrameIntervalMs,
        frameRateHz = m.frameRateHz,
        irregularIntervalPercent = m.irregularIntervalPercent,
        medianHandScalePx = m.medianHandScalePx,
        signalCoveragePercent = m.signalCoveragePercent,
        slowDriftPercent = m.slowDriftPercent,
        amplitudeRmsPercent = m.amplitudeRmsPercent,
        dominantFrequencyHz = m.dominantFrequencyHz,
        spectralProminence = m.spectralProminence,
        periodicityPercent = m.periodicityPercent,
        qualityStatus = qualityStatus.name,
        qualityIssues = qualityIssues.joinToString(",") { it.name },
        qualityScore = qualityScore,
        frameTimesMs = s.frameTimesMs.joinToString(","),
        frameStatuses = s.frameStatuses,
        palmXPx = s.palmXPx.tremorCsv(),
        palmYPx = s.palmYPx.tremorCsv(),
        handScalePx = s.handScalePx.tremorCsv(),
        algorithmVersion = algorithmVersion,
        configSummary = configSummary,
        scoringVersion = scoringVersion,
    )
}

fun RestingTremorEntity.toDomain(): RestingTremorResult {
    val metrics = RestingTremorMetrics(
        plannedDurationMs = plannedDurationMs,
        actualDurationMs = actualDurationMs,
        totalFrames = totalFrames,
        validFrames = validFrames,
        noHandFrames = noHandFrames,
        outOfFrameFrames = outOfFrameFrames,
        multipleHandsFrames = multipleHandsFrames,
        wrongHandFrames = wrongHandFrames,
        errorFrames = errorFrames,
        validFramePercent = validFramePercent,
        interruptionCount = interruptionCount,
        interruptionTotalMs = interruptionTotalMs,
        longestInterruptionMs = longestInterruptionMs,
        medianFrameIntervalMs = medianFrameIntervalMs,
        frameRateHz = frameRateHz,
        irregularIntervalPercent = irregularIntervalPercent,
        medianHandScalePx = medianHandScalePx,
        signalCoveragePercent = signalCoveragePercent,
        slowDriftPercent = slowDriftPercent,
        amplitudeRmsPercent = amplitudeRmsPercent,
        dominantFrequencyHz = dominantFrequencyHz,
        spectralProminence = spectralProminence,
        periodicityPercent = periodicityPercent,
    )
    return RestingTremorResult(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        // Unknown names (e.g. written by a newer app version) degrade to the safe side.
        hand = tremorEnumOrNull<SelectedHand>(hand) ?: SelectedHand.RIGHT,
        plannedDurationMs = plannedDurationMs,
        metrics = metrics,
        qualityStatus = tremorEnumOrNull<QualityStatus>(qualityStatus) ?: QualityStatus.INVALID,
        qualityIssues = qualityIssues.split(',').mapNotNull { tremorEnumOrNull<RestingTremorQualityIssue>(it) },
        qualityScore = qualityScore,
        series = RestingTremorSeries(
            frameTimesMs = frameTimesMs.tremorLongs(),
            frameStatuses = frameStatuses,
            palmXPx = palmXPx.tremorDoubles(),
            palmYPx = palmYPx.tremorDoubles(),
            handScalePx = handScalePx.tremorDoubles(),
        ),
        algorithmVersion = algorithmVersion,
        configSummary = configSummary,
        scoringVersion = scoringVersion,
    )
}
