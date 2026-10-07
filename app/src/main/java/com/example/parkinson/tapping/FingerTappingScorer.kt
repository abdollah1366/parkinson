package com.example.parkinson.tapping

enum class InvalidReason { TOO_FEW_TAPS, POOR_TRACKING }

/** Each value is 0..100, higher = better movement. */
data class SubScores(
    val speed: Int,
    val rhythm: Int,
    val amplitude: Int,
    val fatigue: Int,       // 100 = no amplitude decrement
    val continuity: Int     // 100 = no hesitations
)

sealed interface TapTestOutcome {
    data class Valid(
        /** Internal research index 0..100. NOT a diagnosis. */
        val score: Int,
        val subScores: SubScores,
        val metrics: FingerTappingMetrics
    ) : TapTestOutcome

    data class Invalid(
        val reason: InvalidReason,
        val metrics: FingerTappingMetrics
    ) : TapTestOutcome
}

/**
 * Converts FingerTappingMetrics into a simple, transparent index.
 *
 * IMPORTANT: every reference value below is a PLACEHOLDER. The index has not been
 * validated or calibrated against clinical data. Use it to compare a person with their
 * own earlier tests, not to classify anyone, and never show it as a diagnosis.
 */
object FingerTappingScorer {

    // Validity gates
    private const val MIN_TAPS = 5
    private const val MIN_TRACKING_QUALITY = 0.6f

    // Placeholder reference values (tune with real data)
    private const val REFERENCE_FREQUENCY_HZ = 4.0f
    private const val REFERENCE_AMPLITUDE = 1.0f      // opening / hand size
    private const val MAX_VARIABILITY_PERCENT = 40f
    private const val MAX_DECREMENT_PERCENT = 50f
    private const val POINTS_LOST_PER_HESITATION = 25

    // Weights (sum = 1.0)
    private const val W_SPEED = 0.30f
    private const val W_RHYTHM = 0.20f
    private const val W_AMPLITUDE = 0.20f
    private const val W_FATIGUE = 0.20f
    private const val W_CONTINUITY = 0.10f

    fun score(metrics: FingerTappingMetrics): TapTestOutcome {
        if (metrics.tapCount < MIN_TAPS) {
            return TapTestOutcome.Invalid(InvalidReason.TOO_FEW_TAPS, metrics)
        }
        if (metrics.trackingQuality < MIN_TRACKING_QUALITY) {
            return TapTestOutcome.Invalid(InvalidReason.POOR_TRACKING, metrics)
        }

        val speed = ratioScore(metrics.frequencyHz / REFERENCE_FREQUENCY_HZ)
        val rhythm = ratioScore(1f - metrics.intervalVariabilityPercent / MAX_VARIABILITY_PERCENT)
        val amplitude = ratioScore(metrics.meanAmplitude / REFERENCE_AMPLITUDE)
        val fatigue = ratioScore(1f - metrics.amplitudeDecrementPercent / MAX_DECREMENT_PERCENT)
        val continuity = (100 - metrics.hesitationCount * POINTS_LOST_PER_HESITATION).coerceIn(0, 100)

        val total = speed * W_SPEED +
            rhythm * W_RHYTHM +
            amplitude * W_AMPLITUDE +
            fatigue * W_FATIGUE +
            continuity * W_CONTINUITY

        return TapTestOutcome.Valid(
            score = total.toInt().coerceIn(0, 100),
            subScores = SubScores(speed, rhythm, amplitude, fatigue, continuity),
            metrics = metrics
        )
    }

    private fun ratioScore(ratio: Float): Int = (ratio * 100f).toInt().coerceIn(0, 100)
}

/** Persian message shown when the test must be repeated. */
fun InvalidReason.toPersianMessage(): String = when (this) {
    InvalidReason.TOO_FEW_TAPS ->
        "تعداد ضربه‌ها برای ارزیابی کافی نبود. لطفاً تست را دوباره انجام دهید."

    InvalidReason.POOR_TRACKING ->
        "دست در بخش زیادی از تست به‌خوبی دیده نشد. نور را بیشتر کنید، گوشی را ثابت نگه دارید و دوباره تلاش کنید."
}
