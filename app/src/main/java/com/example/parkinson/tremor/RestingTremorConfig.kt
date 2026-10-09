package com.example.parkinson.tremor

/**
 * Versions stored with every Resting Hand Tremor result. Bump [ALGORITHM_VERSION] for any change to
 * the landmark feature, resampling, detrending, spectrum or quality rules. See
 * docs/resting-hand-tremor-algorithm.md.
 */
object RestingTremorVersions {
    const val ALGORITHM_VERSION = "rt-algo-1.0.0"

    /** No tremor score: the result carries measurements and data quality only. */
    const val SCORING_VERSION = "not-scored"
}

/** Session timing in milliseconds. Timestamps come from the monotonic clock, never from frame counts. */
data class RestingTremorSessionConfig(
    /** Preparation countdown; the hand must rest during it, frames are not recorded yet. */
    val countdownMs: Long = 3_000L,
    /** Recording length (the default protocol is about 15 s). */
    val recordingMs: Long = 15_000L,
    /** Frames captured before the end that MediaPipe has not delivered yet are waited for. */
    val lateFrameGraceMs: Long = 200L,
    val tickMs: Long = 100L,
    /** No MediaPipe result for this long during COUNTDOWN/RECORDING = camera pipeline stalled. */
    val frameStarvationMs: Long = 2_000L,
    /** This many MediaPipe errors in a row = tracking failure. */
    val maxConsecutiveErrors: Int = 5,
)

/**
 * Parameters of the signal processing. ENGINEERING values chosen for technical robustness. They are
 * not clinically validated, and the analysis band is an engineering range, not a clinical tremor band.
 */
data class RestingTremorSignalConfig(
    /** Uniform time grid of the analysed signal; valid frames are linearly resampled onto it. */
    val gridHz: Double = 20.0,
    /** Valid frames further apart than this are a tracking interruption; the signal is not bridged across. */
    val interruptionGapMs: Long = 250L,
    /** Width of the centred moving average that defines the slow component removed by detrending. */
    val detrendWindowMs: Long = 1_000L,
    /** Lowest frequency analysed. Below ~2 cycles per recording a frequency cannot be estimated anyway. */
    val analysisMinHz: Double = 0.5,
    /** Highest frequency analysed; kept below the Nyquist frequency of [gridHz] (10 Hz). */
    val analysisMaxHz: Double = 9.0,
    /** Spacing of the frequency grid of the periodogram. */
    val frequencyStepHz: Double = 0.05,
    /** Half-width around the peak used for the periodicity share. */
    val peakHalfWidthHz: Double = 0.5,
    /** Peak power divided by the mean power of the analysed band; below this no dominant frequency is reported. */
    val minPeakProminence: Double = 6.0,
    /** A dominant frequency needs at least this many cycles inside the recording. */
    val minCyclesInRecording: Double = 3.0,
) {
    fun summary(): String =
        "grid=${gridHz}Hz;gap=${interruptionGapMs}ms;detrend=${detrendWindowMs}ms;" +
            "band=$analysisMinHz-${analysisMaxHz}Hz;prom=$minPeakProminence;cycles=$minCyclesInRecording"
}

/**
 * ENGINEERING limits for a usable recording (technical reliability, never the person).
 * Not clinically validated. Gross-movement limits separate camera/body movement from a resting hand.
 */
data class RestingTremorQualityThresholds(
    /** Fewer frames than this (any status) is not a recording. */
    val minFrames: Int = 30,
    /** The recording must last at least this share of the planned duration. */
    val minRecordingFraction: Double = 0.8,
    /** Valid-frame share below this = insufficient data; below [goodValidFramePercent] = low quality. */
    val minValidFramePercent: Double = 70.0,
    val goodValidFramePercent: Double = 90.0,
    /** A single tracking interruption longer than this = insufficient data. */
    val maxInterruptionMs: Long = 1_000L,
    /** More interruptions than this = low quality. */
    val frequentInterruptions: Int = 3,
    /** Frame rate of the analysis result below this = insufficient (timing unreliable). */
    val minFrameRateHz: Double = 10.0,
    /** Share of frame intervals outside [0.5, 2] x median above this = irregular sampling. */
    val maxIrregularIntervalPercent: Double = 5.0,
    /**
     * Range of the local mean (1 s window) of the palm position, in hand lengths. Above this the hand
     * (or the camera) moved as a whole, so the recording cannot be a resting-state measurement.
     */
    val maxSlowDriftHandLengths: Double = 0.5,
    /** Share of frames with two or more hands above this = invalid. */
    val maxMultipleHandsPercent: Double = 30.0,
    /** Share of frames whose handedness does not match the selected hand above this = invalid. */
    val maxWrongHandPercent: Double = 50.0,
)
