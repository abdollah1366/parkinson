package com.example.parkinson.speech

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Framing and level helpers shared by the analysers. Pure functions, no Android calls. */
object SignalFrames {

    /** Floor for level values of silent frames (dBFS). Silence is reported as this floor, never as a measured level. */
    const val SILENCE_FLOOR_DB = -120.0

    /** 16-bit PCM to floating point in [-1, 1). */
    fun toUnit(samples: ShortArray): DoubleArray = DoubleArray(samples.size) { samples[it] / 32768.0 }

    fun frameLength(sampleRateHz: Int, frameMs: Double): Int = max(1, (sampleRateHz * frameMs / 1000.0).roundToInt())

    fun hopLength(sampleRateHz: Int, hopMs: Double): Int = max(1, (sampleRateHz * hopMs / 1000.0).roundToInt())

    /** Number of complete frames of [frameLength] samples advancing by [hop]. */
    fun frameCount(sampleCount: Int, frameLength: Int, hop: Int): Int =
        if (sampleCount < frameLength) 0 else 1 + (sampleCount - frameLength) / hop

    /** RMS level of x[start until start + length] in dBFS (silence gives [SILENCE_FLOOR_DB]). */
    fun rmsDb(x: DoubleArray, start: Int, length: Int): Double {
        var sum = 0.0
        for (i in start until start + length) sum += x[i] * x[i]
        val rms = sqrt(sum / length)
        return if (rms <= 0.0) SILENCE_FLOOR_DB else max(SILENCE_FLOOR_DB, 20.0 * log10(rms))
    }

    /** Level in dBFS of every analysis frame; frame i starts at sample i x hop. */
    fun frameLevelsDb(x: DoubleArray, sampleRateHz: Int, frameMs: Double, hopMs: Double): DoubleArray {
        val length = frameLength(sampleRateHz, frameMs)
        val hop = hopLength(sampleRateHz, hopMs)
        val n = frameCount(x.size, length, hop)
        return DoubleArray(n) { rmsDb(x, it * hop, length) }
    }

    /** Share of samples at or above [level] in absolute value, in percent. */
    fun clippedPercent(samples: ShortArray, fullScaleFraction: Double): Double {
        if (samples.isEmpty()) return 0.0
        val limit = (32767 * fullScaleFraction).toInt()
        var clipped = 0
        for (s in samples) {
            val v = if (s.toInt() < 0) -s.toInt() else s.toInt()
            if (v >= limit) clipped++
        }
        return 100.0 * clipped / samples.size
    }

    fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()

    fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Linear-interpolated percentile (0..100) of finite values; null when there are none. */
    fun percentile(values: List<Double>, p: Double): Double? {
        val sorted = values.filter { it.isFinite() }.sorted()
        if (sorted.isEmpty()) return null
        val rank = (p / 100.0) * (sorted.size - 1)
        val lo = rank.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (rank - lo)
    }

    /** Population standard deviation; null with fewer than two values. */
    fun sd(values: List<Double>): Double? {
        if (values.size < 2) return null
        val m = values.average()
        return sqrt(values.sumOf { (it - m) * (it - m) } / values.size)
    }
}
