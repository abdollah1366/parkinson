package com.example.parkinson.pronation

import com.example.parkinson.model.SelectedHand

/**
 * Who / what a result is compared with. Today only [hand] is known; the other fields are the
 * place where future normative data (age, sex, dominant hand, device) will plug in.
 */
data class ReferenceContext(
    val hand: SelectedHand,
    val ageYears: Int? = null,
    val sex: String? = null,
    val dominantHand: SelectedHand? = null,
    val deviceModel: String? = null
)

/**
 * Normalization ranges that turn raw metrics into 0..100 component scores. A future validated
 * population reference can implement this interface (selecting ranges by [ReferenceContext])
 * without changing the engines.
 */
interface ScoringReference {
    /** Stored with every result so results are always traceable to the reference used. */
    val name: String

    /** true only for a reference built from validated normative data. */
    val isNormative: Boolean

    /** Cycle rate (Hz) mapped to 0 and to 100. */
    fun speedRangeHz(context: ReferenceContext): ClosedFloatingPointRange<Double>

    /** Median rotation per movement (degrees) mapped to 0 and to 100. */
    fun amplitudeRangeDeg(context: ReferenceContext): ClosedFloatingPointRange<Double>
}

/**
 * INTERNAL SOFTWARE REFERENCE ("محدوده مرجع داخلی نرم‌افزار"), NOT normative data.
 * The ranges span what the detector is designed to measure: 0.3 cycles/s (slowest rhythm the
 * 10-second test can resolve with >= 2 cycles) to 2.5 cycles/s, and 10 deg (detector minimum) to
 * 90 deg of rotation per movement. They are engineering anchors, not clinical normal values, and
 * do not depend on the context until validated data exists.
 */
object InternalSoftwareReference : ScoringReference {
    override val name: String = "internal-software-reference-1.0"
    override val isNormative: Boolean = false
    override fun speedRangeHz(context: ReferenceContext) = 0.3..2.5
    override fun amplitudeRangeDeg(context: ReferenceContext) = 10.0..90.0
}
