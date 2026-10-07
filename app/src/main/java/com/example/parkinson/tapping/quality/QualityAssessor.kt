package com.example.parkinson.tapping.quality

import com.example.parkinson.tapping.metrics.MotorMetrics
import com.example.parkinson.tapping.signal.ProcessedSignal

/** Ordered from best to worst. */
enum class QualityStatus { VALID, LOW_QUALITY, INSUFFICIENT_DATA, INVALID }

enum class QualityIssue(val severity: QualityStatus) {
    NO_FRAMES(QualityStatus.INVALID),
    RECORDING_INCOMPLETE(QualityStatus.INVALID),
    INSUFFICIENT_FPS(QualityStatus.INVALID),
    NO_HAND_DETECTED(QualityStatus.INVALID),
    WRONG_HAND(QualityStatus.INVALID),
    MULTIPLE_HANDS(QualityStatus.INVALID),
    TOO_FEW_VALID_FRAMES(QualityStatus.INVALID),
    EXCESSIVE_DROPOUT(QualityStatus.INVALID),

    NO_TAPPING_DETECTED(QualityStatus.INSUFFICIENT_DATA),
    TOO_FEW_TAPS(QualityStatus.INSUFFICIENT_DATA),

    LOW_FPS(QualityStatus.LOW_QUALITY),
    REDUCED_VALID_FRAMES(QualityStatus.LOW_QUALITY),
    DROPOUTS_PRESENT(QualityStatus.LOW_QUALITY),
    UNSTABLE_TRACKING(QualityStatus.LOW_QUALITY),
    NOISY_SIGNAL(QualityStatus.LOW_QUALITY),
    LOW_EVENT_CONFIDENCE(QualityStatus.LOW_QUALITY)
}

/**
 * Engineering limits for a usable recording. They describe the technical reliability of the
 * measurement (camera, tracking, signal), not the person. Tune with real-device data.
 */
data class QualityThresholds(
    val minFrames: Int = 10,
    val minCompletenessPercent: Double = 95.0,
    /** Below this the fastest taps (~5 Hz) get fewer than ~2.5 samples per cycle. */
    val minFps: Double = 12.0,
    val goodFps: Double = 20.0,
    val wrongHandMaxFraction: Double = 0.30,
    val multipleHandsMaxFraction: Double = 0.30,
    val noHandMaxFraction: Double = 0.50,
    val minValidFraction: Double = 0.60,
    val goodValidFraction: Double = 0.85,
    val maxDropoutShare: Double = 0.30,
    val maxLongestDropoutMs: Long = 2_000L,
    val goodDropoutShare: Double = 0.10,
    val goodLongestDropoutMs: Long = 750L,
    val maxHandScaleCvPercent: Double = 25.0,
    /** Noise sigma relative to the median tap amplitude. */
    val maxNoiseToAmplitude: Double = 0.20,
    val minMeanEventConfidence: Double = 0.50,
    /** Rhythm needs at least 3 intervals. */
    val minTaps: Int = 4
)

data class QualityReport(
    val status: QualityStatus,
    /** All issues found, most severe first. */
    val issues: List<QualityIssue>,
    /** 0..100 technical recording quality. */
    val qualityScore: Int,
    val fps: Double,
    val validFramePercent: Double,
    val dropoutSharePercent: Double,
    val longestDropoutMs: Long,
    val handScaleCvPercent: Double?,
    val noiseToAmplitude: Double?
) {
    val primaryIssue: QualityIssue? get() = issues.firstOrNull()

    /** VALID and LOW_QUALITY recordings produce a result; the others must be repeated. */
    val isUsable: Boolean get() = status == QualityStatus.VALID || status == QualityStatus.LOW_QUALITY
}

/** QUALITY CONTROL: decides whether a recording is reliable enough to report. */
object QualityAssessor {

    fun assess(
        metrics: MotorMetrics,
        signal: ProcessedSignal,
        thresholds: QualityThresholds = QualityThresholds()
    ): QualityReport {
        val f = metrics.frames
        val issues = mutableListOf<QualityIssue>()
        val durationMs = metrics.recordingDurationMs
        val dropoutShare = if (durationMs > 0) f.dropoutTotalMs.toDouble() / durationMs else 1.0
        val validFraction = f.fraction(f.validFrames)
        val noiseToAmplitude = metrics.medianAmplitude?.takeIf { it > 0 }?.let { signal.noiseSigma / it }

        if (f.totalFrames < thresholds.minFrames) issues += QualityIssue.NO_FRAMES
        if (metrics.recordingCompletenessPercent < thresholds.minCompletenessPercent) {
            issues += QualityIssue.RECORDING_INCOMPLETE
        }
        if (f.totalFrames >= 2) {
            if (f.fps < thresholds.minFps) {
                issues += QualityIssue.INSUFFICIENT_FPS
            } else if (f.fps < thresholds.goodFps) {
                issues += QualityIssue.LOW_FPS
            }
        }

        if (validFraction < thresholds.minValidFraction) {
            // Name the dominant cause when there is one.
            when {
                f.fraction(f.wrongHandFrames) >= thresholds.wrongHandMaxFraction -> issues += QualityIssue.WRONG_HAND
                f.fraction(f.multipleHandsFrames) >= thresholds.multipleHandsMaxFraction ->
                    issues += QualityIssue.MULTIPLE_HANDS

                f.validFrames == 0 || f.fraction(f.noHandFrames) >= thresholds.noHandMaxFraction ->
                    issues += QualityIssue.NO_HAND_DETECTED

                else -> issues += QualityIssue.TOO_FEW_VALID_FRAMES
            }
        } else {
            if (f.fraction(f.wrongHandFrames) >= thresholds.wrongHandMaxFraction) issues += QualityIssue.WRONG_HAND
            if (f.fraction(f.multipleHandsFrames) >= thresholds.multipleHandsMaxFraction) {
                issues += QualityIssue.MULTIPLE_HANDS
            }
            if (validFraction < thresholds.goodValidFraction) issues += QualityIssue.REDUCED_VALID_FRAMES
        }

        if (dropoutShare > thresholds.maxDropoutShare || f.longestDropoutMs > thresholds.maxLongestDropoutMs) {
            issues += QualityIssue.EXCESSIVE_DROPOUT
        } else if (dropoutShare > thresholds.goodDropoutShare || f.longestDropoutMs > thresholds.goodLongestDropoutMs) {
            issues += QualityIssue.DROPOUTS_PRESENT
        }

        if ((signal.handScaleCvPercent ?: 0.0) > thresholds.maxHandScaleCvPercent) {
            issues += QualityIssue.UNSTABLE_TRACKING
        }

        if (metrics.tapCount == 0) {
            issues += QualityIssue.NO_TAPPING_DETECTED
        } else if (metrics.tapCount < thresholds.minTaps) {
            issues += QualityIssue.TOO_FEW_TAPS
        } else {
            if (noiseToAmplitude != null && noiseToAmplitude > thresholds.maxNoiseToAmplitude) {
                issues += QualityIssue.NOISY_SIGNAL
            }
            if ((metrics.meanEventConfidence ?: 0.0) < thresholds.minMeanEventConfidence) {
                issues += QualityIssue.LOW_EVENT_CONFIDENCE
            }
        }

        val sorted = issues.distinct().sortedByDescending { it.severity.ordinal }
        val status = sorted.maxOfOrNull { it.severity } ?: QualityStatus.VALID

        return QualityReport(
            status = status,
            issues = sorted,
            qualityScore = qualityScore(f.fps, validFraction, dropoutShare, metrics.meanEventConfidence, thresholds),
            fps = f.fps,
            validFramePercent = f.validFramePercent,
            dropoutSharePercent = (dropoutShare * 100.0).coerceIn(0.0, 100.0),
            longestDropoutMs = f.longestDropoutMs,
            handScaleCvPercent = signal.handScaleCvPercent,
            noiseToAmplitude = noiseToAmplitude
        )
    }

    /** Mean of four 0..1 factors, each linear between its "unusable" and "good" limit. */
    private fun qualityScore(
        fps: Double,
        validFraction: Double,
        dropoutShare: Double,
        meanConfidence: Double?,
        t: QualityThresholds
    ): Int {
        fun ramp(value: Double, bad: Double, good: Double) = ((value - bad) / (good - bad)).coerceIn(0.0, 1.0)
        val factors = listOf(
            ramp(fps, t.minFps, t.goodFps),
            ramp(validFraction, t.minValidFraction, t.goodValidFraction),
            1.0 - ramp(dropoutShare, t.goodDropoutShare, t.maxDropoutShare),
            meanConfidence?.coerceIn(0.0, 1.0) ?: 0.0
        )
        return Math.round(factors.average() * 100.0).toInt()
    }
}
