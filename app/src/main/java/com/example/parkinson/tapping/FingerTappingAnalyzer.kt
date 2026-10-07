package com.example.parkinson.tapping

import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.detection.DetectionConfig
import com.example.parkinson.tapping.detection.DetectionResult
import com.example.parkinson.tapping.detection.TapDetector
import com.example.parkinson.tapping.metrics.FrameStatisticsCalculator
import com.example.parkinson.tapping.metrics.MotorMetrics
import com.example.parkinson.tapping.metrics.MotorMetricsCalculator
import com.example.parkinson.tapping.quality.QualityAssessor
import com.example.parkinson.tapping.quality.QualityReport
import com.example.parkinson.tapping.quality.QualityThresholds
import com.example.parkinson.tapping.raw.TapFrame
import com.example.parkinson.tapping.result.FingerTappingAssessment
import com.example.parkinson.tapping.scoring.PerformanceScore
import com.example.parkinson.tapping.scoring.PerformanceScorer
import com.example.parkinson.tapping.scoring.ScoringConfig
import com.example.parkinson.tapping.signal.ProcessedSignal
import com.example.parkinson.tapping.signal.SignalConfig
import com.example.parkinson.tapping.signal.TapSignalProcessor

/** Everything captured during RECORDING. Frames are raw; nothing is pre-processed. */
data class TapRecording(
    val frames: List<TapFrame>,
    val startMs: Long,
    val endMs: Long,
    val plannedDurationMs: Long,
    val hand: SelectedHand
)

/** All intermediate layers of one analysis, kept separate for testing and review. */
data class FingerTappingAnalysis(
    val signal: ProcessedSignal,
    val detection: DetectionResult,
    val metrics: MotorMetrics,
    val quality: QualityReport,
    val score: PerformanceScore?
)

/**
 * Runs RAW DATA -> SIGNAL PROCESSING -> EVENT DETECTION -> METRICS -> QUALITY CONTROL -> SCORING.
 * Deterministic: the same recording always gives the same analysis.
 */
class FingerTappingAnalyzer(
    private val signalConfig: SignalConfig = SignalConfig(),
    private val detectionConfig: DetectionConfig = DetectionConfig(),
    private val qualityThresholds: QualityThresholds = QualityThresholds(),
    private val scoringConfig: ScoringConfig = ScoringConfig()
) {

    fun analyze(recording: TapRecording): FingerTappingAnalysis {
        val inWindow = recording.frames.filter { it.timestampMs in recording.startMs..recording.endMs }
        val signal = TapSignalProcessor.process(inWindow, signalConfig)
        val detection = TapDetector.detect(signal, detectionConfig)
        val frameStats = FrameStatisticsCalculator.compute(inWindow, recording.startMs, recording.endMs)
        val metrics = MotorMetricsCalculator.compute(
            detection, frameStats, recording.startMs, recording.endMs, recording.plannedDurationMs
        )
        val quality = QualityAssessor.assess(metrics, signal, qualityThresholds)
        val score = PerformanceScorer.score(metrics, quality, scoringConfig)
        return FingerTappingAnalysis(signal, detection, metrics, quality, score)
    }

    companion object {
        fun toAssessment(
            analysis: FingerTappingAnalysis,
            assessmentId: String,
            timestampEpochMs: Long,
            hand: SelectedHand
        ): FingerTappingAssessment {
            val m = analysis.metrics
            val q = analysis.quality
            return FingerTappingAssessment(
                assessmentId = assessmentId,
                timestampEpochMs = timestampEpochMs,
                hand = hand,
                plannedDurationMs = m.plannedDurationMs,
                recordingDurationMs = m.recordingDurationMs,
                tapCount = m.tapCount,
                tapRateHz = m.tapRateHz,
                tapsPer10Seconds = m.tapsPer10Seconds,
                meanIntervalMs = m.meanIntervalMs,
                medianIntervalMs = m.medianIntervalMs,
                intervalSdMs = m.intervalSdMs,
                intervalCvPercent = m.intervalCvPercent,
                tapToTapVariabilityPercent = m.tapToTapVariabilityPercent,
                pauseCount = m.pauseCount,
                meanAmplitude = m.meanAmplitude,
                medianAmplitude = m.medianAmplitude,
                amplitudeSd = m.amplitudeSd,
                amplitudeCvPercent = m.amplitudeCvPercent,
                minAmplitude = m.minAmplitude,
                maxAmplitude = m.maxAmplitude,
                meanTapDurationMs = m.meanTapDurationMs,
                amplitudeEarly = m.amplitudeTrend.early,
                amplitudeMiddle = m.amplitudeTrend.middle,
                amplitudeLate = m.amplitudeTrend.late,
                amplitudeTrendPercent = m.amplitudeTrend.relativeChangePercent,
                rateEarlyHz = m.rateTrend.early,
                rateLateHz = m.rateTrend.late,
                rateTrendPercent = m.rateTrend.relativeChangePercent,
                movementConsistencyPercent = m.movementConsistencyPercent,
                fps = m.frames.fps,
                validFramePercent = m.frames.validFramePercent,
                dropoutCount = m.frames.dropoutCount,
                dropoutDurationMs = m.frames.dropoutTotalMs,
                longestDropoutMs = m.frames.longestDropoutMs,
                recordingCompletenessPercent = m.recordingCompletenessPercent,
                qualityStatus = q.status,
                qualityIssues = q.issues,
                qualityScore = q.qualityScore,
                performanceScore = analysis.score,
                algorithmVersion = FingerTappingVersions.ALGORITHM_VERSION,
                scoringVersion = FingerTappingVersions.SCORING_VERSION
            )
        }
    }
}
