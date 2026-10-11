package com.example.parkinson.speech

/**
 * Versions stored with every speech result. Bump [ALGORITHM_VERSION] for any change to the framing, quality,
 * pitch, voice-activity, pause, syllable-event or metric rules. See docs/speech-assessment-algorithm.md.
 */
object SpeechVersions {
    const val ALGORITHM_VERSION = "speech-algo-1.0.0"

    /** No score: the result carries measurements, data quality and their limits only. */
    const val SCORING_VERSION = "not-scored"
}

/** Requested capture format. The actual rate is read back from the recorder and used for analysis. */
data class SpeechAudioFormat(
    val requestedSampleRateHz: Int = 16_000,
    val minimumSampleRateHz: Int = 8_000,
)

/**
 * ENGINEERING parameters of the signal processing. Not clinically validated. None of them classifies a
 * person; they decide when a measurement is reliable enough to report.
 */
data class SpeechSignalConfig(
    /** Analysis frame (ms) for energy and pitch. */
    val frameMs: Double = 40.0,
    /** Hop between analysis frames (ms). */
    val hopMs: Double = 10.0,
    /** Lowest and highest fundamental frequency searched (Hz). Covers adult speech and sustained phonation. */
    val minF0Hz: Double = 60.0,
    val maxF0Hz: Double = 400.0,
    /** Normalized autocorrelation at the pitch lag needed to call a frame voiced (0..1). */
    val voicingThreshold: Double = 0.6,
    /** A frame is speech-active when its energy is at least this far above the noise floor (dB). */
    val activityMarginDb: Double = 10.0,
    /** Pauses shorter than this are not counted (ms). */
    val minPauseMs: Double = 250.0,
    /** Pauses at least this long count as long pauses (ms). */
    val longPauseMs: Double = 1_000.0,
    /** Syllable-event peaks must exceed the noise floor by this much (dB). */
    val eventMarginDb: Double = 8.0,
    /** Minimum spacing of two syllable-event peaks (ms). About 8 events per second at most. */
    val minEventSpacingMs: Double = 120.0,
    /** Minimum prominence of an event peak over its neighbourhood (dB). */
    val eventProminenceDb: Double = 3.0,
) {
    fun summary(): String =
        "frame=${frameMs}ms;hop=${hopMs}ms;f0=$minF0Hz-${maxF0Hz}Hz;voicing>=$voicingThreshold;" +
            "activity+${activityMarginDb}dB;pause>=${minPauseMs}ms;long>=${longPauseMs}ms;event+${eventMarginDb}dB"
}

/**
 * ENGINEERING quality limits. Clipping, silence and a low signal-to-noise ratio are reported as quality
 * findings. A low-volume voice is not rejected on its own: the level is reported and the SNR decides.
 */
data class SpeechQualityThresholds(
    /** Share of samples at or above [clipLevel] (percent) above which the recording is invalid. */
    val clippingInvalidPercent: Double = 1.0,
    /** Share of clipped samples (percent) above which the recording is flagged. */
    val clippingWarnPercent: Double = 0.1,
    /** Fraction of full scale that counts as clipped. */
    val clipLevel: Double = 0.98,
    /** Overall level below this (dBFS) means no usable speech was captured. */
    val silenceDbfs: Double = -50.0,
    /** Speech-to-noise-floor ratio below this (dB) is a warning. */
    val lowSnrDb: Double = 10.0,
    /** Speech-to-noise-floor ratio below this (dB) means the noise hides the voice: invalid. */
    val unusableSnrDb: Double = 3.0,
    /** Recording must cover at least this share of the planned duration. */
    val minDurationFraction: Double = 0.8,
    /** Valid-frame (voiced or speech-active, with reliable pitch where needed) share needed for a usable result. */
    val minUsableFramePercent: Double = 20.0,
)
