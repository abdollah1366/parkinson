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
import com.example.parkinson.tremor.ImuTremorMetrics
import com.example.parkinson.tremor.ImuTremorQualityIssue
import com.example.parkinson.tremor.RestingTremorImuResult
import kotlinx.coroutines.flow.Flow

/**
 * Stored phone-motion (accelerometer / gyroscope) result of the Resting Hand Tremor test. It lives in its own
 * table so the camera table stays unchanged. Every metric is NULL when it could not be measured (never zero), and
 * the row keeps its status and reason even when no metric exists. No raw sensor samples are stored.
 */
@Entity(tableName = "resting_tremor_imu_assessments", indices = [Index("timestampEpochMs")])
data class RestingTremorImuEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val hand: String,
    val plannedDurationMs: Long,
    /** Whether the device has a gyroscope (not whether its data was used). */
    val gyroscopePresent: Boolean,
    val qualityStatus: String,
    /** Comma-separated ImuTremorQualityIssue names. */
    val qualityIssues: String,
    val spanMs: Double?,
    val accelSamples: Int?,
    val accelRateHz: Double?,
    val accelDropouts: Int?,
    val accelLongestGapMs: Double?,
    /** Null when the gyroscope data was not analysed. */
    val gyroSamples: Int?,
    val accelRmsMs2: Double?,
    val gyroRmsRadS: Double?,
    val dominantFrequencyHz: Double?,
    val spectralProminence: Double?,
    val algorithmVersion: String,
    val configSummary: String,
    val scoringVersion: String,
    /** The registered person this result belongs to; null for a result saved while nobody was registered. */
    val profileId: Int? = null,
)

@Dao
interface RestingTremorImuDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: RestingTremorImuEntity)

    @Query("SELECT * FROM resting_tremor_imu_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<RestingTremorImuEntity>>

    @Query("SELECT * FROM resting_tremor_imu_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<RestingTremorImuEntity?>
}

fun RestingTremorImuResult.toEntity(): RestingTremorImuEntity {
    val m = metrics
    return RestingTremorImuEntity(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        hand = hand.name,
        plannedDurationMs = plannedDurationMs,
        gyroscopePresent = gyroscopePresent,
        qualityStatus = qualityStatus.name,
        qualityIssues = qualityIssues.joinToString(",") { it.name },
        spanMs = m?.spanMs,
        accelSamples = m?.accelSamples,
        accelRateHz = m?.accelRateHz,
        accelDropouts = m?.accelDropouts,
        accelLongestGapMs = m?.accelLongestGapMs,
        gyroSamples = m?.gyroSamples,
        accelRmsMs2 = m?.accelRmsMs2,
        gyroRmsRadS = m?.gyroRmsRadS,
        dominantFrequencyHz = m?.dominantFrequencyHz,
        spectralProminence = m?.spectralProminence,
        algorithmVersion = algorithmVersion,
        configSummary = configSummary,
        scoringVersion = scoringVersion,
    )
}

fun RestingTremorImuEntity.toDomain(): RestingTremorImuResult {
    // Metrics exist only when their required values were stored; a partial row yields no metrics, never zeros.
    val metrics = if (spanMs != null && accelSamples != null && accelRateHz != null && accelDropouts != null &&
        accelLongestGapMs != null && accelRmsMs2 != null
    ) {
        ImuTremorMetrics(
            spanMs = spanMs,
            accelSamples = accelSamples,
            accelRateHz = accelRateHz,
            accelDropouts = accelDropouts,
            accelLongestGapMs = accelLongestGapMs,
            gyroSamples = gyroSamples,
            accelRmsMs2 = accelRmsMs2,
            gyroRmsRadS = gyroRmsRadS,
            dominantFrequencyHz = dominantFrequencyHz,
            spectralProminence = spectralProminence,
        )
    } else {
        null
    }
    return RestingTremorImuResult(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        hand = SelectedHand.entries.firstOrNull { it.name == hand } ?: SelectedHand.RIGHT,
        plannedDurationMs = plannedDurationMs,
        gyroscopePresent = gyroscopePresent,
        qualityStatus = QualityStatus.entries.firstOrNull { it.name == qualityStatus } ?: QualityStatus.INVALID,
        qualityIssues = qualityIssues.split(',').mapNotNull { name ->
            ImuTremorQualityIssue.entries.firstOrNull { it.name == name }
        },
        metrics = metrics,
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion,
        configSummary = configSummary,
    )
}
