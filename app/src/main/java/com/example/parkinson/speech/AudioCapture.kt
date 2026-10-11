package com.example.parkinson.speech

/**
 * One in-memory recording: 16-bit mono PCM samples and the sample rate the recorder actually delivered.
 * Never written to disk; the buffer is dropped after analysis (see [SpeechSession]).
 */
class AudioCapture(
    val samples: ShortArray,
    /** Sample rate the recorder reported (Hz). Analysis uses this value, never the requested one. */
    val sampleRateHz: Int,
    /** Sample rate that was requested from the recorder (Hz), kept for the record. */
    val requestedSampleRateHz: Int,
) {
    /** Duration of the samples actually captured (ms). */
    val durationMs: Double get() = if (sampleRateHz <= 0) 0.0 else samples.size * 1000.0 / sampleRateHz
}

/** Why a capture cannot be analysed at all. Null in a usable capture. */
enum class CaptureIntegrityIssue {
    NO_SAMPLES,
    INVALID_SAMPLE_RATE,
    SAMPLE_RATE_BELOW_MINIMUM,
    TRUNCATED,
}

/**
 * Checks that a capture is structurally usable before any analysis: a positive, supported sample rate, samples
 * present, and at least [minDurationFraction] of the planned duration delivered. The short-sample check is a
 * truncation check (for example, the recorder stopped early); it does not judge the voice.
 */
object CaptureIntegrity {

    fun check(
        capture: AudioCapture,
        plannedDurationMs: Long,
        format: SpeechAudioFormat = SpeechAudioFormat(),
        thresholds: SpeechQualityThresholds = SpeechQualityThresholds(),
    ): CaptureIntegrityIssue? = when {
        capture.samples.isEmpty() -> CaptureIntegrityIssue.NO_SAMPLES
        capture.sampleRateHz <= 0 -> CaptureIntegrityIssue.INVALID_SAMPLE_RATE
        capture.sampleRateHz < format.minimumSampleRateHz -> CaptureIntegrityIssue.SAMPLE_RATE_BELOW_MINIMUM
        capture.durationMs < thresholds.minDurationFraction * plannedDurationMs -> CaptureIntegrityIssue.TRUNCATED
        else -> null
    }
}
