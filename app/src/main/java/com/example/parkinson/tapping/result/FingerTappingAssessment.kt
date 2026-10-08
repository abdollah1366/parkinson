package com.example.parkinson.tapping.result

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.MotorPerformanceBand
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.mediapipe.HandTrackingQuality
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.quality.QualityIssue
import com.example.parkinson.tapping.scoring.PerformanceScore
import com.example.parkinson.tapping.scoring.TappingNote

/**
 * Compact analysis payload: one entry per detected tap, enough to re-analyze rhythm, amplitude
 * and speed later without storing any camera frame. Times are ms from the recording start.
 */
data class TapPayload(
    val timesMs: List<Long> = emptyList(),
    /** Opening amplitude, palm sizes. */
    val amplitudes: List<Double> = emptyList(),
    /** Mean closing speed, palm sizes per second. */
    val velocities: List<Double> = emptyList(),
    val durationsMs: List<Long> = emptyList()
) {
    /** Tap-to-tap intervals, ms. */
    val intervalsMs: List<Long> get() = timesMs.zipWithNext { a, b -> b - a }
}

/**
 * Immutable result of one Finger Tapping assessment, as stored and shown.
 * Contains measurements only; no name, birth date or other personal identifier and no images.
 * Amplitudes are in palm sizes (thumb-index distance / palm size). Null = not measurable.
 */
data class FingerTappingAssessment(
    override val assessmentId: String,
    override val timestampEpochMs: Long,
    override val hand: SelectedHand,
    val plannedDurationMs: Long,
    val recordingDurationMs: Long,

    val tapCount: Int,
    val tapRateHz: Double,
    val tapsPer10Seconds: Double,
    val meanIntervalMs: Double?,
    val medianIntervalMs: Double?,
    val intervalSdMs: Double?,
    val intervalCvPercent: Double?,
    val tapToTapVariabilityPercent: Double?,
    val pauseCount: Int,

    val meanAmplitude: Double?,
    val medianAmplitude: Double?,
    val amplitudeSd: Double?,
    val amplitudeCvPercent: Double?,
    val minAmplitude: Double?,
    val maxAmplitude: Double?,
    val meanTapDurationMs: Double?,

    val amplitudeEarly: Double?,
    val amplitudeMiddle: Double?,
    val amplitudeLate: Double?,
    /** Measured amplitude trend, late vs early third, in %. Not a clinical finding. */
    val amplitudeTrendPercent: Double?,
    val rateEarlyHz: Double?,
    val rateLateHz: Double?,
    val rateTrendPercent: Double?,
    val movementConsistencyPercent: Double?,

    val fps: Double,
    val validFramePercent: Double,
    val dropoutCount: Int,
    val dropoutDurationMs: Long,
    val longestDropoutMs: Long,
    val recordingCompletenessPercent: Double,

    val qualityStatus: QualityStatus,
    val qualityIssues: List<QualityIssue>,
    val qualityScore: Int,

    /** null when the quality did not allow a score (INVALID / INSUFFICIENT_DATA, never stored). */
    val performanceScore: PerformanceScore?,

    override val algorithmVersion: String,
    override val scoringVersion: String,

    // --- Layered quality, frame statistics, interpretation, payload (algorithm 1.1 / scoring 1.0)
    val cameraQuality: CameraQuality? = null,
    val handTrackingQuality: HandTrackingQuality? = null,
    /** Share of the 10 s with valid landmarks, %. */
    val trackingRatePercent: Double? = null,
    val usableDurationMs: Long? = null,
    val framesAnalyzed: Int? = null,
    val validLandmarkFrames: Int? = null,
    val meanLuma: Float? = null,
    val cameraFramesSkipped: Int? = null,
    val pipelineFramesDropped: Int? = null,
    val meanClosingVelocity: Double? = null,
    val closingVelocityCvPercent: Double? = null,
    val reliability: ReliabilityLevel = ReliabilityLevel.NOT_RELIABLE,
    val interpretationNotes: List<TappingNote> = emptyList(),
    val payload: TapPayload = TapPayload()
) : AssessmentResult {
    override val type: AssessmentType get() = AssessmentType.FINGER_TAPPING
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, qualityScore)
    override val performanceIndex: Int? get() = performanceScore?.total
    val interpretationBand: MotorPerformanceBand? get() = performanceScore?.band
}
