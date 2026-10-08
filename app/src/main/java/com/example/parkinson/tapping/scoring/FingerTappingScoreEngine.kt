package com.example.parkinson.tapping.scoring

import com.example.parkinson.assessment.MotorPerformanceBand
import com.example.parkinson.assessment.PerformanceTrendState
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.tapping.FingerTappingVersions
import com.example.parkinson.tapping.metrics.MotorMetrics
import com.example.parkinson.tapping.metrics.TapSegmentMetrics
import com.example.parkinson.tapping.quality.QualityReport
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * ENGINEERING weights and an INTERNAL SOFTWARE REFERENCE. They are NOT clinical cut-offs and
 * have NOT been validated against normative or patient data; they only map each measurement to
 * 0..100 transparently so a person can be compared with their own earlier tests.
 */
data class ScoringConfig(
    val weightSpeed: Double = 0.30,
    val weightRegularity: Double = 0.25,
    val weightAmplitude: Double = 0.20,
    val weightConsistency: Double = 0.15,
    val weightTrend: Double = 0.10,
    /** Tap rate mapped to 0 and to 100 (Hz). 0.5 Hz = 5 taps in 10 s; 5 Hz is fast tapping. */
    val speedRangeHz: ClosedFloatingPointRange<Double> = 0.5..5.0,
    /** Interval CV mapped to 0 (0 % = 100). */
    val maxIntervalCvPercent: Double = 50.0,
    /** Mean opening (palm sizes) mapped to 0 and to 100. 0.15 = the detector's movement minimum. */
    val amplitudeRange: ClosedFloatingPointRange<Double> = 0.15..1.0,
    /** A segment-score decline up to this is tolerated (trend 100); [maxTrendDecline] gives 0. */
    val trendToleranceDecline: Double = 5.0,
    val maxTrendDecline: Double = 40.0,
    /** Late vs early segment-score change (points) that counts as IMPROVING / DECLINING. */
    val trendChangePoints: Int = 10,
    /** A third needs at least this many taps for its segment score. */
    val minTapsPerSegment: Int = 3,
    /** A component below this is mentioned in the interpretation as lower. */
    val lowComponentScore: Int = 60,
    val referenceName: String = "internal-software-reference-ft-1.0"
)

/**
 * "شاخص عملکرد حرکتی" (Motor Performance Index) of the Finger Tapping test, 0..100.
 * Components are 0..100; null = could not be measured (left out, weights renormalized).
 * NOT a diagnosis, NOT a disease probability, NOT clinically validated.
 */
data class PerformanceScore(
    val total: Int,
    /** Tap speed. */
    val rate: Int,
    /** Tap regularity (rhythm). */
    val rhythm: Int?,
    val amplitude: Int,
    val consistency: Int?,
    /** Performance-trend component. */
    val trend: Int?,
    val trendState: PerformanceTrendState,
    val earlyScore: Int?,
    val middleScore: Int?,
    val lateScore: Int?,
    val reliability: ReliabilityLevel,
    val referenceName: String,
    val scoringVersion: String = FingerTappingVersions.SCORING_VERSION,
    val isClinicallyValidated: Boolean = false
) {
    val band: MotorPerformanceBand get() = MotorPerformanceBand.forScore(total)
}

/**
 * SCORING layer, separate from metrics and quality:
 *   raw metric -> normalization -> component 0..100 -> weighted combination -> 0..100.
 * Pure and deterministic. INVALID / INSUFFICIENT_DATA are never scored; LOW_QUALITY is scored
 * with [ReliabilityLevel.LIMITED].
 */
class FingerTappingScoreEngine(private val config: ScoringConfig = ScoringConfig()) {

    fun score(metrics: MotorMetrics, quality: QualityReport): PerformanceScore? {
        if (!quality.isUsable) return null
        val amplitude = metrics.meanAmplitude ?: return null
        val trend = trend(metrics.segments)
        val speed = speedScore(metrics.tapRateHz)
        val rhythm = metrics.intervalCvPercent?.let { regularityScore(it) }
        val amp = amplitudeScore(amplitude)
        val consistency = metrics.movementConsistencyPercent?.let { toScore(it / 100.0) }
        val trendScore = trendScore(trend)
        return PerformanceScore(
            total = combine(
                speed to config.weightSpeed,
                rhythm to config.weightRegularity,
                amp to config.weightAmplitude,
                consistency to config.weightConsistency,
                trendScore to config.weightTrend
            ),
            rate = speed,
            rhythm = rhythm,
            amplitude = amp,
            consistency = consistency,
            trend = trendScore,
            trendState = trend.state,
            earlyScore = trend.early,
            middleScore = trend.middle,
            lateScore = trend.late,
            reliability = if (quality.status == QualityStatus.VALID) ReliabilityLevel.RELIABLE else ReliabilityLevel.LIMITED,
            referenceName = config.referenceName
        )
    }

    /** Slow -> lower; the reference upper rate -> 100. */
    fun speedScore(rateHz: Double): Int = normalize(rateHz, config.speedRangeHz)

    /** Irregular intervals -> lower; 0 % CV -> 100. */
    fun regularityScore(intervalCvPercent: Double): Int = toScore(1.0 - intervalCvPercent / config.maxIntervalCvPercent)

    /** Small openings -> lower. */
    fun amplitudeScore(meanAmplitude: Double): Int = normalize(meanAmplitude, config.amplitudeRange)

    data class Trend(val state: PerformanceTrendState, val early: Int?, val middle: Int?, val late: Int?, val change: Int?)

    /** Segment score: speed, regularity and amplitude of one third. */
    fun segmentScore(s: TapSegmentMetrics): Int? {
        if (s.tapCount < config.minTapsPerSegment) return null
        val amplitude = s.meanAmplitude ?: return null
        return combine(
            speedScore(s.rateHz) to config.weightSpeed,
            s.intervalCvPercent?.let { regularityScore(it) } to config.weightRegularity,
            amplitudeScore(amplitude) to config.weightAmplitude
        )
    }

    fun trend(segments: List<TapSegmentMetrics>): Trend {
        val scores = (0..2).map { i -> segments.firstOrNull { it.segment == i }?.let { segmentScore(it) } }
        val (early, middle, late) = scores
        if (early == null || late == null) return Trend(PerformanceTrendState.INSUFFICIENT_DATA, early, middle, late, null)
        val change = late - early
        val state = when {
            change <= -config.trendChangePoints -> PerformanceTrendState.DECLINING
            change >= config.trendChangePoints -> PerformanceTrendState.IMPROVING
            else -> PerformanceTrendState.STABLE
        }
        return Trend(state, early, middle, late, change)
    }

    /** Stable or improving -> 100; a decline beyond the tolerance lowers it. */
    fun trendScore(trend: Trend): Int? {
        val change = trend.change ?: return null
        val over = max(0.0, -change - config.trendToleranceDecline)
        return toScore(1.0 - over / (config.maxTrendDecline - config.trendToleranceDecline))
    }

    private fun combine(vararg parts: Pair<Int?, Double>): Int {
        val present = parts.mapNotNull { (score, weight) -> score?.let { it to weight } }
        val weightSum = present.sumOf { it.second }
        if (weightSum <= 0.0) return 0
        return (present.sumOf { it.first * it.second } / weightSum).roundToInt().coerceIn(0, 100)
    }

    private fun normalize(value: Double, range: ClosedFloatingPointRange<Double>): Int =
        toScore((value - range.start) / (range.endInclusive - range.start))

    private fun toScore(ratio: Double): Int = (ratio.coerceIn(0.0, 1.0) * 100.0).roundToInt()
}

/** Language-neutral interpretation codes; the UI turns them into Persian sentences. */
enum class TappingNote {
    SPEED_AND_RHYTHM_GOOD,
    SPEED_OR_RHYTHM_LOWER,
    AMPLITUDE_LOWER,
    TREND_STABLE,
    TREND_DECLINING,
    TREND_IMPROVING,
    QUALITY_GOOD,
    QUALITY_LIMITED,
    LIGHTING_WARNING,
    REPEAT_RECOMMENDED
}

data class TappingInterpretation(
    val band: MotorPerformanceBand?,
    val reliability: ReliabilityLevel,
    val notes: List<TappingNote>
)

/** Interpretation from score and quality. Never diagnostic. */
object FingerTappingInterpreter {

    fun interpret(score: PerformanceScore?, quality: QualityReport, config: ScoringConfig = ScoringConfig()): TappingInterpretation {
        if (score == null) {
            return TappingInterpretation(null, ReliabilityLevel.NOT_RELIABLE, listOf(TappingNote.REPEAT_RECOMMENDED))
        }
        val low = config.lowComponentScore
        val notes = mutableListOf<TappingNote>()
        notes += if (score.rate >= low && (score.rhythm ?: low) >= low) TappingNote.SPEED_AND_RHYTHM_GOOD else TappingNote.SPEED_OR_RHYTHM_LOWER
        if (score.amplitude < low) notes += TappingNote.AMPLITUDE_LOWER
        when (score.trendState) {
            PerformanceTrendState.STABLE -> notes += TappingNote.TREND_STABLE
            PerformanceTrendState.DECLINING -> notes += TappingNote.TREND_DECLINING
            PerformanceTrendState.IMPROVING -> notes += TappingNote.TREND_IMPROVING
            PerformanceTrendState.INSUFFICIENT_DATA -> Unit
        }
        notes += if (quality.status == QualityStatus.VALID) TappingNote.QUALITY_GOOD else TappingNote.QUALITY_LIMITED
        if (quality.lightingWarning) notes += TappingNote.LIGHTING_WARNING
        if (quality.status != QualityStatus.VALID) notes += TappingNote.REPEAT_RECOMMENDED
        return TappingInterpretation(score.band, score.reliability, notes)
    }
}
