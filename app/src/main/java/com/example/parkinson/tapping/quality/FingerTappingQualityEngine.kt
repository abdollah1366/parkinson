package com.example.parkinson.tapping.quality

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.mediapipe.HandTrackingQuality
import com.example.parkinson.mediapipe.VisionQualityConfig
import com.example.parkinson.tapping.metrics.MotorMetrics
import com.example.parkinson.tapping.signal.ProcessedSignal
import kotlin.math.max

/**
 * Quality issues, grouped by layer. Severity:
 *  - INVALID: the recording itself is technically unusable (or it is the wrong hand).
 *  - INSUFFICIENT_DATA: the recording is fine but did not contain enough usable motor data.
 *  - LOW_QUALITY: usable, scored with limited reliability.
 * Camera/image issues are never more than LOW_QUALITY: brightness is not motor data.
 */
enum class QualityIssue(val severity: QualityStatus) {
    // Recording / data layer
    NO_FRAMES(QualityStatus.INVALID),
    RECORDING_INCOMPLETE(QualityStatus.INVALID),
    INSUFFICIENT_FPS(QualityStatus.INVALID),

    // Hand detection layer
    WRONG_HAND(QualityStatus.INVALID),
    MULTIPLE_HANDS(QualityStatus.INVALID),
    NO_HAND_DETECTED(QualityStatus.INSUFFICIENT_DATA),

    // Landmark tracking layer
    INSUFFICIENT_TRACKING(QualityStatus.INSUFFICIENT_DATA),
    EXCESSIVE_DROPOUT(QualityStatus.INSUFFICIENT_DATA),

    // Tap detection layer
    NO_TAPPING_DETECTED(QualityStatus.INSUFFICIENT_DATA),
    TOO_FEW_TAPS(QualityStatus.INSUFFICIENT_DATA),

    LOW_FPS(QualityStatus.LOW_QUALITY),
    CAMERA_FRAMES_DROPPED(QualityStatus.LOW_QUALITY),
    REDUCED_TRACKING(QualityStatus.LOW_QUALITY),
    DROPOUTS_PRESENT(QualityStatus.LOW_QUALITY),
    HAND_SIDE_UNCERTAIN(QualityStatus.LOW_QUALITY),
    UNSTABLE_TRACKING(QualityStatus.LOW_QUALITY),
    /** Image too dark/bright AND tracking below GOOD: lighting is likely part of the cause. */
    LIGHTING_AFFECTED_TRACKING(QualityStatus.LOW_QUALITY),
    NOISY_SIGNAL(QualityStatus.LOW_QUALITY),
    LOW_EVENT_CONFIDENCE(QualityStatus.LOW_QUALITY)
}

/**
 * ENGINEERING limits for a usable 10-second recording. They describe the technical reliability
 * of the measurement, not the person, and are not clinically validated. See
 * docs/finger-tapping-algorithm.md section 7 for the reasoning behind each value.
 */
data class QualityThresholds(
    val minFrames: Int = 10,
    /** Frames must really arrive over >= 90 % of the planned window (from frame timestamps). */
    val minObservedCompletenessPercent: Double = 90.0,
    /** Absolute floor for the result rate. */
    val absoluteMinFps: Double = 8.0,
    /** Samples needed per tap cycle (>= 2 per opening and per closing half). */
    val minSamplesPerTapCycle: Double = 4.0,
    val goodFps: Double = 20.0,
    /** Share of the window with valid landmarks: >= 6 s of the 10 s are needed for rhythm. */
    val vision: VisionQualityConfig = VisionQualityConfig(),
    val maxLongestDropoutMs: Long = 2_000L,
    val goodDropoutShare: Double = 0.10,
    val goodLongestDropoutMs: Long = 750L,
    /** Recording-level hand check over the frames whose left/right label is confident. */
    val minSideConfidentFrames: Int = 10,
    val wrongHandMinShare: Double = 0.5,
    val sideUncertainShare: Double = 0.2,
    val multipleHandsMaxFraction: Double = 0.30,
    val maxCameraDropShare: Double = 0.30,
    val maxHandScaleCvPercent: Double = 25.0,
    /** Noise sigma relative to the median tap amplitude. */
    val maxNoiseToAmplitude: Double = 0.20,
    val minMeanEventConfidence: Double = 0.50,
    /** Rhythm needs at least 3 intervals. */
    val minTaps: Int = 4
)

/** Layered quality of one recording. [status] is the tap-data (final) quality. */
data class QualityReport(
    val status: QualityStatus,
    /** All issues found, most severe first. */
    val issues: List<QualityIssue>,
    /** 0..100 technical recording quality ("کیفیت داده"). */
    val qualityScore: Int,
    val fps: Double,
    val validFramePercent: Double,
    val dropoutSharePercent: Double,
    val longestDropoutMs: Long,
    val handScaleCvPercent: Double?,
    val noiseToAmplitude: Double?,
    /** Camera / image layer (brightness). A warning signal only. */
    val cameraQuality: CameraQuality = CameraQuality.GOOD,
    /** Hand detection + landmark tracking layer (time share with landmarks). */
    val handTrackingQuality: HandTrackingQuality = HandTrackingQuality.GOOD,
    val trackingRate: Double = 1.0,
    val usableDurationMs: Long = 0L,
    /** Result rate required for the measured tap rate. */
    val requiredFps: Double = 0.0
) {
    val primaryIssue: QualityIssue? get() = issues.firstOrNull()

    /** VALID and LOW_QUALITY recordings produce a (scored) result; the others must be repeated. */
    val isUsable: Boolean get() = status == QualityStatus.VALID || status == QualityStatus.LOW_QUALITY

    /** The image looked too dark or bright. Never, by itself, a reason to reject the test. */
    val lightingWarning: Boolean get() = cameraQuality != CameraQuality.GOOD
}

/**
 * QUALITY CONTROL in separate layers: camera/image, hand detection, landmark tracking, tap
 * detection and recording. The final status comes from the issues; camera quality only raises a
 * warning, or LOW_QUALITY when tracking is also reduced. Data are rejected only when the motor
 * data themselves are insufficient.
 */
object FingerTappingQualityEngine {

    fun assess(
        metrics: MotorMetrics,
        signal: ProcessedSignal,
        thresholds: QualityThresholds = QualityThresholds()
    ): QualityReport {
        val t = thresholds
        val f = metrics.frames
        val issues = mutableListOf<QualityIssue>()
        val durationMs = metrics.recordingDurationMs
        val dropoutShare = if (durationMs > 0) f.dropoutTotalMs.toDouble() / durationMs else 1.0
        val noiseToAmplitude = metrics.medianAmplitude?.takeIf { it > 0 }?.let { signal.noiseSigma / it }
        val cameraQuality = t.vision.cameraQuality(f.meanLuma)
        val trackingQuality = t.vision.trackingQuality(f.trackingRate)
        // Faster tapping needs more samples per second; slow tapping is measurable at lower rates.
        val requiredFps = max(t.absoluteMinFps, t.minSamplesPerTapCycle * metrics.tapRateHz)

        // Recording / data layer (from frame timestamps).
        if (f.totalFrames < t.minFrames) issues += QualityIssue.NO_FRAMES
        val observedCompleteness = if (metrics.plannedDurationMs > 0) f.observedDurationMs * 100.0 / metrics.plannedDurationMs else 0.0
        if (f.totalFrames >= t.minFrames && observedCompleteness < t.minObservedCompletenessPercent) {
            issues += QualityIssue.RECORDING_INCOMPLETE
        }
        if (f.totalFrames >= 2) {
            if (f.fps < requiredFps) issues += QualityIssue.INSUFFICIENT_FPS
            else if (f.fps < t.goodFps) issues += QualityIssue.LOW_FPS
        }
        val cameraDropShare = f.cameraFramesSkipped.toDouble() / (f.cameraFramesSkipped + f.totalFrames).coerceAtLeast(1)
        if (cameraDropShare > t.maxCameraDropShare) issues += QualityIssue.CAMERA_FRAMES_DROPPED

        // Hand detection layer.
        if (f.validFrames == 0) issues += QualityIssue.NO_HAND_DETECTED
        if (f.fraction(f.multipleHandsFrames) >= t.multipleHandsMaxFraction) issues += QualityIssue.MULTIPLE_HANDS
        val confidentSide = f.sideMatchesFrames + f.sideMismatchFrames
        val mismatch = f.sideMismatchShare
        if (f.validFrames > 0) {
            when {
                confidentSide >= t.minSideConfidentFrames && mismatch != null && mismatch >= t.wrongHandMinShare ->
                    issues += QualityIssue.WRONG_HAND
                confidentSide < t.minSideConfidentFrames || (mismatch ?: 0.0) >= t.sideUncertainShare ->
                    issues += QualityIssue.HAND_SIDE_UNCERTAIN
            }
        }

        // Landmark tracking layer (time-based).
        if (f.validFrames > 0) {
            when (trackingQuality) {
                HandTrackingQuality.POOR -> issues += QualityIssue.INSUFFICIENT_TRACKING
                HandTrackingQuality.WARNING -> issues += QualityIssue.REDUCED_TRACKING
                HandTrackingQuality.GOOD -> Unit
            }
            if (f.longestDropoutMs > t.maxLongestDropoutMs) {
                issues += QualityIssue.EXCESSIVE_DROPOUT
            } else if (dropoutShare > t.goodDropoutShare || f.longestDropoutMs > t.goodLongestDropoutMs) {
                issues += QualityIssue.DROPOUTS_PRESENT
            }
            if ((signal.handScaleCvPercent ?: 0.0) > t.maxHandScaleCvPercent) issues += QualityIssue.UNSTABLE_TRACKING
        }

        // Camera layer: only relevant when tracking also suffered.
        if (cameraQuality != CameraQuality.GOOD && trackingQuality != HandTrackingQuality.GOOD) {
            issues += QualityIssue.LIGHTING_AFFECTED_TRACKING
        }

        // Tap detection layer.
        if (f.validFrames > 0) {
            if (metrics.tapCount == 0) {
                issues += QualityIssue.NO_TAPPING_DETECTED
            } else if (metrics.tapCount < t.minTaps) {
                issues += QualityIssue.TOO_FEW_TAPS
            } else {
                if (noiseToAmplitude != null && noiseToAmplitude > t.maxNoiseToAmplitude) issues += QualityIssue.NOISY_SIGNAL
                if ((metrics.meanEventConfidence ?: 0.0) < t.minMeanEventConfidence) issues += QualityIssue.LOW_EVENT_CONFIDENCE
            }
        }

        val sorted = issues.distinct().sortedWith(compareByDescending<QualityIssue> { it.severity.ordinal }.thenBy { it.ordinal })
        val status = sorted.maxOfOrNull { it.severity } ?: QualityStatus.VALID

        return QualityReport(
            status = status,
            issues = sorted,
            qualityScore = qualityScore(f.fps, requiredFps, f.trackingRate, dropoutShare, metrics.meanEventConfidence, t),
            fps = f.fps,
            validFramePercent = f.validFramePercent,
            dropoutSharePercent = (dropoutShare * 100.0).coerceIn(0.0, 100.0),
            longestDropoutMs = f.longestDropoutMs,
            handScaleCvPercent = signal.handScaleCvPercent,
            noiseToAmplitude = noiseToAmplitude,
            cameraQuality = cameraQuality,
            handTrackingQuality = trackingQuality,
            trackingRate = f.trackingRate,
            usableDurationMs = f.usableDurationMs,
            requiredFps = requiredFps
        )
    }

    /** Mean of four 0..1 factors, each linear between its "unusable" and "good" limit. */
    private fun qualityScore(
        fps: Double,
        requiredFps: Double,
        trackingRate: Double,
        dropoutShare: Double,
        meanConfidence: Double?,
        t: QualityThresholds
    ): Int {
        fun ramp(value: Double, bad: Double, good: Double) =
            if (good <= bad) (if (value >= good) 1.0 else 0.0) else ((value - bad) / (good - bad)).coerceIn(0.0, 1.0)
        val factors = listOf(
            ramp(fps, requiredFps, max(t.goodFps, requiredFps + 1)),
            ramp(trackingRate, t.vision.minTrackingRate, t.vision.goodTrackingRate),
            1.0 - ramp(dropoutShare, t.goodDropoutShare, 1.0 - t.vision.minTrackingRate),
            meanConfidence?.coerceIn(0.0, 1.0) ?: 0.0
        )
        return Math.round(factors.average() * 100.0).toInt()
    }
}
