package com.example.parkinson.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.gait.GaitMetrics
import com.example.parkinson.gait.GaitQualityIssue
import com.example.parkinson.gait.GaitResult
import com.example.parkinson.gait.GaitSeries
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * Stored walking (camera pose) result: the metrics, the data quality and the timestamped pose series they
 * were computed from. No camera images, no keypoint images and no personal identifiers. Nullable metrics
 * are NULL when they could not be calculated (never zero). Plantar pressure is not measured by this test.
 */
@Entity(tableName = "gait_assessments", indices = [Index("timestampEpochMs")])
data class GaitEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val plannedDurationMs: Long,

    // --- metrics
    val actualDurationMs: Long,
    val totalFrames: Int,
    val validFrames: Int,
    val validFramePercent: Double,
    val longestGapMs: Long,
    val gapCount: Int,
    val medianFrameIntervalMs: Double,
    val frameRateHz: Double,
    /** Comma-separated step times (ms, relative to the first frame). */
    val stepTimesMs: String,
    val stepCount: Int,
    val cadenceStepsPerMinute: Double?,
    val stepIntervalCvPercent: Double?,
    val trunkLeanMeanDeg: Double?,
    val leftArmSwingRangeTorso: Double?,
    val rightArmSwingRangeTorso: Double?,

    // --- quality
    val qualityStatus: String,
    /** Comma-separated GaitQualityIssue names. */
    val qualityIssues: String,
    val qualityScore: Int,

    // --- timestamped pose series (comma-separated, one entry per frame)
    val frameTimesMs: String,
    /** One letter per frame (GaitSeries.statusCode). */
    val frameStatuses: String,
    val ankleSeparationTorso: String,

    val algorithmVersion: String,
    val configSummary: String,
    val scoringVersion: String,
)

@Dao
interface GaitDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GaitEntity)

    @Query("SELECT * FROM gait_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<GaitEntity>>

    @Query("SELECT * FROM gait_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<GaitEntity?>
}

private fun List<Double>.gaitCsv(): String = joinToString(",") { String.format(Locale.US, "%.4f", it) }
private fun String.gaitDoubles(): List<Double> = if (isBlank()) emptyList() else split(',').map { it.toDouble() }
private fun String.gaitLongs(): List<Long> = if (isBlank()) emptyList() else split(',').map { it.toLong() }

private inline fun <reified E : Enum<E>> gaitEnumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

fun GaitResult.toEntity(): GaitEntity {
    val m = metrics
    val s = series
    return GaitEntity(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        plannedDurationMs = plannedDurationMs,
        actualDurationMs = m.actualDurationMs,
        totalFrames = m.totalFrames,
        validFrames = m.validFrames,
        validFramePercent = m.validFramePercent,
        longestGapMs = m.longestGapMs,
        gapCount = m.gapCount,
        medianFrameIntervalMs = m.medianFrameIntervalMs,
        frameRateHz = m.frameRateHz,
        stepTimesMs = m.stepTimesMs.joinToString(","),
        stepCount = m.stepCount,
        cadenceStepsPerMinute = m.cadenceStepsPerMinute,
        stepIntervalCvPercent = m.stepIntervalCvPercent,
        trunkLeanMeanDeg = m.trunkLeanMeanDeg,
        leftArmSwingRangeTorso = m.leftArmSwingRangeTorso,
        rightArmSwingRangeTorso = m.rightArmSwingRangeTorso,
        qualityStatus = qualityStatus.name,
        qualityIssues = qualityIssues.joinToString(",") { it.name },
        qualityScore = qualityScore,
        frameTimesMs = s.frameTimesMs.joinToString(","),
        frameStatuses = s.frameStatuses,
        ankleSeparationTorso = s.ankleSeparationTorso.gaitCsv(),
        algorithmVersion = algorithmVersion,
        configSummary = configSummary,
        scoringVersion = scoringVersion,
    )
}

fun GaitEntity.toDomain(): GaitResult {
    val metrics = GaitMetrics(
        plannedDurationMs = plannedDurationMs,
        actualDurationMs = actualDurationMs,
        totalFrames = totalFrames,
        validFrames = validFrames,
        validFramePercent = validFramePercent,
        longestGapMs = longestGapMs,
        gapCount = gapCount,
        medianFrameIntervalMs = medianFrameIntervalMs,
        frameRateHz = frameRateHz,
        stepTimesMs = stepTimesMs.gaitLongs(),
        stepCount = stepCount,
        cadenceStepsPerMinute = cadenceStepsPerMinute,
        stepIntervalCvPercent = stepIntervalCvPercent,
        trunkLeanMeanDeg = trunkLeanMeanDeg,
        leftArmSwingRangeTorso = leftArmSwingRangeTorso,
        rightArmSwingRangeTorso = rightArmSwingRangeTorso,
    )
    return GaitResult(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        plannedDurationMs = plannedDurationMs,
        metrics = metrics,
        // Unknown names (e.g. written by a newer app version) degrade to the safe side.
        qualityStatus = gaitEnumOrNull<QualityStatus>(qualityStatus) ?: QualityStatus.INVALID,
        qualityIssues = qualityIssues.split(',').mapNotNull { gaitEnumOrNull<GaitQualityIssue>(it) },
        qualityScore = qualityScore,
        series = GaitSeries(
            frameTimesMs = frameTimesMs.gaitLongs(),
            frameStatuses = frameStatuses,
            ankleSeparationTorso = ankleSeparationTorso.gaitDoubles(),
        ),
        algorithmVersion = algorithmVersion,
        configSummary = configSummary,
        scoringVersion = scoringVersion,
    )
}
