package com.example.parkinson.tapping

/** One detected tap (finger closing after an opening). */
data class TapEvent(
    val timestampMs: Long,
    /** Peak thumb-index opening before this tap, as a ratio of hand size. */
    val peakOpening: Float
)

/**
 * Research-level movement indicators for one Finger Tapping test.
 * These are NOT a clinical diagnosis.
 */
data class FingerTappingMetrics(
    val tapCount: Int,
    val testDurationSeconds: Float,
    /** Taps per second, measured between the first and last tap. */
    val frequencyHz: Float,
    val meanIntervalMs: Float,
    /** Coefficient of variation of the inter-tap intervals, in percent. */
    val intervalVariabilityPercent: Float,
    /** Mean peak opening (ratio of hand size). */
    val meanAmplitude: Float,
    /** Drop in amplitude from the first third of taps to the last third, in percent. */
    val amplitudeDecrementPercent: Float,
    /** Number of intervals longer than hesitationFactor x the median interval. */
    val hesitationCount: Int,
    /** Share of frames in which exactly the expected hand was tracked (0..1). */
    val trackingQuality: Float,
    val taps: List<TapEvent>
)
