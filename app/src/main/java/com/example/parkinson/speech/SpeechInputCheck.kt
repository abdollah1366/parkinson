package com.example.parkinson.speech

/** Result of the short microphone check before a task. Each value maps to one plain-language instruction. */
enum class InputCheckResult {
    READY,
    /** No speech-level signal: the person should speak louder or move closer to the phone. */
    TOO_QUIET,
    /** The signal is distorted: move the phone further away or speak more softly. */
    CLIPPING,
    /** The noise hides the voice: move to a quieter place. */
    NOISY,
    /** No usable capture at all (no samples, truncated, or the recorder failed to deliver audio). */
    NO_SIGNAL,
}

/**
 * Turns the quality report of a short test recording into one instruction. Only conditions the quality analyser
 * actually detected are reported; a low-quality warning alone still counts as ready.
 */
object SpeechInputCheck {

    fun interpret(report: SpeechQualityReport): InputCheckResult = when {
        SpeechQualityIssue.NO_SAMPLES in report.issues ||
            SpeechQualityIssue.TRUNCATED in report.issues ||
            SpeechQualityIssue.INVALID_SAMPLE_RATE in report.issues ||
            SpeechQualityIssue.SAMPLE_RATE_BELOW_MINIMUM in report.issues -> InputCheckResult.NO_SIGNAL
        SpeechQualityIssue.CLIPPING in report.issues -> InputCheckResult.CLIPPING
        SpeechQualityIssue.SILENCE in report.issues -> InputCheckResult.TOO_QUIET
        SpeechQualityIssue.UNUSABLE_SNR in report.issues -> InputCheckResult.NOISY
        else -> InputCheckResult.READY
    }
}
