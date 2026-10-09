package com.example.parkinson.speech

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import kotlin.math.roundToInt

/**
 * The stored result of one speech task. It holds the measurements (or their reasons), the recording's quality
 * findings, and the actual sample rate. It holds no audio and no transcript. Interpretation text is produced
 * by the UI from these fields and is always descriptive.
 */
data class SpeechResult(
    override val assessmentId: String,
    /** Wall-clock time the recording ended (ms since epoch). */
    override val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    /** Groups the results of one assessment visit (one task run per visit in this version). */
    val sessionId: String,
    val task: SpeechTask,
    val plannedDurationMs: Long,
    /** The person accepted the privacy notice and the recording consent before the recording. */
    val consentAccepted: Boolean,
    val qualityStatus: QualityStatus,
    val qualityIssues: List<SpeechQualityIssue>,
    /** Share of voiced frames (percent), 0..100: a technical share, not a score. */
    val qualityScore: Int,
    val durationMs: Double,
    val sampleRateHz: Int,
    val requestedSampleRateHz: Int,
    val rmsDbfs: Double?,
    val clippedPercent: Double?,
    val noiseFloorDb: Double?,
    val snrDb: Double?,
    val metrics: Map<SpeechMetric, SpeechValue>,
    override val algorithmVersion: String,
    val configSummary: String,
    override val scoringVersion: String,
) : AssessmentResult {

    override val type: AssessmentType get() = AssessmentType.SPEECH

    /** Speech is not tied to a selected hand. */
    override val hand: SelectedHand? get() = null
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, qualityScore)

    /** No performance index: no validated scoring exists for these measurements. */
    override val performanceIndex: Int? get() = null

    /** The measured value, or null when unavailable (never zero for an unavailable metric). */
    fun measured(metric: SpeechMetric): Double? = (metrics[metric] as? SpeechValue.Measured)?.value

    companion object {
        fun from(
            analysis: SpeechAnalysis,
            capture: AudioCapture,
            assessmentId: String,
            sessionId: String,
            startEpochMs: Long,
            endEpochMs: Long,
            consentAccepted: Boolean,
            signal: SpeechSignalConfig,
            plannedDurationMs: Long,
            algorithmVersion: String = SpeechVersions.ALGORITHM_VERSION,
            scoringVersion: String = SpeechVersions.SCORING_VERSION,
        ): SpeechResult {
            val q = analysis.quality
            val voiced = (analysis.metrics[SpeechMetric.VOICED_PERCENT] as? SpeechValue.Measured)?.value ?: 0.0
            return SpeechResult(
                assessmentId = assessmentId,
                timestampEpochMs = endEpochMs,
                startEpochMs = startEpochMs,
                endEpochMs = endEpochMs,
                sessionId = sessionId,
                task = analysis.task,
                plannedDurationMs = plannedDurationMs,
                consentAccepted = consentAccepted,
                qualityStatus = q.status,
                qualityIssues = q.issues,
                qualityScore = voiced.roundToInt().coerceIn(0, 100),
                durationMs = q.durationMs,
                sampleRateHz = capture.sampleRateHz,
                requestedSampleRateHz = capture.requestedSampleRateHz,
                rmsDbfs = q.rmsDbfs,
                clippedPercent = q.clippedPercent,
                noiseFloorDb = q.noiseFloorDb,
                snrDb = q.snrDb,
                metrics = analysis.metrics,
                algorithmVersion = algorithmVersion,
                configSummary = signal.summary(),
                scoringVersion = scoringVersion,
            )
        }
    }
}
