package com.example.parkinson.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.mediapipe.HandTrackingQuality
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.openclose.HandOpenCloseResult
import com.example.parkinson.openclose.OpenCloseFrameStats
import com.example.parkinson.openclose.OpenCloseMetrics
import com.example.parkinson.openclose.OpenCloseQualityIssue
import com.example.parkinson.openclose.OpenCloseSeries
import kotlinx.coroutines.flow.Flow
import java.util.Locale

/**
 * Stored Hand Opening/Closing result: the measurements, the quality, and the timestamped series
 * (cycles and per-frame openings with their statuses) needed to recalculate them later. No camera
 * images and no personal identifiers. Enums are stored by name.
 */
@Entity(tableName = "hand_open_close_assessments", indices = [Index("timestampEpochMs")])
data class HandOpenCloseEntity(
    @PrimaryKey val assessmentId: String,
    val timestampEpochMs: Long,
    val hand: String,
    val plannedDurationMs: Long,

    // --- metrics
    val measurementDurationMs: Long,
    val usableDurationMs: Long,
    val completedCycles: Int,
    val cycleRatePerSecond: Double?,
    val meanCycleDurationMs: Double?,
    val cycleDurationSdMs: Double?,
    val cycleDurationCvPercent: Double?,
    val meanIntervalMs: Double?,
    val intervalSdMs: Double?,
    val intervalCvPercent: Double?,
    val meanAmplitude: Double?,
    val amplitudeSd: Double?,
    val amplitudeCvPercent: Double?,
    val meanOpeningMs: Double?,
    val meanClosingMs: Double?,
    val partialOpenings: Int,
    val partialClosings: Int,
    val rejectedCandidates: Int,
    val incompleteFinalCycle: Boolean,
    val noiseToAmplitude: Double?,
    val recordingCompletenessPercent: Double,

    // --- frame statistics
    val totalFrames: Int,
    val validFrames: Int,
    val noHandFrames: Int,
    val multipleHandsFrames: Int,
    val outOfFrameFrames: Int,
    val errorFrames: Int,
    val sideMatchesFrames: Int,
    val sideUncertainFrames: Int,
    val sideMismatchFrames: Int,
    val fps: Double,
    val medianFrameIntervalMs: Double?,
    val dropoutCount: Int,
    val dropoutTotalMs: Long,
    val longestDropoutMs: Long,
    val observedDurationMs: Long,
    val trackingRate: Double,
    val meanLuma: Double?,
    val cameraFramesSkipped: Int,
    val pipelineFramesDropped: Int,

    // --- quality
    val qualityStatus: String,
    /** Comma-separated OpenCloseQualityIssue names. */
    val qualityIssues: String,
    val qualityScore: Int,
    /** Empty when not recorded (older or unavailable). */
    val cameraQuality: String,
    val handTrackingQuality: String,

    // --- timestamped series (comma-separated; statuses one letter per frame)
    val cycleEndTimesMs: String,
    val cycleDurationsMs: String,
    val cycleAmplitudes: String,
    val sampleTimesMs: String,
    val sampleOpenings: String,
    val sampleStatuses: String,

    val algorithmVersion: String,
    val scoringVersion: String
)

@Dao
interface HandOpenCloseDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: HandOpenCloseEntity)

    @Query("SELECT * FROM hand_open_close_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<HandOpenCloseEntity>>

    @Query("SELECT * FROM hand_open_close_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<HandOpenCloseEntity?>
}

private fun List<Double>.csv(): String = joinToString(",") { String.format(Locale.US, "%.4f", it) }
private fun String.doubles(): List<Double> = if (isBlank()) emptyList() else split(',').mapNotNull { it.toDoubleOrNull() }
private fun String.longs(): List<Long> = if (isBlank()) emptyList() else split(',').mapNotNull { it.toLongOrNull() }

private inline fun <reified E : Enum<E>> enumOrNull(name: String?): E? = enumValues<E>().firstOrNull { it.name == name }

fun HandOpenCloseResult.toEntity(): HandOpenCloseEntity {
    val m = metrics
    val f = m.frames
    val s = series
    return HandOpenCloseEntity(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        hand = hand.name,
        plannedDurationMs = plannedDurationMs,
        measurementDurationMs = m.measurementDurationMs,
        usableDurationMs = m.usableDurationMs,
        completedCycles = m.completedCycles,
        cycleRatePerSecond = m.cycleRatePerSecond,
        meanCycleDurationMs = m.meanCycleDurationMs,
        cycleDurationSdMs = m.cycleDurationSdMs,
        cycleDurationCvPercent = m.cycleDurationCvPercent,
        meanIntervalMs = m.meanIntervalMs,
        intervalSdMs = m.intervalSdMs,
        intervalCvPercent = m.intervalCvPercent,
        meanAmplitude = m.meanAmplitude,
        amplitudeSd = m.amplitudeSd,
        amplitudeCvPercent = m.amplitudeCvPercent,
        meanOpeningMs = m.meanOpeningMs,
        meanClosingMs = m.meanClosingMs,
        partialOpenings = m.partialOpenings,
        partialClosings = m.partialClosings,
        rejectedCandidates = m.rejectedCandidates,
        incompleteFinalCycle = m.incompleteFinalCycle,
        noiseToAmplitude = m.noiseToAmplitude,
        recordingCompletenessPercent = m.recordingCompletenessPercent,
        totalFrames = f.totalFrames,
        validFrames = f.validFrames,
        noHandFrames = f.noHandFrames,
        multipleHandsFrames = f.multipleHandsFrames,
        outOfFrameFrames = f.outOfFrameFrames,
        errorFrames = f.errorFrames,
        sideMatchesFrames = f.sideMatchesFrames,
        sideUncertainFrames = f.sideUncertainFrames,
        sideMismatchFrames = f.sideMismatchFrames,
        fps = f.fps,
        medianFrameIntervalMs = f.medianFrameIntervalMs,
        dropoutCount = f.dropoutCount,
        dropoutTotalMs = f.dropoutTotalMs,
        longestDropoutMs = f.longestDropoutMs,
        observedDurationMs = f.observedDurationMs,
        trackingRate = f.trackingRate,
        meanLuma = f.meanLuma?.toDouble(),
        cameraFramesSkipped = f.cameraFramesSkipped,
        pipelineFramesDropped = f.pipelineFramesDropped,
        qualityStatus = qualityStatus.name,
        qualityIssues = qualityIssues.joinToString(",") { it.name },
        qualityScore = qualityScore,
        cameraQuality = cameraQuality?.name.orEmpty(),
        handTrackingQuality = handTrackingQuality?.name.orEmpty(),
        cycleEndTimesMs = s.cycleEndTimesMs.joinToString(","),
        cycleDurationsMs = s.cycleDurationsMs.joinToString(","),
        cycleAmplitudes = s.cycleAmplitudes.csv(),
        sampleTimesMs = s.sampleTimesMs.joinToString(","),
        sampleOpenings = s.sampleOpenings.csv(),
        sampleStatuses = s.sampleStatuses,
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion
    )
}

fun HandOpenCloseEntity.toDomain(): HandOpenCloseResult {
    val frames = OpenCloseFrameStats(
        totalFrames = totalFrames,
        validFrames = validFrames,
        noHandFrames = noHandFrames,
        multipleHandsFrames = multipleHandsFrames,
        outOfFrameFrames = outOfFrameFrames,
        errorFrames = errorFrames,
        sideMatchesFrames = sideMatchesFrames,
        sideUncertainFrames = sideUncertainFrames,
        sideMismatchFrames = sideMismatchFrames,
        fps = fps,
        medianFrameIntervalMs = medianFrameIntervalMs,
        dropoutCount = dropoutCount,
        dropoutTotalMs = dropoutTotalMs,
        longestDropoutMs = longestDropoutMs,
        usableDurationMs = usableDurationMs,
        observedDurationMs = observedDurationMs,
        trackingRate = trackingRate,
        meanLuma = meanLuma?.toFloat(),
        cameraFramesSkipped = cameraFramesSkipped,
        pipelineFramesDropped = pipelineFramesDropped
    )
    val metrics = OpenCloseMetrics(
        plannedDurationMs = plannedDurationMs,
        measurementDurationMs = measurementDurationMs,
        usableDurationMs = usableDurationMs,
        completedCycles = completedCycles,
        cycleRatePerSecond = cycleRatePerSecond,
        meanCycleDurationMs = meanCycleDurationMs,
        cycleDurationSdMs = cycleDurationSdMs,
        cycleDurationCvPercent = cycleDurationCvPercent,
        meanIntervalMs = meanIntervalMs,
        intervalSdMs = intervalSdMs,
        intervalCvPercent = intervalCvPercent,
        meanAmplitude = meanAmplitude,
        amplitudeSd = amplitudeSd,
        amplitudeCvPercent = amplitudeCvPercent,
        meanOpeningMs = meanOpeningMs,
        meanClosingMs = meanClosingMs,
        partialOpenings = partialOpenings,
        partialClosings = partialClosings,
        rejectedCandidates = rejectedCandidates,
        incompleteFinalCycle = incompleteFinalCycle,
        noiseToAmplitude = noiseToAmplitude,
        recordingCompletenessPercent = recordingCompletenessPercent,
        frames = frames
    )
    return HandOpenCloseResult(
        assessmentId = assessmentId,
        timestampEpochMs = timestampEpochMs,
        // Unknown names (e.g. written by a newer app version) degrade to the safe side.
        hand = enumOrNull<SelectedHand>(hand) ?: SelectedHand.RIGHT,
        plannedDurationMs = plannedDurationMs,
        metrics = metrics,
        qualityStatus = enumOrNull<QualityStatus>(qualityStatus) ?: QualityStatus.INVALID,
        qualityIssues = qualityIssues.split(',').mapNotNull { enumOrNull<OpenCloseQualityIssue>(it) },
        qualityScore = qualityScore,
        cameraQuality = enumOrNull<CameraQuality>(cameraQuality),
        handTrackingQuality = enumOrNull<HandTrackingQuality>(handTrackingQuality),
        series = OpenCloseSeries(
            cycleEndTimesMs = cycleEndTimesMs.longs(),
            cycleDurationsMs = cycleDurationsMs.longs(),
            cycleAmplitudes = cycleAmplitudes.doubles(),
            sampleTimesMs = sampleTimesMs.longs(),
            sampleOpenings = sampleOpenings.doubles(),
            sampleStatuses = sampleStatuses
        ),
        algorithmVersion = algorithmVersion,
        scoringVersion = scoringVersion
    )
}
