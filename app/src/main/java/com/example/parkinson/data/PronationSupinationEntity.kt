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
import com.example.parkinson.pronation.MetricTrend
import com.example.parkinson.pronation.PerformanceTrend
import com.example.parkinson.pronation.PronationQualityIssue
import com.example.parkinson.pronation.PronationSupinationPerformanceScore
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.pronation.TrendDirection
import kotlinx.coroutines.flow.Flow

/**
 * Stored Pronation/Supination result: derived measurements only, never the raw sensor stream.
 * Enums are stored by name. Trend columns: amplitude (amp*), peak velocity (vel*), cycle
 * duration (dur*) in the early / middle / late third.
 */
@Entity(tableName = "pronation_supination_assessments", indices = [Index("timestampEpochMs")])
data class PronationSupinationEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val hand: String,
    val plannedDurationMs: Long,
    val durationMs: Long,
    val cycleCount: Int,
    val validCycleCount: Int,
    val cycleRateHz: Double,
    val meanCycleDurationMs: Double?,
    val medianCycleDurationMs: Double?,
    val cycleVariability: Double?,
    val angularVelocityMean: Double,
    val angularVelocityPeak: Double?,
    val angularVelocityVariability: Double?,
    val angularVelocityRms: Double,
    val movementAmplitude: Double?,
    val amplitudeVariability: Double?,
    val movementConsistency: Double?,
    val pauseCount: Int,
    val pauseDurationMs: Long,
    val accelerationRms: Double,
    val dominantFrequencyHz: Double?,
    val rotationAxisSharePercent: Double,
    val ampEarly: Double?,
    val ampMiddle: Double?,
    val ampLate: Double?,
    val ampChangePercent: Double?,
    val ampDirection: String?,
    val velEarly: Double?,
    val velMiddle: Double?,
    val velLate: Double?,
    val velChangePercent: Double?,
    val velDirection: String?,
    val durEarly: Double?,
    val durMiddle: Double?,
    val durLate: Double?,
    val durChangePercent: Double?,
    val durDirection: String?,
    val effectiveSamplingRate: Double,
    val accSamplingRateHz: Double,
    val validSamplePercentage: Double,
    val dropoutCount: Int,
    val completenessPercent: Double,
    val qualityStatus: String,
    /** Comma-separated PronationQualityIssue names. */
    val qualityIssues: String,
    val qualityScore: Int,
    val scoreTotal: Int?,
    val scoreRate: Int?,
    val scoreRhythm: Int?,
    val scoreAmplitude: Int?,
    val scoreVelocity: Int?,
    val scoreConsistency: Int?,
    val scoreDataQuality: Int?,
    val algorithmVersion: String,
    val scoringVersion: String
)

@Dao
interface PronationSupinationDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: PronationSupinationEntity)

    @Query("SELECT * FROM pronation_supination_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<PronationSupinationEntity>>

    @Query("SELECT * FROM pronation_supination_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<PronationSupinationEntity?>
}

fun PronationSupinationResult.toEntity(): PronationSupinationEntity {
    val tr = trend
    val s = performanceScore
    return PronationSupinationEntity(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        hand = hand.name,
        plannedDurationMs = plannedDurationMs,
        durationMs = durationMs,
        cycleCount = cycleCount,
        validCycleCount = validCycleCount,
        cycleRateHz = cycleRateHz,
        meanCycleDurationMs = meanCycleDurationMs,
        medianCycleDurationMs = medianCycleDurationMs,
        cycleVariability = cycleVariability,
        angularVelocityMean = angularVelocityMean,
        angularVelocityPeak = angularVelocityPeak,
        angularVelocityVariability = angularVelocityVariability,
        angularVelocityRms = angularVelocityRms,
        movementAmplitude = movementAmplitude,
        amplitudeVariability = amplitudeVariability,
        movementConsistency = movementConsistency,
        pauseCount = pauseCount,
        pauseDurationMs = pauseDurationMs,
        accelerationRms = accelerationRms,
        dominantFrequencyHz = dominantFrequencyHz,
        rotationAxisSharePercent = rotationAxisSharePercent,
        ampEarly = tr?.amplitudeDeg?.early,
        ampMiddle = tr?.amplitudeDeg?.middle,
        ampLate = tr?.amplitudeDeg?.late,
        ampChangePercent = tr?.amplitudeDeg?.changePercent,
        ampDirection = tr?.amplitudeDeg?.direction?.name,
        velEarly = tr?.peakVelocityDegS?.early,
        velMiddle = tr?.peakVelocityDegS?.middle,
        velLate = tr?.peakVelocityDegS?.late,
        velChangePercent = tr?.peakVelocityDegS?.changePercent,
        velDirection = tr?.peakVelocityDegS?.direction?.name,
        durEarly = tr?.cycleDurationMs?.early,
        durMiddle = tr?.cycleDurationMs?.middle,
        durLate = tr?.cycleDurationMs?.late,
        durChangePercent = tr?.cycleDurationMs?.changePercent,
        durDirection = tr?.cycleDurationMs?.direction?.name,
        effectiveSamplingRate = effectiveSamplingRate,
        accSamplingRateHz = accSamplingRateHz,
        validSamplePercentage = validSamplePercentage,
        dropoutCount = dropoutCount,
        completenessPercent = completenessPercent,
        qualityStatus = qualityStatus.name,
        qualityIssues = qualityIssues.joinToString(",") { it.name },
        qualityScore = qualityScore,
        scoreTotal = s?.total,
        scoreRate = s?.rate,
        scoreRhythm = s?.rhythm,
        scoreAmplitude = s?.amplitude,
        scoreVelocity = s?.velocity,
        scoreConsistency = s?.consistency,
        scoreDataQuality = s?.dataQuality,
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion
    )
}

private fun direction(name: String?): TrendDirection? = TrendDirection.entries.firstOrNull { it.name == name }

fun PronationSupinationEntity.toDomain(): PronationSupinationResult {
    val score = if (scoreTotal != null && scoreRate != null && scoreAmplitude != null &&
        scoreVelocity != null && scoreDataQuality != null
    ) {
        PronationSupinationPerformanceScore(
            total = scoreTotal,
            rate = scoreRate,
            rhythm = scoreRhythm,
            amplitude = scoreAmplitude,
            velocity = scoreVelocity,
            consistency = scoreConsistency,
            dataQuality = scoreDataQuality,
            scoringVersion = scoringVersion
        )
    } else null
    val trend = PerformanceTrend(
        amplitudeDeg = MetricTrend(ampEarly, ampMiddle, ampLate, ampChangePercent, direction(ampDirection)),
        peakVelocityDegS = MetricTrend(velEarly, velMiddle, velLate, velChangePercent, direction(velDirection)),
        cycleDurationMs = MetricTrend(durEarly, durMiddle, durLate, durChangePercent, direction(durDirection))
    )
    return PronationSupinationResult(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        hand = SelectedHand.entries.firstOrNull { it.name == hand } ?: SelectedHand.RIGHT,
        plannedDurationMs = plannedDurationMs,
        durationMs = durationMs,
        cycleCount = cycleCount,
        validCycleCount = validCycleCount,
        cycleRateHz = cycleRateHz,
        meanCycleDurationMs = meanCycleDurationMs,
        medianCycleDurationMs = medianCycleDurationMs,
        cycleVariability = cycleVariability,
        angularVelocityMean = angularVelocityMean,
        angularVelocityPeak = angularVelocityPeak,
        angularVelocityVariability = angularVelocityVariability,
        angularVelocityRms = angularVelocityRms,
        movementAmplitude = movementAmplitude,
        amplitudeVariability = amplitudeVariability,
        movementConsistency = movementConsistency,
        pauseCount = pauseCount,
        pauseDurationMs = pauseDurationMs,
        accelerationRms = accelerationRms,
        dominantFrequencyHz = dominantFrequencyHz,
        rotationAxisSharePercent = rotationAxisSharePercent,
        trend = trend,
        effectiveSamplingRate = effectiveSamplingRate,
        accSamplingRateHz = accSamplingRateHz,
        validSamplePercentage = validSamplePercentage,
        dropoutCount = dropoutCount,
        completenessPercent = completenessPercent,
        // Unknown names (e.g. written by a newer app version) degrade to the safe side.
        qualityStatus = QualityStatus.entries.firstOrNull { it.name == qualityStatus } ?: QualityStatus.INVALID,
        qualityIssues = qualityIssues.split(',').mapNotNull { n -> PronationQualityIssue.entries.firstOrNull { it.name == n } },
        qualityScore = qualityScore,
        performanceScore = score,
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion
    )
}
