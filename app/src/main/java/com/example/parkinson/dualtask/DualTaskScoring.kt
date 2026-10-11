package com.example.parkinson.dualtask

import com.example.parkinson.imu.GaitReference
import com.example.parkinson.imu.GaitScoring
import kotlin.math.roundToInt

/**
 * Serial subtraction by three, said aloud while walking. The person reports the last number they said once the walk
 * has ended (never while walking), and the number of answers follows from the start number.
 */
object SerialSubtraction {
    const val STEP = 3

    /** Start numbers with a matching voice clip (res/raw/voice_dual_start_N). */
    val START_NUMBERS = listOf(99, 98, 96, 94, 92, 91)

    /**
     * Answers given from [start] down to the reported [last] number. [exact] is false when the difference is not a
     * multiple of three, so at least one answer was wrong. Null when [last] is not below or equal to [start].
     */
    fun answers(start: Int, last: Int): Answers? {
        if (last > start || last < start - 3 * 60) return null
        val diff = start - last
        return Answers(count = diff / STEP, exact = diff % STEP == 0)
    }

    data class Answers(val count: Int, val exact: Boolean)
}

/**
 * Reference values of the dual-task test. Sources (see docs/dual-task.md):
 *
 * - Srygley et al., Brain Res 2009;1253:92-99: 276 healthy adults aged 70-90, serial 3 subtractions for 2 minutes:
 *   44.96 (SD 16.86) subtractions while walking, about 22 per minute; usual gait speed 1.24 m/s, 1.11 m/s while
 *   subtracting (about 10 % slower; a speed cost, cadence usually drops less).
 * - Priest et al., J Neuroeng Rehabil 2008;5:29: healthy women aged about 80 slowed by 30 % while counting backwards,
 *   so healthy costs vary widely.
 * - Yogev et al., Eur J Neurosci 2005;22:1248 (PhysioNet "Gait in Parkinson's Disease"): gait variability rises with
 *   dual tasking in Parkinson's disease, not in age-matched controls.
 * - Vitorio et al., J Parkinsons Dis 2021;11:653: the gait-speed dual-task cost separates Parkinson's disease from
 *   controls only weakly (AUC 0.54, alphabet task). So the cost is one domain of three, not the whole score.
 *
 * Bounds marked ENGINEERING are choices around these values, not validated cut-offs.
 */
object DualTaskReference {
    /** Answers per minute while walking: full score at the healthy older-adult mean (Srygley 2009). */
    const val ANSWERS_HEALTHY_PER_MIN = 22.0

    /** ENGINEERING: about the healthy mean minus 1.2 SD (8.4 per minute). */
    const val ANSWERS_SIGN_PER_MIN = 12.0
    const val ANSWERS_ZERO_PER_MIN = 4.0

    /** Cadence cost (percent slower): full score up to the healthy gait-speed cost (about 10 %, Srygley 2009). */
    const val CADENCE_COST_HEALTHY = 10.0

    /** ENGINEERING sign and zero bounds. */
    const val CADENCE_COST_SIGN = 20.0
    const val CADENCE_COST_ZERO = 35.0

    /** Stride-time variability rise (percentage points) that, with a dual-task CV above the PD bound, is a sign. */
    const val VARIABILITY_RISE_SIGN = 1.5

    /** A silence of at least this long while counting (ms) is a long pause. */
    const val LONG_PAUSE_MS = 3_000.0
}

enum class DualTaskDomain { DUAL_TASK_GAIT, ANSWERS, CADENCE_COST }

enum class DualTaskSign {
    /** Fewer answers per minute than the healthy range. */
    SLOW_ANSWERS,

    /** Walking slowed much more than in healthy older adults. */
    LARGE_GAIT_COST,

    /** Step timing became irregular only while thinking (reported more often in Parkinson's disease). */
    VARIABILITY_RISES,

    /** Several long silences while counting (counted from the microphone). */
    LONG_PAUSES,

    /** At least two of the above together. */
    PARKINSONIAN_PATTERN,
}

data class DualTaskScore(val overall: Int?, val domains: Map<DualTaskDomain, Int>, val signs: Set<DualTaskSign>)

/** Gait values of one walk that the dual-task comparison uses. */
data class WalkSummary(
    val cadence: Double?,
    val strideTimeMs: Double?,
    val strideCvPercent: Double?,
    val stepAsymmetryPercent: Double?,
    val meanTurnVelocityDegPerSec: Double?,
)

object DualTaskScoring {
    const val VERSION = "dual-task-score-0.1-preliminary"

    /** Percent by which [dual] is lower than [single] (positive: slower). Null when either is missing. */
    fun costPercent(single: Double?, dual: Double?): Double? =
        if (single == null || dual == null || single <= 0.0) null else 100.0 * (single - dual) / single

    fun score(
        single: WalkSummary,
        dual: WalkSummary,
        answersPerMinute: Double?,
        longPauses: Int?,
    ): DualTaskScore {
        val domains = LinkedHashMap<DualTaskDomain, Int>()
        val signs = LinkedHashSet<DualTaskSign>()

        GaitScoring.score(
            cadence = dual.cadence,
            strideCvPercent = dual.strideCvPercent,
            stepAsymmetryPercent = dual.stepAsymmetryPercent,
            meanTurnVelocityDegPerSec = dual.meanTurnVelocityDegPerSec,
            walkingTremorIndex = null,
            standingTremorIndex = null,
        ).overall?.let { domains[DualTaskDomain.DUAL_TASK_GAIT] = it }

        answersPerMinute?.let {
            domains[DualTaskDomain.ANSWERS] =
                GaitScoring.linear(it, DualTaskReference.ANSWERS_HEALTHY_PER_MIN, DualTaskReference.ANSWERS_ZERO_PER_MIN)
            if (it < DualTaskReference.ANSWERS_SIGN_PER_MIN) signs += DualTaskSign.SLOW_ANSWERS
        }

        costPercent(single.cadence, dual.cadence)?.let {
            domains[DualTaskDomain.CADENCE_COST] = GaitScoring.linear(
                it.coerceAtLeast(0.0), DualTaskReference.CADENCE_COST_HEALTHY, DualTaskReference.CADENCE_COST_ZERO,
            )
            if (it > DualTaskReference.CADENCE_COST_SIGN) signs += DualTaskSign.LARGE_GAIT_COST
        }

        val rise = if (single.strideCvPercent != null && dual.strideCvPercent != null) {
            dual.strideCvPercent - single.strideCvPercent
        } else {
            null
        }
        if (rise != null && rise >= DualTaskReference.VARIABILITY_RISE_SIGN &&
            dual.strideCvPercent!! > GaitReference.STRIDE_CV_SIGN
        ) {
            signs += DualTaskSign.VARIABILITY_RISES
        }
        if (longPauses != null && longPauses >= 2) signs += DualTaskSign.LONG_PAUSES
        if (signs.size >= 2) signs += DualTaskSign.PARKINSONIAN_PATTERN

        val overall = if (domains.size >= 2) domains.values.average().roundToInt() else null
        return DualTaskScore(overall, domains, signs)
    }
}
