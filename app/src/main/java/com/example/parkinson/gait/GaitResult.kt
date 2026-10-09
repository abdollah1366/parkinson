package com.example.parkinson.gait

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import kotlin.math.roundToInt

/**
 * Timestamped pose data kept with the result, so the metrics can be recalculated later. Times are relative
 * to the first frame (ms). [frameStatuses] has one letter per frame (see [statusCode]); the ankle separation
 * is NaN for frames that were not VALID.
 */
data class GaitSeries(
    val frameTimesMs: List<Long>,
    val frameStatuses: String,
    val ankleSeparationTorso: List<Double>,
) {
    companion object {
        val EMPTY = GaitSeries(emptyList(), "", emptyList())

        fun statusCode(status: PoseFrameStatus): Char = when (status) {
            PoseFrameStatus.VALID -> 'V'
            PoseFrameStatus.NO_POSE -> 'N'
            PoseFrameStatus.INCOMPLETE -> 'I'
            PoseFrameStatus.TOO_SMALL -> 'S'
            PoseFrameStatus.ERROR -> 'E'
        }

        fun statusFromCode(code: Char): PoseFrameStatus = when (code) {
            'V' -> PoseFrameStatus.VALID
            'I' -> PoseFrameStatus.INCOMPLETE
            'S' -> PoseFrameStatus.TOO_SMALL
            'E' -> PoseFrameStatus.ERROR
            else -> PoseFrameStatus.NO_POSE
        }
    }
}

/**
 * Immutable result of one walking recording, as stored and shown. Measurements and data quality only:
 * no gait score, no severity and no diagnosis. Plantar pressure is never measured by this test (no
 * hardware), so [plantarPressureMeasured] is always false.
 */
data class GaitResult(
    override val assessmentId: String,
    /** Wall-clock time the recording ended (ms since epoch). */
    override val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val plannedDurationMs: Long,
    val metrics: GaitMetrics,
    val qualityStatus: QualityStatus,
    val qualityIssues: List<GaitQualityIssue>,
    /** Technical pose-tracking share (valid frames, percent). Not a gait score. */
    val qualityScore: Int,
    val series: GaitSeries,
    override val algorithmVersion: String,
    /** [GaitSignalConfig.summary] at analysis time. */
    val configSummary: String,
    override val scoringVersion: String,
) : AssessmentResult {

    override val type: AssessmentType get() = AssessmentType.GAIT

    /** Gait is not tied to a selected hand. */
    override val hand: SelectedHand? get() = null
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, qualityScore)

    /** No performance index: this test has no validated scoring. */
    override val performanceIndex: Int? get() = null

    /** Plantar-pressure hardware is not connected to this test. Stored explicitly so no reader infers a value. */
    val plantarPressureMeasured: Boolean get() = false

    companion object {
        fun from(
            analysis: GaitAnalysis,
            metrics: GaitMetrics,
            frames: List<PoseFrame>,
            assessmentId: String,
            startEpochMs: Long,
            endEpochMs: Long,
            plannedDurationMs: Long,
            signal: GaitSignalConfig,
        ): GaitResult {
            val origin = frames.firstOrNull()?.timestampMs ?: 0L
            return GaitResult(
                assessmentId = assessmentId,
                timestampEpochMs = endEpochMs,
                startEpochMs = startEpochMs,
                endEpochMs = endEpochMs,
                plannedDurationMs = plannedDurationMs,
                metrics = metrics,
                qualityStatus = analysis.quality.status,
                qualityIssues = analysis.quality.issues,
                qualityScore = metrics.validFramePercent.roundToInt().coerceIn(0, 100),
                series = GaitSeries(
                    frameTimesMs = frames.map { it.timestampMs - origin },
                    frameStatuses = frames.map { GaitSeries.statusCode(it.status) }.joinToString(""),
                    ankleSeparationTorso = frames.map { it.features?.ankleSeparationTorso ?: Double.NaN },
                ),
                algorithmVersion = GaitVersions.ALGORITHM_VERSION,
                configSummary = signal.summary(),
                scoringVersion = GaitVersions.SCORING_VERSION,
            )
        }
    }
}
