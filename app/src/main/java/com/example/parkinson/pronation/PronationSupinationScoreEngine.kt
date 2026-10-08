package com.example.parkinson.pronation

import com.example.parkinson.assessment.QualityStatus
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * "شاخص عملکرد حرکتی" (Motor Performance Index), kept separate from the raw metrics:
 *
 *   raw metric -> normalization ([ScoringReference], [PronationScoringConfig])
 *   -> component score 0..100 -> weighted combination -> index 0..100.
 *
 * Weights and ranges are ENGINEERING values and an INTERNAL SOFTWARE REFERENCE, not normative
 * data and not a clinical scale. Never computed for INVALID / INSUFFICIENT_DATA recordings; for
 * LOW_QUALITY recordings it is computed but marked [ReliabilityLevel.LIMITED].
 */
class PronationSupinationScoreEngine(
    private val config: PronationScoringConfig = PronationScoringConfig(),
    private val reference: ScoringReference = InternalSoftwareReference
) {

    fun score(metrics: PronationMetrics, quality: PronationQualityReport, context: ReferenceContext): PronationSupinationPerformanceScore? {
        if (!quality.isUsable) return null
        val amplitude = metrics.medianAmplitudeDeg ?: return null
        val trend = trend(metrics.segments, context)
        val components = ComponentScores(
            speed = speedScore(metrics.cyclesPerSecond, context),
            rhythm = metrics.cycleDurationCvPercent?.let { rhythmScore(it) },
            amplitude = amplitudeScore(amplitude, metrics.amplitudeCvPercent, context),
            consistency = consistencyScore(metrics.withinTolerancePercent, metrics.velocityCvPercent),
            trend = trendScore(trend)
        )
        return PronationSupinationPerformanceScore(
            total = combine(
                components.speed to config.weightSpeed,
                components.rhythm to config.weightRhythm,
                components.amplitude to config.weightAmplitude,
                components.consistency to config.weightConsistency,
                components.trend to config.weightTrend
            ),
            components = components,
            trend = trend,
            reliability = if (quality.status == QualityStatus.VALID) ReliabilityLevel.RELIABLE else ReliabilityLevel.LIMITED,
            referenceName = reference.name
        )
    }

    // --- Normalization (each 0..100, explainable) --------------------------------------------

    /** Slow -> lower, up to the reference speed -> 100. */
    fun speedScore(cyclesPerSecond: Double, context: ReferenceContext): Int =
        normalize(cyclesPerSecond, reference.speedRangeHz(context))

    /** Irregular cycle durations -> lower; 0 % variability -> 100. */
    fun rhythmScore(cycleCvPercent: Double): Int = toScore(1.0 - cycleCvPercent / config.maxCycleCvPercent)

    /** Very small or unstable amplitude -> lower; consistent adequate rotation -> higher. */
    fun amplitudeScore(medianAmplitudeDeg: Double, amplitudeCvPercent: Double?, context: ReferenceContext): Int {
        val size = normalize(medianAmplitudeDeg, reference.amplitudeRangeDeg(context)) / 100.0
        val stability = amplitudeCvPercent?.let { 1.0 - it / config.maxAmplitudeCvPercent }?.coerceIn(0.0, 1.0) ?: 1.0
        val share = config.amplitudeStabilityShare
        return toScore(size * ((1 - share) + share * stability))
    }

    /** High variability -> lower; movements like the typical one and stable peak speed -> higher. */
    fun consistencyScore(withinTolerancePercent: Double?, velocityCvPercent: Double?): Int? {
        val parts = listOfNotNull(
            withinTolerancePercent?.let { it / 100.0 },
            velocityCvPercent?.let { 1.0 - it / config.maxVelocityCvPercent }
        )
        return if (parts.isEmpty()) null else toScore(parts.map { it.coerceIn(0.0, 1.0) }.average())
    }

    /** Stable or improving -> 100; a decline beyond the tolerance lowers it, maxTrendDecline -> 0. */
    fun trendScore(trend: PerformanceTrend): Int? {
        if (trend.state == PerformanceTrendState.INSUFFICIENT_DATA) return null
        val decline = max(0, -(trend.scoreChange ?: 0)).toDouble()
        val over = max(0.0, decline - config.trendToleranceDecline)
        return toScore(1.0 - over / (config.maxTrendDecline - config.trendToleranceDecline))
    }

    // --- Performance trend -------------------------------------------------------------------

    /** Segment score: speed, rhythm, amplitude and consistency of one third (no trend part). */
    fun segmentScore(s: SegmentMetrics, context: ReferenceContext): Int? {
        val rate = s.cycleRateHz ?: return null
        val amplitude = s.medianAmplitudeDeg ?: return null
        return combine(
            speedScore(rate, context) to config.weightSpeed,
            s.durationCvPercent?.let { rhythmScore(it) } to config.weightRhythm,
            amplitudeScore(amplitude, s.amplitudeCvPercent, context) to config.weightAmplitude,
            consistencyScore(s.withinTolerancePercent, s.velocityCvPercent) to config.weightConsistency
        )
    }

    fun trend(segments: List<SegmentMetrics>, context: ReferenceContext): PerformanceTrend {
        val scores = TrendSegment.entries.map { seg -> segments.firstOrNull { it.segment == seg }?.let { segmentScore(it, context) } }
        val (early, middle, late) = scores
        if (early == null || late == null) return PerformanceTrend(PerformanceTrendState.INSUFFICIENT_DATA, early, middle, late, null)
        val change = late - early
        val state = when {
            change <= -config.trendChangePoints -> PerformanceTrendState.DECLINING
            change >= config.trendChangePoints -> PerformanceTrendState.IMPROVING
            else -> PerformanceTrendState.STABLE
        }
        return PerformanceTrend(state, early, middle, late, change)
    }

    // --- helpers -------------------------------------------------------------------------------

    /** Weighted mean of the available components (weights renormalized), clamped to 0..100. */
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
