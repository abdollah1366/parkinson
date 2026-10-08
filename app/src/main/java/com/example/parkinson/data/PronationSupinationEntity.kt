package com.example.parkinson.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.pronation.InterpretationNote
import com.example.parkinson.pronation.InternalSoftwareReference
import com.example.parkinson.pronation.MeasureTrends
import com.example.parkinson.pronation.MetricTrend
import com.example.parkinson.assessment.MotorPerformanceBand
import com.example.parkinson.assessment.PerformanceTrendState
import com.example.parkinson.pronation.PronationQualityIssue
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.pronation.TrendDirection
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * Stored Pronation/Supination result: derived measurements only, never the raw sensor stream.
 * Enums are stored by name.
 *
 * The table was created in DB v3 (development algorithm "ps-algo-1.0.0"). v4 only ADDS columns
 * (with defaults), so existing rows survive the automatic migration. Columns from v3 are reused
 * where the meaning is the same (e.g. cycleRateHz = cycles per second, scoreRhythm = regularity);
 * movementConsistency, scoreVelocity and scoreDataQuality are v3-only and written as null.
 * Trend columns: amplitude (amp*), peak velocity (vel*), cycle duration (dur*) per third.
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
    /** Cycles per second. */
    val cycleRateHz: Double,
    val meanCycleDurationMs: Double?,
    val medianCycleDurationMs: Double?,
    val cycleVariability: Double?,
    val angularVelocityMean: Double,
    val angularVelocityPeak: Double?,
    val angularVelocityVariability: Double?,
    val angularVelocityRms: Double,
    /** Median amplitude. */
    val movementAmplitude: Double?,
    val amplitudeVariability: Double?,
    /** v3 only. */
    val movementConsistency: Double?,
    val pauseCount: Int,
    /** Total pause duration. */
    val pauseDurationMs: Long,
    /** 0 when the accelerometer was not used (see [accelerometerAvailable]). */
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
    /** Gyroscope (primary) sampling rate. */
    val effectiveSamplingRate: Double,
    /** 0 when the accelerometer was not used. */
    val accSamplingRateHz: Double,
    val validSamplePercentage: Double,
    val dropoutCount: Int,
    val completenessPercent: Double,
    val qualityStatus: String,
    /** Comma-separated PronationQualityIssue names. */
    val qualityIssues: String,
    /** Quality percentage. */
    val qualityScore: Int,
    /** Motor performance index. */
    val scoreTotal: Int?,
    /** Speed component. */
    val scoreRate: Int?,
    /** Rhythm = regularity component. */
    val scoreRhythm: Int?,
    val scoreAmplitude: Int?,
    /** v3 only. */
    val scoreVelocity: Int?,
    val scoreConsistency: Int?,
    /** v3 only. */
    val scoreDataQuality: Int?,
    val algorithmVersion: String,
    val scoringVersion: String,

    // --- added in v4 -------------------------------------------------------------------------
    @ColumnInfo(defaultValue = "") val sessionId: String = "",
    @ColumnInfo(defaultValue = "0") val cyclesPerMinute: Double = 0.0,
    val meanAmplitude: Double? = null,
    val maxAngularVelocity: Double? = null,
    @ColumnInfo(defaultValue = "0") val longestPauseMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val movementCoveragePercent: Double = 0.0,
    @ColumnInfo(defaultValue = "0") val noiseLevelDegS: Double = 0.0,
    val scoreTrend: Int? = null,
    val earlyScore: Int? = null,
    val middleScore: Int? = null,
    val lateScore: Int? = null,
    @ColumnInfo(defaultValue = "INSUFFICIENT_DATA") val performanceTrend: String = PerformanceTrendState.INSUFFICIENT_DATA.name,
    @ColumnInfo(defaultValue = "1") val accelerometerAvailable: Boolean = true,
    @ColumnInfo(defaultValue = "0") val estimatedMissingSamples: Int = 0,
    @ColumnInfo(defaultValue = "0") val longestGapMs: Long = 0,
    val interpretationBand: String? = null,
    /** Empty in v3 rows: derived from quality when read. */
    @ColumnInfo(defaultValue = "") val reliability: String = "",
    /** Comma-separated InterpretationNote names. */
    @ColumnInfo(defaultValue = "") val interpretationNotes: String = "",
    /** Comma-separated deg/s values (one decimal). */
    @ColumnInfo(defaultValue = "") val velocityTrace: String = "",
    @ColumnInfo(defaultValue = "0") val traceHz: Double = 0.0,
    @ColumnInfo(defaultValue = "") val referenceName: String = ""
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
    val tr = measureTrends
    return PronationSupinationEntity(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        hand = hand.name,
        plannedDurationMs = plannedDurationMs,
        durationMs = durationMs,
        cycleCount = cycleCount,
        validCycleCount = validCycleCount,
        cycleRateHz = cyclesPerSecond,
        meanCycleDurationMs = meanCycleDurationMs,
        medianCycleDurationMs = medianCycleDurationMs,
        cycleVariability = cycleDurationVariability,
        angularVelocityMean = meanAngularVelocity,
        angularVelocityPeak = peakAngularVelocity,
        angularVelocityVariability = velocityVariability,
        angularVelocityRms = angularVelocityRms,
        movementAmplitude = medianAmplitude,
        amplitudeVariability = amplitudeVariability,
        movementConsistency = null,
        pauseCount = pauseCount,
        pauseDurationMs = totalPauseDurationMs,
        accelerationRms = accelerationRms ?: 0.0,
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
        effectiveSamplingRate = samplingRate,
        accSamplingRateHz = accSamplingRate ?: 0.0,
        validSamplePercentage = validSamplePercentage,
        dropoutCount = dropoutCount,
        completenessPercent = completenessPercent,
        qualityStatus = qualityStatus.name,
        qualityIssues = qualityIssues.joinToString(",") { it.name },
        qualityScore = qualityPercentage,
        scoreTotal = performanceScore,
        scoreRate = speedScore,
        scoreRhythm = regularityScore,
        scoreAmplitude = amplitudeScore,
        scoreVelocity = null,
        scoreConsistency = consistencyScore,
        scoreDataQuality = null,
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion,
        sessionId = sessionId,
        cyclesPerMinute = cyclesPerMinute,
        meanAmplitude = meanAmplitude,
        maxAngularVelocity = maxAngularVelocity,
        longestPauseMs = longestPauseMs,
        movementCoveragePercent = movementCoveragePercent,
        noiseLevelDegS = noiseLevelDegS,
        scoreTrend = trendScore,
        earlyScore = earlyScore,
        middleScore = middleScore,
        lateScore = lateScore,
        performanceTrend = performanceTrend.name,
        accelerometerAvailable = accelerometerAvailable,
        estimatedMissingSamples = estimatedMissingSamples,
        longestGapMs = longestGapMs,
        interpretationBand = interpretationBand?.name,
        reliability = reliability.name,
        interpretationNotes = interpretationNotes.joinToString(",") { it.name },
        velocityTrace = velocityTrace.joinToString(",") { String.format(Locale.US, "%.1f", it) },
        traceHz = traceHz,
        referenceName = referenceName
    )
}

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

private inline fun <reified E : Enum<E>> enumList(csv: String): List<E> =
    csv.split(',').mapNotNull { n -> enumValues<E>().firstOrNull { it.name == n } }

fun PronationSupinationEntity.toDomain(): PronationSupinationResult {
    // Unknown names (e.g. written by a newer app version) degrade to the safe side.
    val quality = enumOrNull<QualityStatus>(qualityStatus) ?: QualityStatus.INVALID
    val score = scoreTotal?.takeIf { it in 0..100 }
    // v3 rows have no stored reliability / band: derive them from quality and score.
    val storedReliability = enumOrNull<ReliabilityLevel>(reliability)
    val derivedReliability = when {
        score == null -> ReliabilityLevel.NOT_RELIABLE
        quality == QualityStatus.VALID -> ReliabilityLevel.RELIABLE
        quality == QualityStatus.LOW_QUALITY -> ReliabilityLevel.LIMITED
        else -> ReliabilityLevel.NOT_RELIABLE
    }
    fun trend(e: Double?, m: Double?, l: Double?, c: Double?, d: String?) =
        MetricTrend(e, m, l, c, enumOrNull<TrendDirection>(d))
    return PronationSupinationResult(
        assessmentId = assessmentId,
        sessionId = sessionId,
        timestampEpochMs = timestampEpochMs,
        hand = enumOrNull<SelectedHand>(hand) ?: SelectedHand.RIGHT,
        plannedDurationMs = plannedDurationMs,
        durationMs = durationMs,
        cycleCount = cycleCount,
        validCycleCount = validCycleCount,
        cyclesPerSecond = cycleRateHz,
        cyclesPerMinute = if (cyclesPerMinute > 0) cyclesPerMinute else cycleRateHz * 60.0,
        meanCycleDurationMs = meanCycleDurationMs,
        medianCycleDurationMs = medianCycleDurationMs,
        cycleDurationVariability = cycleVariability,
        meanAmplitude = meanAmplitude,
        medianAmplitude = movementAmplitude,
        amplitudeVariability = amplitudeVariability,
        meanAngularVelocity = angularVelocityMean,
        peakAngularVelocity = angularVelocityPeak,
        maxAngularVelocity = maxAngularVelocity,
        velocityVariability = angularVelocityVariability,
        angularVelocityRms = angularVelocityRms,
        pauseCount = pauseCount,
        totalPauseDurationMs = pauseDurationMs,
        longestPauseMs = longestPauseMs,
        movementCoveragePercent = movementCoveragePercent,
        noiseLevelDegS = noiseLevelDegS,
        accelerationRms = accelerationRms.takeIf { accelerometerAvailable },
        dominantFrequencyHz = dominantFrequencyHz,
        rotationAxisSharePercent = rotationAxisSharePercent,
        speedScore = scoreRate,
        regularityScore = scoreRhythm,
        amplitudeScore = scoreAmplitude,
        consistencyScore = scoreConsistency,
        trendScore = scoreTrend,
        earlyScore = earlyScore,
        middleScore = middleScore,
        lateScore = lateScore,
        performanceScore = score,
        performanceTrend = enumOrNull<PerformanceTrendState>(performanceTrend) ?: PerformanceTrendState.INSUFFICIENT_DATA,
        measureTrends = MeasureTrends(
            amplitudeDeg = trend(ampEarly, ampMiddle, ampLate, ampChangePercent, ampDirection),
            peakVelocityDegS = trend(velEarly, velMiddle, velLate, velChangePercent, velDirection),
            cycleDurationMs = trend(durEarly, durMiddle, durLate, durChangePercent, durDirection)
        ),
        qualityStatus = quality,
        qualityIssues = enumList(qualityIssues),
        qualityPercentage = qualityScore,
        validSamplePercentage = validSamplePercentage,
        samplingRate = effectiveSamplingRate,
        accSamplingRate = accSamplingRateHz.takeIf { accelerometerAvailable },
        accelerometerAvailable = accelerometerAvailable,
        dropoutCount = dropoutCount,
        estimatedMissingSamples = estimatedMissingSamples,
        longestGapMs = longestGapMs,
        completenessPercent = completenessPercent,
        interpretationBand = enumOrNull<MotorPerformanceBand>(interpretationBand) ?: score?.let { MotorPerformanceBand.forScore(it) },
        reliability = storedReliability ?: derivedReliability,
        interpretationNotes = enumList<InterpretationNote>(interpretationNotes),
        velocityTrace = if (velocityTrace.isBlank()) emptyList() else velocityTrace.split(',').mapNotNull { it.toFloatOrNull() },
        traceHz = traceHz,
        referenceName = referenceName.ifEmpty { InternalSoftwareReference.name },
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion
    )
}
