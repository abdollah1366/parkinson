package com.example.parkinson.tapping

import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.detection.DetectionConfig
import com.example.parkinson.tapping.detection.DetectionResult
import com.example.parkinson.tapping.detection.TapDetector
import com.example.parkinson.tapping.metrics.FrameStatisticsCalculator
import com.example.parkinson.tapping.metrics.MotorMetrics
import com.example.parkinson.tapping.metrics.MotorMetricsCalculator
import com.example.parkinson.tapping.quality.FingerTappingQualityEngine
import com.example.parkinson.tapping.quality.QualityReport
import com.example.parkinson.tapping.quality.QualityThresholds
import com.example.parkinson.tapping.raw.TapFrame
import com.example.parkinson.tapping.result.FingerTappingAssessment
import com.example.parkinson.tapping.result.TapPayload
import com.example.parkinson.tapping.scoring.FingerTappingInterpreter
import com.example.parkinson.tapping.scoring.FingerTappingScoreEngine
import com.example.parkinson.tapping.scoring.PerformanceScore
import com.example.parkinson.tapping.scoring.ScoringConfig
import com.example.parkinson.tapping.scoring.TappingInterpretation
import com.example.parkinson.tapping.signal.ProcessedSignal
import com.example.parkinson.tapping.signal.SignalConfig
import com.example.parkinson.tapping.signal.TapSignalProcessor
import kotlin.math.roundToLong

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
    val score: PerformanceScore?,
    val interpretation: TappingInterpretation
)

/**
 * Runs RAW DATA -> SIGNAL PROCESSING -> TAP EVENTS -> METRICS -> QUALITY (layers) -> SCORE ->
 * INTERPRETATION. Deterministic: the same recording always gives the same analysis. The live tap
 * count during recording runs [detectTaps] on the frames received so far: the same pipeline,
 * so there is one source of tap events.
 */
class FingerTappingAnalyzer(
    private val signalConfig: SignalConfig = SignalConfig(),
    private val detectionConfig: DetectionConfig = DetectionConfig(),
    private val qualityThresholds: QualityThresholds = QualityThresholds(),
    scoringConfig: ScoringConfig = ScoringConfig()
) {
    private val scoreEngine = FingerTappingScoreEngine(scoringConfig)

    /** Tap events of the given frames (signal processing + detection only). */
    fun detectTaps(frames: List<TapFrame>): DetectionResult =
        TapDetector.detect(TapSignalProcessor.process(frames, signalConfig), detectionConfig)

    fun analyze(recording: TapRecording): FingerTappingAnalysis {
        val inWindow = recording.frames.filter { it.timestampMs in recording.startMs..recording.endMs }
        val signal = TapSignalProcessor.process(inWindow, signalConfig)
        val detection = TapDetector.detect(signal, detectionConfig)
        val frameStats = FrameStatisticsCalculator.compute(inWindow, recording.startMs, recording.endMs)
        val metrics = MotorMetricsCalculator.compute(
            detection, frameStats, recording.startMs, recording.endMs, recording.plannedDurationMs
        )
        val quality = FingerTappingQualityEngine.assess(metrics, signal, qualityThresholds)
        val score = scoreEngine.score(metrics, quality)
        val interpretation = FingerTappingInterpreter.interpret(score, quality)
        return FingerTappingAnalysis(signal, detection, metrics, quality, score, interpretation)
    }

    companion object {
        private fun round4(v: Double) = (v * 10_000.0).roundToLong() / 10_000.0

        fun toAssessment(
            analysis: FingerTappingAnalysis,
            assessmentId: String,
            timestampEpochMs: Long,
            hand: SelectedHand,
            recordingStartMs: Long = analysis.signal.samples.firstOrNull()?.timestampMs ?: 0L
        ): FingerTappingAssessment {
            val m = analysis.metrics
            val q = analysis.quality
            val f = m.frames
            val events = analysis.detection.events
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
                fps = f.fps,
                validFramePercent = f.validFramePercent,
                dropoutCount = f.dropoutCount,
                dropoutDurationMs = f.dropoutTotalMs,
                longestDropoutMs = f.longestDropoutMs,
                recordingCompletenessPercent = m.recordingCompletenessPercent,
                qualityStatus = q.status,
                qualityIssues = q.issues,
                qualityScore = q.qualityScore,
                performanceScore = analysis.score,
                algorithmVersion = FingerTappingVersions.ALGORITHM_VERSION,
                scoringVersion = FingerTappingVersions.SCORING_VERSION,
                cameraQuality = q.cameraQuality,
                handTrackingQuality = q.handTrackingQuality,
                trackingRatePercent = q.trackingRate * 100.0,
                usableDurationMs = q.usableDurationMs,
                framesAnalyzed = f.totalFrames,
                validLandmarkFrames = f.validFrames,
                meanLuma = f.meanLuma,
                cameraFramesSkipped = f.cameraFramesSkipped,
                pipelineFramesDropped = f.pipelineFramesDropped,
                meanClosingVelocity = m.meanClosingVelocity,
                closingVelocityCvPercent = m.closingVelocityCvPercent,
                reliability = analysis.interpretation.reliability,
                interpretationNotes = analysis.interpretation.notes,
                // Rounded so the stored payload reads back identically.
                payload = TapPayload(
                    timesMs = events.map { it.timestampMs - recordingStartMs },
                    amplitudes = events.map { round4(it.amplitude) },
                    velocities = events.map { round4(it.closingVelocity) },
                    durationsMs = events.map { it.durationMs }
                )
            )
        }
    }
}
