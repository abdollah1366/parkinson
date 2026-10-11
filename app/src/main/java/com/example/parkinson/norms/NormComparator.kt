package com.example.parkinson.norms

import kotlin.math.abs

/** Metric keys used in `reference_norms.csv`. */
object NormMetric {
    const val GAIT_CADENCE = "gait.cadence"
    const val GAIT_STRIDE_CV = "gait.stride_cv"
    const val GAIT_TURN_VELOCITY = "gait.turn_velocity"
    const val STS_FIVE_TIMES = "sts.five_times"
    const val FT_TAP_RATE = "ft.tap_rate"
    const val FT_INTERVAL_CV = "ft.interval_cv"
    const val PS_CYCLE_RATE = "ps.cycle_rate"
    const val TREMOR_FREQUENCY = "tremor.frequency"
}

/** What the comparison knows about the person: from the registration, all null when nobody is registered. */
data class NormPerson(val age: Int?, val sex: NormSex?, val parkinsonDiagnosed: Boolean?) {
    companion object {
        val UNKNOWN = NormPerson(null, null, null)
    }
}

/** Where a value sits against the published references. Never a diagnosis. */
enum class NormVerdict {
    /** Within 1.5 SD of the healthy mean, or no worse than the healthy mean when no SD is published. */
    WITHIN_HEALTHY,
    /** More than 1.5 SD worse than the healthy mean. */
    OUTSIDE_HEALTHY_RANGE,
    /** Worse than the healthy average (the source gives no SD) but nearer to it than to the Parkinson's value. */
    WORSE_THAN_AVERAGE,
    /** Worse than the healthy average and nearer to the published Parkinson's value. */
    CLOSER_TO_PARKINSON,
    /** Past a published cut-off. */
    PAST_CUTOFF,
    /** Only a cut-off is published and the value has not passed it. */
    WITHIN_CUTOFF,
    /** Frequency in the physiological band (8-12 Hz). */
    PHYSIOLOGICAL_BAND,
    /** Frequency in the band of most pathological tremors (4-8 Hz). */
    PATHOLOGICAL_BAND,
    /** Frequency outside both bands. */
    OTHER_BAND,
    /** No published value fits this person (for example an age outside the studied range). */
    NO_REFERENCE,
}

/**
 * One measure compared with the references that fit the person. [zHealthy] and [zParkinson] are signed so that a
 * positive value is worse (slower, more irregular) than the group mean; null without a published SD.
 */
data class NormComparison(
    val metric: String,
    val value: Double,
    val healthy: NormEntry?,
    val parkinson: NormEntry?,
    val cutoff: NormEntry?,
    val zHealthy: Double?,
    val zParkinson: Double?,
    val verdict: NormVerdict,
)

/** Compares measured values with [norms] for a [NormPerson]. Pure, so it is unit-tested on the JVM. */
class NormComparator(private val norms: ReferenceNorms) {

    fun compare(metric: String, value: Double, person: NormPerson): NormComparison {
        val healthy = norms.find(metric, NormGroup.HEALTHY, person.age, person.sex)
        val parkinson = norms.find(metric, NormGroup.PARKINSON, person.age, person.sex)
        val cutoff = norms.find(metric, NormGroup.PARKINSON, person.age, person.sex, withCutoff = true)
            ?: norms.find(metric, NormGroup.HEALTHY, person.age, person.sex, withCutoff = true)
        val direction = (healthy ?: parkinson ?: cutoff)?.direction
        val zHealthy = healthy?.let { z(value, it) }
        val zParkinson = parkinson?.let { z(value, it) }
        val verdict = when {
            direction == null -> NormVerdict.NO_REFERENCE
            direction == NormDirection.BAND -> band(value, healthy, parkinson)
            cutoff != null && worse(value, cutoff.cutoff!!, direction) -> NormVerdict.PAST_CUTOFF
            zHealthy != null -> if (zHealthy > OUTSIDE_SD) NormVerdict.OUTSIDE_HEALTHY_RANGE else NormVerdict.WITHIN_HEALTHY
            healthy?.mean != null -> when {
                !worse(value, healthy.mean, direction) -> NormVerdict.WITHIN_HEALTHY
                parkinson?.mean != null && abs(value - parkinson.mean) < abs(value - healthy.mean) -> NormVerdict.CLOSER_TO_PARKINSON
                else -> NormVerdict.WORSE_THAN_AVERAGE
            }
            cutoff != null -> NormVerdict.WITHIN_CUTOFF
            else -> NormVerdict.NO_REFERENCE
        }
        return NormComparison(metric, value, healthy, parkinson, cutoff, zHealthy, zParkinson, verdict)
    }

    private fun worse(value: Double, reference: Double, direction: NormDirection): Boolean = when (direction) {
        NormDirection.HIGHER_IS_BETTER -> value < reference
        NormDirection.LOWER_IS_BETTER -> value > reference
        NormDirection.BAND -> false
    }

    private fun z(value: Double, e: NormEntry): Double? {
        val mean = e.mean ?: return null
        val sd = e.sd?.takeIf { it > 0.0 } ?: return null
        return when (e.direction) {
            NormDirection.HIGHER_IS_BETTER -> (mean - value) / sd
            NormDirection.LOWER_IS_BETTER -> (value - mean) / sd
            NormDirection.BAND -> null
        }
    }

    private fun band(value: Double, healthy: NormEntry?, parkinson: NormEntry?): NormVerdict = when {
        parkinson?.low != null && parkinson.high != null && value >= parkinson.low && value < parkinson.high -> NormVerdict.PATHOLOGICAL_BAND
        healthy?.low != null && healthy.high != null && value >= healthy.low && value <= healthy.high -> NormVerdict.PHYSIOLOGICAL_BAND
        else -> NormVerdict.OTHER_BAND
    }

    companion object {
        /** Same distance the gait score uses for its signs (about mean - 1.5 SD). */
        const val OUTSIDE_SD = 1.5
    }
}
