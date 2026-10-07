package com.example.parkinson.tapping

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.sqrt

/** Small deterministic statistics helpers shared by the analysis layers. */
internal object Stats {

    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.sum() / values.size

    fun median(values: List<Double>): Double? = if (values.isEmpty()) null else percentileOfSorted(values.sorted(), 0.5)

    /** Sample standard deviation (n - 1). Needs at least 2 values. */
    fun sampleSd(values: List<Double>): Double? {
        if (values.size < 2) return null
        val m = values.sum() / values.size
        val variance = values.sumOf { (it - m) * (it - m) } / (values.size - 1)
        return sqrt(variance)
    }

    /** Coefficient of variation in percent. Needs at least 2 values and a positive mean. */
    fun cvPercent(values: List<Double>): Double? {
        val m = mean(values) ?: return null
        val sd = sampleSd(values) ?: return null
        return if (m > 0.0) sd / m * 100.0 else null
    }

    /** Linear-interpolated percentile, [p] in 0..1, [sorted] ascending and non-empty. */
    fun percentileOfSorted(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = p.coerceIn(0.0, 1.0) * (sorted.size - 1)
        val lower = floor(pos).toInt()
        val upper = (lower + 1).coerceAtMost(sorted.size - 1)
        val fraction = pos - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    /** Same as [percentileOfSorted] for the first [count] entries of a sorted array. */
    fun percentileOfSorted(sorted: DoubleArray, count: Int, p: Double): Double {
        if (count == 1) return sorted[0]
        val pos = p.coerceIn(0.0, 1.0) * (count - 1)
        val lower = floor(pos).toInt()
        val upper = (lower + 1).coerceAtMost(count - 1)
        val fraction = pos - lower
        return sorted[lower] + (sorted[upper] - sorted[lower]) * fraction
    }

    /** Robust standard deviation estimate: 1.4826 x median absolute deviation. */
    fun robustSigma(values: List<Double>): Double? {
        val med = median(values) ?: return null
        val mad = median(values.map { abs(it - med) }) ?: return null
        return 1.4826 * mad
    }
}
