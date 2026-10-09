package com.example.parkinson.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.imu.ImuDataInfo
import com.example.parkinson.imu.ImuGaitResult
import com.example.parkinson.imu.ImuRepetition
import com.example.parkinson.imu.ImuSitToStandResult
import com.example.parkinson.imu.InvalidInterval
import com.example.parkinson.imu.Rejection
import com.example.parkinson.imu.RejectionReason
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * Stored sensor-based sit-to-stand result (phone in the trouser pocket). Stores the detected transfers, the rejected
 * candidates, the invalid intervals and the sensor-quality facts. No raw sensor samples are stored.
 */
@Entity(tableName = "imu_sit_to_stand_assessments", indices = [Index("timestampEpochMs")])
data class ImuSitToStandEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val sessionId: String,
    val protocolId: String,
    val placement: String,
    val targetRepetitions: Int,
    /** Per repetition, comma-separated, grid ms relative to the first repetition. */
    val repStandOnsetMs: String,
    val repStandReachedMs: String,
    val repSitOnsetMs: String,
    val repSeatedReachedMs: String,
    val repPeakPostureDeg: String,
    val repPeakRotationDegPerS: String,
    /** "REASON:startMs-endMs" entries, comma-separated. */
    val rejections: String,
    /** "startMs-endMs" entries, comma-separated. */
    val invalidIntervals: String,
    val coveragePercent: Double,
    val accelerometerRateHz: Double,
    val gyroscopeRateHz: Double,
    val dropoutCount: Int,
    val timestampIssues: Int,
    val receivedEvents: Int,
    val unreliablePercent: Double,
    val qualityStatus: String,
    val algorithmVersion: String,
    val configSummary: String,
    val scoringVersion: String,
)

@Dao
interface ImuSitToStandDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ImuSitToStandEntity)

    @Query("SELECT * FROM imu_sit_to_stand_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<ImuSitToStandEntity>>

    @Query("SELECT * FROM imu_sit_to_stand_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<ImuSitToStandEntity?>
}

/**
 * Stored sensor-based walking result. Stores the walking summary (steps, bouts, cadence, variability, turning and
 * rejection counts, coverage) and the sensor-quality facts. No raw sensor samples and no per-step times are stored.
 */
@Entity(tableName = "imu_gait_assessments", indices = [Index("timestampEpochMs")])
data class ImuGaitEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val sessionId: String,
    val protocolId: String,
    val placement: String,
    val plannedWalkingMs: Double,
    val validWalkingMs: Double,
    val steps: Int,
    val bouts: Int,
    val cadenceStepsPerMinute: Double?,
    val meanStepIntervalMs: Double?,
    val stepIntervalCvPercent: Double?,
    val turningMs: Double,
    val turnRejectedSteps: Int,
    val implausibleRejectedPeaks: Int,
    val coveragePercent: Double,
    val invalidIntervals: String,
    val accelerometerRateHz: Double,
    val gyroscopeRateHz: Double,
    val dropoutCount: Int,
    val timestampIssues: Int,
    val receivedEvents: Int,
    val unreliablePercent: Double,
    val qualityStatus: String,
    val algorithmVersion: String,
    val configSummary: String,
    val scoringVersion: String,
)

@Dao
interface ImuGaitDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ImuGaitEntity)

    @Query("SELECT * FROM imu_gait_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<ImuGaitEntity>>

    @Query("SELECT * FROM imu_gait_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<ImuGaitEntity?>
}

private fun List<Double>.csvD(): String = joinToString(",") { String.format(Locale.US, "%.3f", it) }
private fun String.parseD(): List<Double> = if (isBlank()) emptyList() else split(',').map { it.toDouble() }
private inline fun <reified E : Enum<E>> imuEnumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }


fun ImuSitToStandResult.toEntity(): ImuSitToStandEntity = ImuSitToStandEntity(
    assessmentId = assessmentId,
    timestampEpochMs = timestampEpochMs,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    sessionId = sessionId,
    protocolId = protocolId,
    placement = placement,
    targetRepetitions = targetRepetitions,
    repStandOnsetMs = repetitions.joinToString(",") { String.format(Locale.US, "%.3f", it.standOnsetMs) },
    repStandReachedMs = repetitions.joinToString(",") { String.format(Locale.US, "%.3f", it.standReachedMs) },
    repSitOnsetMs = repetitions.joinToString(",") { String.format(Locale.US, "%.3f", it.sitOnsetMs) },
    repSeatedReachedMs = repetitions.joinToString(",") { String.format(Locale.US, "%.3f", it.seatedReachedMs) },
    repPeakPostureDeg = repetitions.map { it.peakPostureDeg }.csvD(),
    repPeakRotationDegPerS = repetitions.map { it.peakRotationDegPerSec }.csvD(),
    rejections = rejections.joinToString(",") { "${it.reason.name}:${String.format(Locale.US, "%.3f", it.startMs)}-${String.format(Locale.US, "%.3f", it.endMs)}" },
    invalidIntervals = invalidIntervals.joinToString(",") { String.format(Locale.US, "%.3f", it.startMs) + "-" + String.format(Locale.US, "%.3f", it.endMs) },
    coveragePercent = coveragePercent,
    accelerometerRateHz = data.accelerometerRateHz,
    gyroscopeRateHz = data.gyroscopeRateHz,
    dropoutCount = data.dropoutCount,
    timestampIssues = data.timestampIssues,
    receivedEvents = data.receivedEvents,
    unreliablePercent = data.unreliablePercent,
    qualityStatus = qualityStatus.name,
    algorithmVersion = algorithmVersion,
    configSummary = configSummary,
    scoringVersion = scoringVersion,
)

fun ImuSitToStandEntity.toDomain(): ImuSitToStandResult {
    val onset = repStandOnsetMs.parseD()
    val reached = repStandReachedMs.parseD()
    val sitOnset = repSitOnsetMs.parseD()
    val seated = repSeatedReachedMs.parseD()
    val peakPosture = repPeakPostureDeg.parseD()
    val peakRotation = repPeakRotationDegPerS.parseD()
    val repetitions = onset.indices.map { i ->
        ImuRepetition(i + 1, onset[i], reached[i], sitOnset[i], seated[i], peakPosture[i], peakRotation[i])
    }
    return ImuSitToStandResult(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        startEpochMs = startEpochMs,
        endEpochMs = endEpochMs,
        sessionId = sessionId,
        protocolId = protocolId,
        placement = placement,
        targetRepetitions = targetRepetitions,
        repetitions = repetitions,
        rejections = rejections.split(',').filter { it.contains(':') }.mapNotNull { entry ->
            val (reason, span) = entry.split(':', limit = 2)
            val (a, b) = span.split('-')
            imuEnumOrNull<RejectionReason>(reason)?.let { Rejection(it, a.toDouble(), b.toDouble()) }
        },
        invalidIntervals = invalidIntervals.split(',').filter { it.contains('-') }.map {
            val (a, b) = it.split('-')
            InvalidInterval(a.toDouble(), b.toDouble())
        },
        coveragePercent = coveragePercent,
        data = ImuDataInfo(
            accelerometerRateHz = accelerometerRateHz,
            gyroscopeRateHz = gyroscopeRateHz,
            dropoutCount = dropoutCount,
            timestampIssues = timestampIssues,
            receivedEvents = receivedEvents,
            unreliablePercent = unreliablePercent,
        ),
        qualityStatus = imuEnumOrNull<QualityStatus>(qualityStatus) ?: QualityStatus.INVALID,
        algorithmVersion = algorithmVersion,
        configSummary = configSummary,
        scoringVersion = scoringVersion,
    )
}

fun ImuGaitResult.toEntity(): ImuGaitEntity = ImuGaitEntity(
    assessmentId = assessmentId,
    timestampEpochMs = timestampEpochMs,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    sessionId = sessionId,
    protocolId = protocolId,
    placement = placement,
    plannedWalkingMs = plannedWalkingMs,
    validWalkingMs = validWalkingMs,
    steps = steps,
    bouts = bouts,
    cadenceStepsPerMinute = cadenceStepsPerMinute,
    meanStepIntervalMs = meanStepIntervalMs,
    stepIntervalCvPercent = stepIntervalCvPercent,
    turningMs = turningMs,
    turnRejectedSteps = turnRejectedSteps,
    implausibleRejectedPeaks = implausibleRejectedPeaks,
    coveragePercent = coveragePercent,
    invalidIntervals = invalidIntervals.joinToString(",") { String.format(Locale.US, "%.3f", it.startMs) + "-" + String.format(Locale.US, "%.3f", it.endMs) },
    accelerometerRateHz = data.accelerometerRateHz,
    gyroscopeRateHz = data.gyroscopeRateHz,
    dropoutCount = data.dropoutCount,
    timestampIssues = data.timestampIssues,
    receivedEvents = data.receivedEvents,
    unreliablePercent = data.unreliablePercent,
    qualityStatus = qualityStatus.name,
    algorithmVersion = algorithmVersion,
    configSummary = configSummary,
    scoringVersion = scoringVersion,
)

fun ImuGaitEntity.toDomain(): ImuGaitResult = ImuGaitResult(
    assessmentId = assessmentId,
    timestampEpochMs = timestampEpochMs,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    sessionId = sessionId,
    protocolId = protocolId,
    placement = placement,
    plannedWalkingMs = plannedWalkingMs,
    validWalkingMs = validWalkingMs,
    steps = steps,
    bouts = bouts,
    cadenceStepsPerMinute = cadenceStepsPerMinute,
    meanStepIntervalMs = meanStepIntervalMs,
    stepIntervalCvPercent = stepIntervalCvPercent,
    turningMs = turningMs,
    turnRejectedSteps = turnRejectedSteps,
    implausibleRejectedPeaks = implausibleRejectedPeaks,
    coveragePercent = coveragePercent,
    invalidIntervals = invalidIntervals.split(',').filter { it.contains('-') }.map {
        val (a, b) = it.split('-')
        InvalidInterval(a.toDouble(), b.toDouble())
    },
    data = ImuDataInfo(
        accelerometerRateHz = accelerometerRateHz,
        gyroscopeRateHz = gyroscopeRateHz,
        dropoutCount = dropoutCount,
        timestampIssues = timestampIssues,
        receivedEvents = receivedEvents,
        unreliablePercent = unreliablePercent,
    ),
    qualityStatus = imuEnumOrNull<QualityStatus>(qualityStatus) ?: QualityStatus.INVALID,
    algorithmVersion = algorithmVersion,
    configSummary = configSummary,
    scoringVersion = scoringVersion,
)

