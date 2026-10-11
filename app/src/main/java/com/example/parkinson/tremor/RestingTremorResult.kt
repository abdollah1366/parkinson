package com.example.parkinson.tremor

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.raw.FrameStatus
import kotlin.math.roundToInt

/**
 * Timestamped raw palm data kept with the result, so every metric can be recalculated later. Times are
 * relative to the first frame (ms); positions are upright-image pixels (NaN when the frame was not VALID).
 * [frameStatuses] has one letter per frame (see [statusCode]).
 */
data class RestingTremorSeries(
    val frameTimesMs: List<Long>,
    val frameStatuses: String,
    val palmXPx: List<Double>,
    val palmYPx: List<Double>,
    val handScalePx: List<Double>,
) {
    companion object {
        val EMPTY = RestingTremorSeries(emptyList(), "", emptyList(), emptyList(), emptyList())

        fun statusCode(status: FrameStatus): Char = when (status) {
            FrameStatus.VALID -> 'V'
            FrameStatus.NO_HAND -> 'N'
            FrameStatus.MULTIPLE_HANDS -> 'M'
            FrameStatus.OUT_OF_FRAME -> 'O'
            FrameStatus.ERROR -> 'E'
        }

        fun statusFromCode(code: Char): FrameStatus = when (code) {
            'V' -> FrameStatus.VALID
            'M' -> FrameStatus.MULTIPLE_HANDS
            'O' -> FrameStatus.OUT_OF_FRAME
            'E' -> FrameStatus.ERROR
            else -> FrameStatus.NO_HAND
        }
    }
}

/**
 * Immutable result of one Resting Hand Tremor recording, as stored and shown. Measurements and data
 * quality only: no tremor score, no severity grade and no diagnosis. Units are given on each metric.
 */
data class RestingTremorResult(
    override val assessmentId: String,
    /** Wall-clock time the recording ended (ms since epoch). */
    override val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    override val hand: SelectedHand,
    val plannedDurationMs: Long,
    val metrics: RestingTremorMetrics,
    val qualityStatus: QualityStatus,
    val qualityIssues: List<RestingTremorQualityIssue>,
    /** Technical tracking share (valid frames, percent). Not a tremor score. */
    val qualityScore: Int,
    val series: RestingTremorSeries,
    override val algorithmVersion: String,
    /** [RestingTremorSignalConfig.summary] at analysis time. */
    val configSummary: String,
    override val scoringVersion: String,
) : AssessmentResult {

    override val type: AssessmentType get() = AssessmentType.RESTING_TREMOR
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, qualityScore)

    /** No performance index: this test has no validated scoring. */
    override val performanceIndex: Int? get() = null

    companion object {
        /** Builds a result from a usable analysis. Requires [RestingTremorAnalysis.metrics] to be present. */
        fun from(
            analysis: RestingTremorAnalysis,
            metrics: RestingTremorMetrics,
            frames: List<RestingTremorFrame>,
            assessmentId: String,
            startEpochMs: Long,
            endEpochMs: Long,
            hand: SelectedHand,
            plannedDurationMs: Long,
            signal: RestingTremorSignalConfig,
        ): RestingTremorResult {
            val origin = frames.firstOrNull()?.timestampMs ?: 0L
            return RestingTremorResult(
                assessmentId = assessmentId,
                timestampEpochMs = endEpochMs,
                startEpochMs = startEpochMs,
                endEpochMs = endEpochMs,
                hand = hand,
                plannedDurationMs = plannedDurationMs,
                metrics = metrics,
                qualityStatus = analysis.quality.status,
                qualityIssues = analysis.quality.issues,
                qualityScore = metrics.validFramePercent.roundToInt().coerceIn(0, 100),
                series = RestingTremorSeries(
                    frameTimesMs = frames.map { it.timestampMs - origin },
                    frameStatuses = frames.map { RestingTremorSeries.statusCode(it.status) }.joinToString(""),
                    palmXPx = frames.map { it.palmXPx },
                    palmYPx = frames.map { it.palmYPx },
                    handScalePx = frames.map { it.handScalePx },
                ),
                algorithmVersion = RestingTremorVersions.ALGORITHM_VERSION,
                configSummary = signal.summary(),
                scoringVersion = RestingTremorVersions.SCORING_VERSION,
            )
        }
    }
}
