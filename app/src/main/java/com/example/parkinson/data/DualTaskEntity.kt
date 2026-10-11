package com.example.parkinson.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.dualtask.DualTaskResult
import com.example.parkinson.dualtask.WalkSummary
import kotlinx.coroutines.flow.Flow

/**
 * Stored motor-cognitive (dual-task) result: summary values of the usual walk (single*) and of the walk while
 * subtracting (dual*), and the answers. NULL means not measured. No sensor samples and no audio are stored.
 */
@Entity(tableName = "dual_task_assessments", indices = [Index("timestampEpochMs")])
data class DualTaskEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val sessionId: String,
    val protocolId: String,
    val plannedWalkingMs: Double,
    val singleCadence: Double?,
    val singleStrideTimeMs: Double?,
    val singleStrideCvPercent: Double?,
    val singleStepAsymmetryPercent: Double?,
    val singleTurnVelocityDegPerSec: Double?,
    val singleSteps: Int,
    val dualCadence: Double?,
    val dualStrideTimeMs: Double?,
    val dualStrideCvPercent: Double?,
    val dualStepAsymmetryPercent: Double?,
    val dualTurnVelocityDegPerSec: Double?,
    val dualSteps: Int,
    val startNumber: Int,
    val lastNumber: Int?,
    val answers: Int?,
    val answersExact: Boolean?,
    val spokenUtterances: Int?,
    val longPauses: Int?,
    val longestPauseMs: Double?,
    val coveragePercent: Double,
    val qualityStatus: String,
    val algorithmVersion: String,
    val configSummary: String,
    val scoringVersion: String,
)

@Dao
interface DualTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: DualTaskEntity)

    @Query("SELECT * FROM dual_task_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<DualTaskEntity>>

    @Query("SELECT * FROM dual_task_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<DualTaskEntity?>
}

fun DualTaskResult.toEntity() = DualTaskEntity(
    assessmentId = assessmentId,
    timestampEpochMs = timestampEpochMs,
    sessionId = sessionId,
    protocolId = protocolId,
    plannedWalkingMs = plannedWalkingMs,
    singleCadence = single.cadence,
    singleStrideTimeMs = single.strideTimeMs,
    singleStrideCvPercent = single.strideCvPercent,
    singleStepAsymmetryPercent = single.stepAsymmetryPercent,
    singleTurnVelocityDegPerSec = single.meanTurnVelocityDegPerSec,
    singleSteps = singleSteps,
    dualCadence = dual.cadence,
    dualStrideTimeMs = dual.strideTimeMs,
    dualStrideCvPercent = dual.strideCvPercent,
    dualStepAsymmetryPercent = dual.stepAsymmetryPercent,
    dualTurnVelocityDegPerSec = dual.meanTurnVelocityDegPerSec,
    dualSteps = dualSteps,
    startNumber = startNumber,
    lastNumber = lastNumber,
    answers = answers,
    answersExact = answersExact,
    spokenUtterances = spokenUtterances,
    longPauses = longPauses,
    longestPauseMs = longestPauseMs,
    coveragePercent = coveragePercent,
    qualityStatus = qualityStatus.name,
    algorithmVersion = algorithmVersion,
    configSummary = configSummary,
    scoringVersion = scoringVersion,
)

fun DualTaskEntity.toDomain() = DualTaskResult(
    assessmentId = assessmentId,
    timestampEpochMs = timestampEpochMs,
    sessionId = sessionId,
    protocolId = protocolId,
    plannedWalkingMs = plannedWalkingMs,
    single = WalkSummary(singleCadence, singleStrideTimeMs, singleStrideCvPercent, singleStepAsymmetryPercent, singleTurnVelocityDegPerSec),
    dual = WalkSummary(dualCadence, dualStrideTimeMs, dualStrideCvPercent, dualStepAsymmetryPercent, dualTurnVelocityDegPerSec),
    singleSteps = singleSteps,
    dualSteps = dualSteps,
    startNumber = startNumber,
    lastNumber = lastNumber,
    answers = answers,
    answersExact = answersExact,
    spokenUtterances = spokenUtterances,
    longPauses = longPauses,
    longestPauseMs = longestPauseMs,
    coveragePercent = coveragePercent,
    qualityStatus = QualityStatus.entries.firstOrNull { it.name == qualityStatus } ?: QualityStatus.INVALID,
    algorithmVersion = algorithmVersion,
    configSummary = configSummary,
    scoringVersion = scoringVersion,
)
