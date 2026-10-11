package com.example.parkinson.tremor

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand

object RestingTremorImuVersions {
    /** Version of [RestingTremorImuEngine]. Bump on any change to its features or quality gates. */
    const val ALGORITHM_VERSION = "rt-imu-algo-1.0.0"

    /** No tremor score: phone-motion features and data quality only. */
    const val SCORING_VERSION = "not-scored"
}

/**
 * One phone-motion recording of the resting-hand test, stored as its own assessment.
 *
 * [qualityStatus] is the acquisition and signal quality of the accelerometer stream (plus the gyroscope check).
 * [metrics] is null when the recording could not be measured, and then none of its features are shown. No score
 * is computed, so [quality] carries no score. [gyroscopePresent] says whether the device has a gyroscope;
 * [gyroscopeUsed] says whether its data was analysed (gyroscope-dependent features need both).
 */
data class RestingTremorImuResult(
    override val assessmentId: String,
    /** Wall-clock time the recording ended (ms since epoch). */
    override val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    override val hand: SelectedHand,
    val plannedDurationMs: Long,
    val gyroscopePresent: Boolean,
    val qualityStatus: QualityStatus,
    val qualityIssues: List<ImuTremorQualityIssue>,
    val metrics: ImuTremorMetrics?,
    override val algorithmVersion: String = RestingTremorImuVersions.ALGORITHM_VERSION,
    override val scoringVersion: String = RestingTremorImuVersions.SCORING_VERSION,
    val configSummary: String,
) : AssessmentResult {

    override val type: AssessmentType get() = AssessmentType.RESTING_TREMOR

    /** Technical quality only: no score. */
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, null)

    override val performanceIndex: Int? get() = null

    /** True when the stored features may be shown (VALID or LOW_QUALITY with measurements). */
    val isUsable: Boolean get() = metrics != null && (qualityStatus == QualityStatus.VALID || qualityStatus == QualityStatus.LOW_QUALITY)

    /** True when the gyroscope data was analysed. */
    val gyroscopeUsed: Boolean get() = metrics?.gyroSamples != null

    companion object {
        fun from(
            analysis: ImuTremorAnalysis,
            assessmentId: String,
            startEpochMs: Long,
            endEpochMs: Long,
            hand: SelectedHand,
            plannedDurationMs: Long,
            gyroscopePresent: Boolean,
            config: RestingTremorImuConfig,
        ): RestingTremorImuResult = RestingTremorImuResult(
            assessmentId = assessmentId,
            timestampEpochMs = endEpochMs,
            startEpochMs = startEpochMs,
            endEpochMs = endEpochMs,
            hand = hand,
            plannedDurationMs = plannedDurationMs,
            gyroscopePresent = gyroscopePresent,
            qualityStatus = analysis.quality.status,
            qualityIssues = analysis.quality.issues,
            metrics = analysis.metrics,
            configSummary = configSummary(config),
        )

        fun configSummary(c: RestingTremorImuConfig): String =
            "grid=${c.gridHz}Hz;band=${c.bandMinHz}-${c.bandMaxHz}Hz;detrend=${c.detrendHalfWindowMs * 2}ms;" +
                "minRate=${c.minRateHz}Hz;maxGap=${c.maxGapMs}ms;prom=${c.minPeakProminence};cycles=${c.minCycles}"
    }
}
