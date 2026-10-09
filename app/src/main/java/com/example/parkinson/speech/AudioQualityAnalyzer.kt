package com.example.parkinson.speech

import com.example.parkinson.assessment.QualityStatus

/**
 * Findings about the recording itself, ordered from most to least severe. Each one maps to a status. None of them
 * describes the person's voice; they say whether the recording can be analysed reliably.
 */
enum class SpeechQualityIssue(val status: QualityStatus) {
    NO_SAMPLES(QualityStatus.INVALID),
    INVALID_SAMPLE_RATE(QualityStatus.INVALID),
    SAMPLE_RATE_BELOW_MINIMUM(QualityStatus.INVALID),
    /** Clipping above the invalid limit: the peaks are distorted. */
    CLIPPING(QualityStatus.INVALID),
    TRUNCATED(QualityStatus.INSUFFICIENT_DATA),
    /** No speech-level signal was captured. */
    SILENCE(QualityStatus.INSUFFICIENT_DATA),
    /** The noise hides the voice (signal-to-noise below the unusable limit). */
    UNUSABLE_SNR(QualityStatus.INSUFFICIENT_DATA),
    /** Clipping above the warning limit but below the invalid limit. */
    CLIPPING_WARNING(QualityStatus.LOW_QUALITY),
    LOW_SNR(QualityStatus.LOW_QUALITY),
}

data class SpeechQualityReport(
    val status: QualityStatus,
    val issues: List<SpeechQualityIssue>,
    /** Duration of the samples actually captured (ms). */
    val durationMs: Double,
    /** Sample rate the recorder delivered (Hz). */
    val sampleRateHz: Int,
    /** Level of the whole recording (dBFS, relative to full scale; not a calibrated SPL). Null for an empty capture. */
    val rmsDbfs: Double?,
    val clippedPercent: Double?,
    /** 10th percentile of the frame levels (dBFS). Null when there are no frames. */
    val noiseFloorDb: Double?,
    /** Median level of the speech-active frames minus the noise floor (dB). Null when either is missing. */
    val snrDb: Double?,
) {
    /** Measurements may be reported only for VALID or LOW_QUALITY recordings. */
    val isUsable: Boolean get() = status == QualityStatus.VALID || status == QualityStatus.LOW_QUALITY
}

object AudioQualityAnalyzer {

    fun analyze(
        capture: AudioCapture,
        plannedDurationMs: Long,
        activity: ActivityTrack?,
        format: SpeechAudioFormat = SpeechAudioFormat(),
        thresholds: SpeechQualityThresholds = SpeechQualityThresholds(),
    ): SpeechQualityReport {
        val integrity = CaptureIntegrity.check(capture, plannedDurationMs, format, thresholds)
        if (integrity != null) {
            val issue = when (integrity) {
                CaptureIntegrityIssue.NO_SAMPLES -> SpeechQualityIssue.NO_SAMPLES
                CaptureIntegrityIssue.INVALID_SAMPLE_RATE -> SpeechQualityIssue.INVALID_SAMPLE_RATE
                CaptureIntegrityIssue.SAMPLE_RATE_BELOW_MINIMUM -> SpeechQualityIssue.SAMPLE_RATE_BELOW_MINIMUM
                CaptureIntegrityIssue.TRUNCATED -> SpeechQualityIssue.TRUNCATED
            }
            return report(capture, listOf(issue), rms = null, clipped = null, activity = null, snr = null)
        }

        val x = SignalFrames.toUnit(capture.samples)
        val rms = SignalFrames.rmsDb(x, 0, x.size)
        val clipped = SignalFrames.clippedPercent(capture.samples, thresholds.clipLevel)
        val noise = activity?.noiseFloorDb
        val speechLevel = activity?.activeLevelDb
        val snr = if (noise != null && speechLevel != null) speechLevel - noise else null

        val issues = ArrayList<SpeechQualityIssue>()
        if (clipped > thresholds.clippingInvalidPercent) {
            issues += SpeechQualityIssue.CLIPPING
        } else if (clipped > thresholds.clippingWarnPercent) {
            issues += SpeechQualityIssue.CLIPPING_WARNING
        }
        if (rms <= thresholds.silenceDbfs) issues += SpeechQualityIssue.SILENCE
        if (snr != null) {
            if (snr < thresholds.unusableSnrDb) {
                issues += SpeechQualityIssue.UNUSABLE_SNR
            } else if (snr < thresholds.lowSnrDb) {
                issues += SpeechQualityIssue.LOW_SNR
            }
        }
        return report(capture, issues, rms, clipped, activity, snr)
    }

    private fun report(
        capture: AudioCapture,
        issues: List<SpeechQualityIssue>,
        rms: Double?,
        clipped: Double?,
        activity: ActivityTrack?,
        snr: Double?,
    ): SpeechQualityReport {
        val sorted = issues.distinct().sortedBy { it.ordinal }
        val status = when {
            sorted.any { it.status == QualityStatus.INVALID } -> QualityStatus.INVALID
            sorted.any { it.status == QualityStatus.INSUFFICIENT_DATA } -> QualityStatus.INSUFFICIENT_DATA
            sorted.any { it.status == QualityStatus.LOW_QUALITY } -> QualityStatus.LOW_QUALITY
            else -> QualityStatus.VALID
        }
        return SpeechQualityReport(
            status = status,
            issues = sorted,
            durationMs = capture.durationMs,
            sampleRateHz = capture.sampleRateHz,
            rmsDbfs = rms,
            clippedPercent = clipped,
            noiseFloorDb = activity?.noiseFloorDb,
            snrDb = snr,
        )
    }
}
