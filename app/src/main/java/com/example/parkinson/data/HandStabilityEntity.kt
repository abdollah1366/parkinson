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
import com.example.parkinson.stability.FrequencyStatus
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.stability.StabilityIndex
import com.example.parkinson.stability.StabilityQualityIssue
import kotlinx.coroutines.flow.Flow

/**
 * Stored Hand Stability result: derived measurements only, never the raw sensor stream.
 * Enums are stored by name.
 */
@Entity(tableName = "hand_stability_assessments", indices = [Index("timestampEpochMs")])
data class HandStabilityEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val hand: String,
    val plannedDurationMs: Long,
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
    val frequencyStatus: String,
    val qualityStatus: String,
    /** Comma-separated StabilityQualityIssue names. */
    val qualityIssues: String,
    val qualityScore: Int,
    val indexTotal: Int?,
    val indexRotation: Int?,
    val indexAcceleration: Int?,
    val algorithmVersion: String,
    val scoringVersion: String
)

@Dao
interface HandStabilityDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HandStabilityEntity)

    @Query("SELECT * FROM hand_stability_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<HandStabilityEntity>>

    @Query("SELECT * FROM hand_stability_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<HandStabilityEntity?>
}

fun HandStabilityResult.toEntity(): HandStabilityEntity = HandStabilityEntity(
    assessmentId = assessmentId,
    timestampEpochMs = timestampEpochMs,
    hand = hand.name,
    plannedDurationMs = plannedDurationMs,
    recordingDurationMs = recordingDurationMs,
    accSampleCount = accSampleCount,
    accSamplingRateHz = accSamplingRateHz,
    accMedianIntervalMs = accMedianIntervalMs,
    gyroSampleCount = gyroSampleCount,
    gyroSamplingRateHz = gyroSamplingRateHz,
    gyroMedianIntervalMs = gyroMedianIntervalMs,
    validSamplePercent = validSamplePercent,
    dropoutCount = dropoutCount,
    dropoutDurationMs = dropoutDurationMs,
    longestGapMs = longestGapMs,
    completenessPercent = completenessPercent,
    accMagnitudeMean = accMagnitudeMean,
    accMagnitudeSd = accMagnitudeSd,
    accMagnitudeVariance = accMagnitudeVariance,
    accMagnitudeRange = accMagnitudeRange,
    accDynamicRms = accDynamicRms,
    accDynamicVariance = accDynamicVariance,
    gyroMagnitudeMean = gyroMagnitudeMean,
    gyroMagnitudeRms = gyroMagnitudeRms,
    gyroMagnitudeSd = gyroMagnitudeSd,
    gyroMagnitudeVariance = gyroMagnitudeVariance,
    gyroMagnitudeMax = gyroMagnitudeMax,
    gyroDynamicRms = gyroDynamicRms,
    rotationRangeDeg = rotationRangeDeg,
    tiltChangeDeg = tiltChangeDeg,
    dominantFrequencyHz = dominantFrequencyHz,
    oscillationBandPowerPercent = oscillationBandPowerPercent,
    frequencyStatus = frequencyStatus.name,
    qualityStatus = qualityStatus.name,
    qualityIssues = qualityIssues.joinToString(",") { it.name },
    qualityScore = qualityScore,
    indexTotal = stabilityIndex?.total,
    indexRotation = stabilityIndex?.rotationComponent,
    indexAcceleration = stabilityIndex?.accelerationComponent,
    algorithmVersion = algorithmVersion,
    scoringVersion = scoringVersion
)

fun HandStabilityEntity.toDomain(): HandStabilityResult {
    val index = if (indexTotal != null && indexRotation != null && indexAcceleration != null) {
        StabilityIndex(indexTotal, indexRotation, indexAcceleration, scoringVersion)
    } else null
    return HandStabilityResult(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        hand = SelectedHand.entries.firstOrNull { it.name == hand } ?: SelectedHand.RIGHT,
        plannedDurationMs = plannedDurationMs,
        recordingDurationMs = recordingDurationMs,
        accSampleCount = accSampleCount,
        accSamplingRateHz = accSamplingRateHz,
        accMedianIntervalMs = accMedianIntervalMs,
        gyroSampleCount = gyroSampleCount,
        gyroSamplingRateHz = gyroSamplingRateHz,
        gyroMedianIntervalMs = gyroMedianIntervalMs,
        validSamplePercent = validSamplePercent,
        dropoutCount = dropoutCount,
        dropoutDurationMs = dropoutDurationMs,
        longestGapMs = longestGapMs,
        completenessPercent = completenessPercent,
        accMagnitudeMean = accMagnitudeMean,
        accMagnitudeSd = accMagnitudeSd,
        accMagnitudeVariance = accMagnitudeVariance,
        accMagnitudeRange = accMagnitudeRange,
        accDynamicRms = accDynamicRms,
        accDynamicVariance = accDynamicVariance,
        gyroMagnitudeMean = gyroMagnitudeMean,
        gyroMagnitudeRms = gyroMagnitudeRms,
        gyroMagnitudeSd = gyroMagnitudeSd,
        gyroMagnitudeVariance = gyroMagnitudeVariance,
        gyroMagnitudeMax = gyroMagnitudeMax,
        gyroDynamicRms = gyroDynamicRms,
        rotationRangeDeg = rotationRangeDeg,
        tiltChangeDeg = tiltChangeDeg,
        dominantFrequencyHz = dominantFrequencyHz,
        oscillationBandPowerPercent = oscillationBandPowerPercent,
        frequencyStatus = FrequencyStatus.entries.firstOrNull { it.name == frequencyStatus } ?: FrequencyStatus.NO_CLEAR_PEAK,
        // Unknown names (e.g. written by a newer app version) degrade to the safe side.
        qualityStatus = QualityStatus.entries.firstOrNull { it.name == qualityStatus } ?: QualityStatus.INVALID,
        qualityIssues = qualityIssues.split(',').mapNotNull { n -> StabilityQualityIssue.entries.firstOrNull { it.name == n } },
        qualityScore = qualityScore,
        stabilityIndex = index,
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion
    )
}
