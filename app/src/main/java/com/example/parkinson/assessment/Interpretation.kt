package com.example.parkinson.assessment

/**
 * Software interpretation vocabulary shared by the motor tests. These are INTERNAL SOFTWARE
 * bands, not clinical categories: no normative dataset was used.
 */

/** Exact, non-overlapping bands over 0..100 for a "شاخص عملکرد حرکتی". */
enum class MotorPerformanceBand(val min: Int, val max: Int) {
    VERY_LOW(0, 19),
    SIGNIFICANTLY_REDUCED(20, 39),
    REDUCED(40, 59),
    ACCEPTABLE(60, 79),
    GOOD(80, 100);

    companion object {
        fun forScore(score: Int): MotorPerformanceBand {
            require(score in 0..100) { "score out of range: $score" }
            return entries.first { score in it.min..it.max }
        }
    }
}

/** How far a score can be relied on, from the recording quality. */
enum class ReliabilityLevel { RELIABLE, LIMITED, NOT_RELIABLE }

/** Change of performance within one recording (early vs late). Never disease progression. */
enum class PerformanceTrendState { STABLE, IMPROVING, DECLINING, INSUFFICIENT_DATA }
