package com.example.parkinson.openclose

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.mediapipe.HandTrackingQuality
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.raw.FrameStatus
import kotlin.math.roundToLong

/**
 * Timestamped series kept with the result so that every metric can be recalculated later.
 * Times are relative to the start of the recording (ms). Openings are rounded to 4 decimals so the
 * stored payload reads back identically. [sampleStatuses] has one letter per frame (see [statusCode]).
 */
data class OpenCloseSeries(
    val cycleEndTimesMs: List<Long>,
    val cycleDurationsMs: List<Long>,
    val cycleAmplitudes: List<Double>,
    val sampleTimesMs: List<Long>,
    /** Raw opening of every frame; NaN when the frame had no usable landmarks. */
    val sampleOpenings: List<Double>,
    val sampleStatuses: String
) {
    companion object {
        val EMPTY = OpenCloseSeries(emptyList(), emptyList(), emptyList(), emptyList(), emptyList(), "")

        /** One letter per frame status, stored in [OpenCloseSeries.sampleStatuses]. */
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
 * Immutable result of one Hand Opening/Closing recording, as stored and shown. Measurements and
 * quality only: there is no performance score and no clinical interpretation. Units: ms, finger extension (0..1, unitless),
 * Hz for the cycle rate.
 */
data class HandOpenCloseResult(
    override val assessmentId: String,
    override val timestampEpochMs: Long,
    override val hand: SelectedHand,
    val plannedDurationMs: Long,
    val metrics: OpenCloseMetrics,
    val qualityStatus: QualityStatus,
    val qualityIssues: List<OpenCloseQualityIssue>,
    val qualityScore: Int,
    val cameraQuality: CameraQuality?,
    val handTrackingQuality: HandTrackingQuality?,
    val series: OpenCloseSeries,
    override val algorithmVersion: String,
    override val scoringVersion: String = HandOpenCloseVersions.SCORING_VERSION
) : AssessmentResult {
    override val type: AssessmentType get() = AssessmentType.HAND_OPEN_CLOSE
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, qualityScore)

    /** No 0..100 performance index in this version (see [HandOpenCloseVersions.SCORING_VERSION]). */
    override val performanceIndex: Int? get() = null

    companion object {
        private fun round4(v: Double) = (v * 10_000.0).roundToLong() / 10_000.0

        /** Builds the stored result from an analysis of a usable recording. */
        fun from(
            analysis: OpenCloseAnalysis,
            assessmentId: String,
            timestampEpochMs: Long,
            hand: SelectedHand,
            plannedMs: Long,
            quality: OpenCloseQualityReport = analysis.quality
        ): HandOpenCloseResult {
            val start = analysis.startMs
            val series = OpenCloseSeries(
                cycleEndTimesMs = analysis.detection.cycles.map { it.endMs - start },
                cycleDurationsMs = analysis.detection.cycles.map { it.durationMs },
                cycleAmplitudes = analysis.detection.cycles.map { round4(it.amplitude) },
                sampleTimesMs = analysis.frames.map { it.timestampMs - start },
                sampleOpenings = analysis.frames.map { if (it.isValid) round4(it.opening) else Double.NaN },
                sampleStatuses = analysis.frames.map { OpenCloseSeries.statusCode(it.status) }.joinToString("")
            )
            return HandOpenCloseResult(
                assessmentId = assessmentId,
                timestampEpochMs = timestampEpochMs,
                hand = hand,
                plannedDurationMs = plannedMs,
                metrics = analysis.metrics,
                qualityStatus = quality.status,
                qualityIssues = quality.issues,
                qualityScore = quality.qualityScore,
                cameraQuality = quality.cameraQuality,
                handTrackingQuality = quality.handTrackingQuality,
                series = series,
                algorithmVersion = HandOpenCloseVersions.ALGORITHM_VERSION
            )
        }
    }
}
