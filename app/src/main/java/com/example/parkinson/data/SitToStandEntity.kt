package com.example.parkinson.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.sts.SeatedBaseline
import com.example.parkinson.sts.SitToStandIssue
import com.example.parkinson.sts.SitToStandRepetition
import com.example.parkinson.sts.SitToStandResult
import com.example.parkinson.sts.SitToStandSeries
import com.example.parkinson.sts.SitToStandSide
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * Stored Five Times Sit-to-Stand result: the measured repetition times and peaks, the seated baseline and the
 * timestamped series they came from. Derived values (means, variability) are not stored; they are computed
 * from the measurements on read. No camera images and no personal identifiers.
 */
@Entity(tableName = "sit_to_stand_assessments", indices = [Index("timestampEpochMs")])
data class SitToStandEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val protocolId: String,
    val targetRepetitions: Int,
    val side: String,
    val baselineKneeDeg: Double,
    val baselineHipYPx: Double,
    val baselineShinPx: Double,
    val totalTimeMs: Long,

    // --- measured per repetition (comma-separated, one entry per repetition, in order)
    val repStandOnsetMs: String,
    val repStandReachedMs: String,
    val repSitOnsetMs: String,
    val repSeatedReachedMs: String,
    val repPeakHipRise: String,
    val repPeakKneeExtensionDeg: String,

    // --- tracking and quality
    val validFrames: Int,
    val invalidFrames: Int,
    /** "NAME:count" pairs, comma-separated (SitToStandIssue names). */
    val invalidFramesByIssue: String,
    val partialAttempts: Int,
    val qualityStatus: String,

    // --- timestamped series (comma-separated, one entry per accepted sample)
    val frameTimesMs: String,
    /** One letter per sample: V valid, I invalid. */
    val frameValid: String,
    val hipRiseShinLengths: String,
    val kneeAngleDeg: String,
    val trunkLeanDeg: String,

    val algorithmVersion: String,
    val configSummary: String,
    val scoringVersion: String,
)

@Dao
interface SitToStandDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SitToStandEntity)

    @Query("SELECT * FROM sit_to_stand_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<SitToStandEntity>>

    @Query("SELECT * FROM sit_to_stand_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<SitToStandEntity?>
}

private fun List<Double>.stsCsv(): String = joinToString(",") { if (it.isNaN()) "NaN" else String.format(Locale.US, "%.4f", it) }
private fun String.stsDoubles(): List<Double> = if (isBlank()) emptyList() else split(',').map { it.toDouble() }
private fun String.stsLongs(): List<Long> = if (isBlank()) emptyList() else split(',').map { it.toLong() }

fun SitToStandResult.toEntity(): SitToStandEntity {
    val reps = repetitions
    val s = series
    return SitToStandEntity(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        protocolId = protocolId,
        targetRepetitions = targetRepetitions,
        side = side.name,
        baselineKneeDeg = baseline.kneeAngleDeg,
        baselineHipYPx = baseline.hipYPx,
        baselineShinPx = baseline.shinPx,
        totalTimeMs = totalTimeMs,
        repStandOnsetMs = reps.joinToString(",") { it.standOnsetMs.toString() },
        repStandReachedMs = reps.joinToString(",") { it.standReachedMs.toString() },
        repSitOnsetMs = reps.joinToString(",") { it.sitOnsetMs.toString() },
        repSeatedReachedMs = reps.joinToString(",") { it.seatedReachedMs.toString() },
        repPeakHipRise = reps.map { it.peakHipRiseShinLengths }.stsCsv(),
        repPeakKneeExtensionDeg = reps.map { it.peakKneeExtensionDeg }.stsCsv(),
        validFrames = validFrames,
        invalidFrames = invalidFrames,
        invalidFramesByIssue = invalidFramesByIssue.entries.joinToString(",") { "${it.key.name}:${it.value}" },
        partialAttempts = partialAttempts,
        qualityStatus = qualityStatus.name,
        frameTimesMs = s.frameTimesMs.joinToString(","),
        frameValid = s.frameValid,
        hipRiseShinLengths = s.hipRiseShinLengths.stsCsv(),
        kneeAngleDeg = s.kneeAngleDeg.stsCsv(),
        trunkLeanDeg = s.trunkLeanDeg.stsCsv(),
        algorithmVersion = algorithmVersion,
        configSummary = configSummary,
        scoringVersion = scoringVersion,
    )
}

fun SitToStandEntity.toDomain(): SitToStandResult {
    val onsets = repStandOnsetMs.stsLongs()
    val reached = repStandReachedMs.stsLongs()
    val sitOnsets = repSitOnsetMs.stsLongs()
    val seated = repSeatedReachedMs.stsLongs()
    val hips = repPeakHipRise.stsDoubles()
    val knees = repPeakKneeExtensionDeg.stsDoubles()
    val repetitions = onsets.indices.map { i ->
        SitToStandRepetition(
            index = i + 1,
            standOnsetMs = onsets[i],
            standReachedMs = reached[i],
            sitOnsetMs = sitOnsets[i],
            seatedReachedMs = seated[i],
            peakHipRiseShinLengths = hips[i],
            peakKneeExtensionDeg = knees[i],
        )
    }
    return SitToStandResult(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        protocolId = protocolId,
        targetRepetitions = targetRepetitions,
        side = stsEnumOrNull<SitToStandSide>(side) ?: SitToStandSide.RIGHT,
        baseline = SeatedBaseline(
            side = stsEnumOrNull<SitToStandSide>(side) ?: SitToStandSide.RIGHT,
            kneeAngleDeg = baselineKneeDeg,
            hipYPx = baselineHipYPx,
            shinPx = baselineShinPx,
        ),
        repetitions = repetitions,
        totalTimeMs = totalTimeMs,
        validFrames = validFrames,
        invalidFrames = invalidFrames,
        // Unknown issue names (written by a newer version) are left out rather than guessed.
        invalidFramesByIssue = invalidFramesByIssue.split(',').filter { it.contains(':') }.mapNotNull { pair ->
            val (name, count) = pair.split(':')
            stsEnumOrNull<SitToStandIssue>(name)?.let { it to count.toInt() }
        }.toMap(),
        partialAttempts = partialAttempts,
        qualityStatus = stsEnumOrNull<QualityStatus>(qualityStatus) ?: QualityStatus.INVALID,
        series = SitToStandSeries(
            frameTimesMs = frameTimesMs.stsLongs(),
            frameValid = frameValid,
            hipRiseShinLengths = hipRiseShinLengths.stsDoubles(),
            kneeAngleDeg = kneeAngleDeg.stsDoubles(),
            trunkLeanDeg = trunkLeanDeg.stsDoubles(),
        ),
        algorithmVersion = algorithmVersion,
        configSummary = configSummary,
        scoringVersion = scoringVersion,
    )
}

private inline fun <reified E : Enum<E>> stsEnumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }
