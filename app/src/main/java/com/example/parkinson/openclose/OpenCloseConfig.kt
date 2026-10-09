package com.example.parkinson.openclose

import com.example.parkinson.mediapipe.VisionQualityConfig

/**
 * Versions stored with every Hand Opening/Closing result. Bump [ALGORITHM_VERSION] for any change to
 * landmark features, detection, metrics or quality rules. See docs/hand-opening-closing-algorithm.md.
 */
object HandOpenCloseVersions {
    const val ALGORITHM_VERSION = "ho-algo-1.1.0"

    /** This version has no performance score: the result carries measurements and quality only. */
    const val SCORING_VERSION = "not-scored"
}

/** Session timing in milliseconds. Timestamps come from the monotonic clock, never from frame counts. */
data class OpenCloseSessionConfig(
    val countdownMs: Long = 3_000L,
    val recordingMs: Long = 10_000L,
    /** Frames captured before the end that MediaPipe has not delivered yet are waited for. */
    val lateFrameGraceMs: Long = 200L,
    val tickMs: Long = 100L,
    /** No MediaPipe result for this long during COUNTDOWN/RECORDING = camera pipeline stalled. */
    val frameStarvationMs: Long = 2_000L,
    /** This many MediaPipe errors in a row = tracking failure. */
    val maxConsecutiveErrors: Int = 5,
    /** Live cycle count refresh period during RECORDING. */
    val liveUpdateIntervalMs: Long = 300L
)

/**
 * Parameters of the signal and cycle detection. ENGINEERING values chosen for technical robustness;
 * they are not clinically validated. Levels are fractions of the recording's own opening range, so
 * the thresholds follow each person's full range of movement; [minRange] is the only absolute floor.
 */
data class OpenCloseDetectionConfig(
    /** Time constant of the zero-phase smoother. Openings take several hundred ms, so 60 ms is mild. */
    val smoothingTauMs: Double = 60.0,
    /** Gaps without valid landmarks up to this length are bridged; longer gaps split the signal. */
    val maxBridgeGapMs: Long = 250L,
    /** Extension values above this are landmark glitches (a finger cannot be longer than its chain, beyond noise). */
    val maxPlausibleOpening: Double = 1.25,
    /** Envelope percentiles of the valid smoothed signal: the fully closed and fully open levels. */
    val envelopeLowPercentile: Double = 0.05,
    val envelopeHighPercentile: Double = 0.95,
    /** Smallest opening range (extension units) that counts as movement. Tiny motion is never a cycle. */
    val minRange: Double = 0.25,
    /**
     * Absolute references, in extension units (see [OpenCloseFrameExtractor]). A counted cycle must
     * reach a FULLY OPEN hand (extension at least [minFullOpening]) and a CLOSED fist (at most
     * [maxFullClosing]). Without them, a recording of partial movements would make the partial level
     * the "open" level and count it as a cycle. The values are about geometry, not about the person:
     * a straight finger is 1.0 for every finger length, and a fist curls the fingers to about 0.4-0.6.
     * A slight natural bend (~0.9) still counts as open. Engineering values, to be validated on device.
     */
    val minFullOpening: Double = 0.85,
    val maxFullClosing: Double = 0.60,
    /**
     * Hysteresis levels (fractions of the range above the low level). A cycle needs the OPEN and
     * CLOSED states to be entered at the outer levels; they are left only after the inner levels,
     * so noise around one threshold cannot create extra transitions.
     */
    val closeFraction: Double = 0.35,
    val closeExitFraction: Double = 0.45,
    val openExitFraction: Double = 0.55,
    val openFraction: Double = 0.65,
    /**
     * A full cycle (open -> open) outside this range is not a repetitive opening and closing. Short
     * spikes are removed by the smoothing and the hysteresis levels, so no separate stage floor is used:
     * a stage floor near the frame interval would reject real movements sampled at ~30 Hz.
     */
    val minCycleMs: Long = 400L,
    val maxCycleMs: Long = 4_000L
)

/** ENGINEERING limits for a usable recording (technical reliability, never the person). Not clinically validated. */
data class OpenCloseQualityThresholds(
    val minFrames: Int = 10,
    /** Frames must really arrive over >= 90 % of the planned window (from frame timestamps). */
    val minObservedCompletenessPercent: Double = 90.0,
    /** Absolute floor for the result rate, and the rate below which timing is only a warning. */
    val absoluteMinFps: Double = 8.0,
    val goodFps: Double = 20.0,
    val vision: VisionQualityConfig = VisionQualityConfig(),
    val maxLongestDropoutMs: Long = 2_000L,
    val goodDropoutShare: Double = 0.10,
    val goodLongestDropoutMs: Long = 750L,
    /** Share of frames where the hand left the image (or its landmarks were unusable). */
    val maxOutOfFrameShare: Double = 0.10,
    val minSideConfidentFrames: Int = 10,
    val wrongHandMinShare: Double = 0.5,
    val sideUncertainShare: Double = 0.2,
    val multipleHandsMaxFraction: Double = 0.30,
    /** Noise sigma relative to the median cycle amplitude. */
    val maxNoiseToAmplitude: Double = 0.20,
    /** Partial (not completed) excursions as a share of all excursions. */
    val maxPartialShare: Double = 0.5,
    /** Fewer completed cycles than this are not enough for a rhythm measurement. */
    val minCycles: Int = 3
)
