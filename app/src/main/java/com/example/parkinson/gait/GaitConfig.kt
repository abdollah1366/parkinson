package com.example.parkinson.gait

/**
 * Versions stored with every Gait result. Bump [ALGORITHM_VERSION] for any change to the feature,
 * step-detection, metric or quality rules. See docs/gait-assessment-algorithm.md.
 */
object GaitVersions {
    const val ALGORITHM_VERSION = "gait-algo-1.0.0"

    /** No gait score: the result carries measurements and data quality only. */
    const val SCORING_VERSION = "not-scored"
}

/** Session timing in milliseconds. Timestamps come from the monotonic clock, never from frame counts. */
data class GaitSessionConfig(
    /** Preparation countdown: the person stands at the start position, nothing is recorded yet. */
    val countdownMs: Long = 3_000L,
    /** Walking time (the default protocol is 20 s). */
    val recordingMs: Long = 20_000L,
    /** Frames captured before the end that MediaPipe has not delivered yet are waited for. */
    val lateFrameGraceMs: Long = 200L,
    val tickMs: Long = 100L,
    /** No pose result for this long during COUNTDOWN/RECORDING = camera pipeline stalled. */
    val frameStarvationMs: Long = 2_000L,
    /** This many pose errors in a row = model failure. */
    val maxConsecutiveErrors: Int = 5,
)

/**
 * Parameters of the step detection. ENGINEERING values, not clinically validated. The step event is a
 * peak of the absolute ankle separation (image plane, torso lengths), with hysteresis.
 */
data class GaitSignalConfig(
    /** Ankle separation is averaged over this many consecutive valid frames before peak picking. */
    val smoothingFrames: Int = 3,
    /** A peak must rise at least this far above the preceding trough (torso lengths). */
    val minStepPeakTorso: Double = 0.15,
    /** Two step events closer than this are not both accepted (ms). About 200 steps per minute. */
    val minStepIntervalMs: Long = 300L,
) {
    fun summary(): String =
        "smooth=$smoothingFrames;hyst=$minStepPeakTorso;minStepMs=$minStepIntervalMs"
}

/**
 * ENGINEERING limits for a usable walking recording (technical reliability, never the person).
 * Not clinically validated.
 */
data class GaitQualityThresholds(
    /** Fewer frames than this (any status) is not a recording. */
    val minFrames: Int = 30,
    /** The recording must last at least this share of the planned duration. */
    val minRecordingFraction: Double = 0.8,
    /** Valid-frame share below this = insufficient data; below [goodValidFramePercent] = low quality. */
    val minValidFramePercent: Double = 70.0,
    val goodValidFramePercent: Double = 90.0,
    /** Gaps between valid frames longer than this are interruptions (ms). */
    val interruptionGapMs: Long = 500L,
    /** One interruption longer than this = insufficient data (ms). */
    val maxGapMs: Long = 1_000L,
    /** More interruptions than this = low quality. */
    val frequentInterruptions: Int = 3,
    /** Frame rate of the analysis below this = insufficient (timing unreliable). */
    val minFrameRateHz: Double = 10.0,
    /** Steps needed for cadence and step-time variability. */
    val minSteps: Int = 6,
    /** Frames with a wrist visible needed to report an arm-swing range. */
    val minWristFrames: Int = 10,
)
