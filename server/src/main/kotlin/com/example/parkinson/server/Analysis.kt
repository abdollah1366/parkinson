package com.example.parkinson.server

import kotlinx.serialization.Serializable

/**
 * How one test (and hand) of one patient has gone over time, from the performance index (0..100, higher is better).
 * Engineering summary to help the doctor read the results, not a diagnosis.
 */
@Serializable
data class TestAnalysis(
    val type: String,
    val hand: String? = null,
    /** All results of this test, scored or not. */
    val count: Int,
    /** Results with a performance index (valid recordings). */
    val scoredCount: Int,
    val firstEpochMs: Long,
    val lastEpochMs: Long,
    /** Mean of the first scored results (up to 3); null with fewer than [Analysis.MIN_FOR_CHANGE] scored results. */
    val baseline: Double? = null,
    /** Mean of the latest scored results (up to 3), not overlapping [baseline]. */
    val recent: Double? = null,
    /** [recent] − [baseline], in index points. */
    val change: Double? = null,
    val onMean: Double? = null,
    val offMean: Double? = null,
    val onCount: Int = 0,
    val offCount: Int = 0,
    /** Least-squares trend in index points per 30 days; null with fewer than 3 scored results or under a week. */
    val slopePer30Days: Double? = null,
    /** The latest results are [Analysis.ATTENTION_DROP] points or more below the first ones. */
    val attention: Boolean = false,
)

/** One scored or unscored result, as the analysis needs it. */
data class ScorePoint(
    val type: String,
    val hand: String?,
    val epochMs: Long,
    val performanceIndex: Int?,
    val medicationState: String?,
)

object Analysis {
    const val MIN_FOR_CHANGE = 4
    const val ATTENTION_DROP = 10.0
    private const val DAY_MS = 86_400_000.0

    /** One summary per test and hand, in a stable order (test, then hand). */
    fun summarize(points: List<ScorePoint>): List<TestAnalysis> =
        points.groupBy { it.type to it.hand }
            .map { (key, group) -> summarizeOne(key.first, key.second, group.sortedBy { it.epochMs }) }
            .sortedWith(compareBy({ it.type }, { it.hand ?: "" }))

    private fun summarizeOne(type: String, hand: String?, all: List<ScorePoint>): TestAnalysis {
        val scored = all.filter { it.performanceIndex != null }
        val values = scored.map { it.performanceIndex!!.toDouble() }
        var baseline: Double? = null
        var recent: Double? = null
        if (values.size >= MIN_FOR_CHANGE) {
            val k = minOf(3, values.size / 2)
            baseline = values.take(k).average()
            recent = values.takeLast(k).average()
        }
        val change = if (baseline != null && recent != null) recent - baseline else null
        val on = scored.filter { it.medicationState == "on" }.map { it.performanceIndex!!.toDouble() }
        val off = scored.filter { it.medicationState == "off" }.map { it.performanceIndex!!.toDouble() }
        return TestAnalysis(
            type = type,
            hand = hand,
            count = all.size,
            scoredCount = scored.size,
            firstEpochMs = all.first().epochMs,
            lastEpochMs = all.last().epochMs,
            baseline = baseline?.round1(),
            recent = recent?.round1(),
            change = change?.round1(),
            onMean = on.takeIf { it.isNotEmpty() }?.average()?.round1(),
            offMean = off.takeIf { it.isNotEmpty() }?.average()?.round1(),
            onCount = on.size,
            offCount = off.size,
            slopePer30Days = slope(scored)?.round1(),
            attention = change != null && change <= -ATTENTION_DROP,
        )
    }

    private fun slope(scored: List<ScorePoint>): Double? {
        if (scored.size < 3) return null
        val t0 = scored.first().epochMs
        val xs = scored.map { (it.epochMs - t0) / DAY_MS }
        if (xs.last() < 7.0) return null
        val ys = scored.map { it.performanceIndex!!.toDouble() }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx == 0.0) return null
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        return sxy / sxx * 30.0
    }

    private fun Double.round1(): Double = Math.round(this * 10.0) / 10.0
}
