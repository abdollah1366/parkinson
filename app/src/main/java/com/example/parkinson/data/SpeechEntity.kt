package com.example.parkinson.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.speech.SpeechMetric
import com.example.parkinson.speech.SpeechQualityIssue
import com.example.parkinson.speech.SpeechResult
import com.example.parkinson.speech.SpeechTask
import com.example.parkinson.speech.SpeechValue
import com.example.parkinson.speech.UnavailableReason
import kotlinx.coroutines.flow.Flow

/**
 * Stored speech result: the task, the quality findings, the actual sample rate and the metric set. Each metric is
 * stored as a measured value or as an explicit unavailable reason (never zero). No audio and no transcript are
 * stored. [sessionId] groups the results of one visit.
 */
@Entity(tableName = "speech_assessments", indices = [Index("timestampEpochMs")])
data class SpeechEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val sessionId: String,
    val task: String,
    val plannedDurationMs: Long,
    val consentAccepted: Boolean,
    val qualityStatus: String,
    /** Comma-separated SpeechQualityIssue names. */
    val qualityIssues: String,
    val qualityScore: Int,
    val durationMs: Double,
    val sampleRateHz: Int,
    val requestedSampleRateHz: Int,
    val rmsDbfs: Double?,
    val clippedPercent: Double?,
    val noiseFloorDb: Double?,
    val snrDb: Double?,
    /** ";"-separated "METRIC=value" or "METRIC=U:REASON" entries, one per SpeechMetric. */
    val metrics: String,
    val algorithmVersion: String,
    val configSummary: String,
    val scoringVersion: String,
)

@Dao
interface SpeechDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: SpeechEntity)

    @Query("SELECT * FROM speech_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<SpeechEntity>>

    @Query("SELECT * FROM speech_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<SpeechEntity?>
}

private fun encodeMetrics(metrics: Map<SpeechMetric, SpeechValue>): String =
    SpeechMetric.entries.joinToString(";") { metric ->
        when (val v = metrics[metric]) {
            is SpeechValue.Measured -> "${metric.name}=${v.value}"
            is SpeechValue.Unavailable -> "${metric.name}=U:${v.reason.name}"
            null -> "${metric.name}=U:${UnavailableReason.NOT_APPLICABLE_TO_TASK.name}"
        }
    }

/** Unknown names or malformed entries (written by another version) are left out, never guessed. */
private fun decodeMetrics(text: String): Map<SpeechMetric, SpeechValue> {
    val out = LinkedHashMap<SpeechMetric, SpeechValue>()
    text.split(';').filter { it.contains('=') }.forEach { entry ->
        val (name, value) = entry.split('=', limit = 2)
        val metric = SpeechMetric.entries.firstOrNull { it.name == name } ?: return@forEach
        val decoded: SpeechValue? = if (value.startsWith("U:")) {
            UnavailableReason.entries.firstOrNull { it.name == value.removePrefix("U:") }?.let { SpeechValue.Unavailable(it) }
        } else {
            value.toDoubleOrNull()?.let { SpeechValue.Measured(it) }
        }
        if (decoded != null) out[metric] = decoded
    }
    return out
}

fun SpeechResult.toEntity(): SpeechEntity = SpeechEntity(
    assessmentId = assessmentId,
    timestampEpochMs = timestampEpochMs,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    sessionId = sessionId,
    task = task.id,
    plannedDurationMs = plannedDurationMs,
    consentAccepted = consentAccepted,
    qualityStatus = qualityStatus.name,
    qualityIssues = qualityIssues.joinToString(",") { it.name },
    qualityScore = qualityScore,
    durationMs = durationMs,
    sampleRateHz = sampleRateHz,
    requestedSampleRateHz = requestedSampleRateHz,
    rmsDbfs = rmsDbfs,
    clippedPercent = clippedPercent,
    noiseFloorDb = noiseFloorDb,
    snrDb = snrDb,
    metrics = encodeMetrics(metrics),
    algorithmVersion = algorithmVersion,
    configSummary = configSummary,
    scoringVersion = scoringVersion,
)

fun SpeechEntity.toDomain(): SpeechResult = SpeechResult(
    assessmentId = assessmentId,
    timestampEpochMs = timestampEpochMs,
    startEpochMs = startEpochMs,
    endEpochMs = endEpochMs,
    sessionId = sessionId,
    // Unknown task ids (written by a newer version) fall back to the sustained vowel label; see SpeechTask.fromId.
    task = SpeechTask.fromId(task) ?: SpeechTask.SUSTAINED_VOWEL,
    plannedDurationMs = plannedDurationMs,
    consentAccepted = consentAccepted,
    qualityStatus = speechEnumOrNull<QualityStatus>(qualityStatus) ?: QualityStatus.INVALID,
    qualityIssues = qualityIssues.split(',').mapNotNull { speechEnumOrNull<SpeechQualityIssue>(it) },
    qualityScore = qualityScore,
    durationMs = durationMs,
    sampleRateHz = sampleRateHz,
    requestedSampleRateHz = requestedSampleRateHz,
    rmsDbfs = rmsDbfs,
    clippedPercent = clippedPercent,
    noiseFloorDb = noiseFloorDb,
    snrDb = snrDb,
    metrics = decodeMetrics(metrics),
    algorithmVersion = algorithmVersion,
    configSummary = configSummary,
    scoringVersion = scoringVersion,
)

private inline fun <reified E : Enum<E>> speechEnumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }
