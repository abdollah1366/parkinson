package com.example.parkinson.tapping.metrics

import com.example.parkinson.tapping.Stats
import com.example.parkinson.tapping.detection.DetectionResult
import com.example.parkinson.tapping.detection.TapEvent
import kotlin.math.abs
import kotlin.math.max

/** Early / middle / late thirds of the recording. Values are null when a third has too few taps. */
data class TrendWindows(
    val early: Double?,
    val middle: Double?,
    val late: Double?,
    /** (late - early) / early x 100. Negative = lower at the end. A measured trend only. */
    val relativeChangePercent: Double?
)

/** One third (early / middle / late) of the recording. null = too few taps in that third. */
data class TapSegmentMetrics(
    val segment: Int,
    val tapCount: Int,
    val rateHz: Double,
    val meanAmplitude: Double?,
    val amplitudeCvPercent: Double?,
    val intervalCvPercent: Double?
)

/** METRICS layer: measured values only, no interpretation. Amplitudes are in palm sizes. */
data class MotorMetrics(
    val plannedDurationMs: Long,
    val recordingDurationMs: Long,
    val tapCount: Int,
    /** tapCount / recording duration. */
    val tapRateHz: Double,
    val tapsPer10Seconds: Double,
    val meanIntervalMs: Double?,
    val medianIntervalMs: Double?,
    val intervalSdMs: Double?,
    val intervalCvPercent: Double?,
    /** Mean absolute difference between consecutive intervals / mean interval x 100. */
    val tapToTapVariabilityPercent: Double?,
    val meanAmplitude: Double?,
    val medianAmplitude: Double?,
    val amplitudeSd: Double?,
    val amplitudeCvPercent: Double?,
    val minAmplitude: Double?,
    val maxAmplitude: Double?,
    val meanTapDurationMs: Double?,
    val medianTapDurationMs: Double?,
    /** 100 - mean(interval CV, amplitude CV), clamped to 0..100. Engineering composite. */
    val movementConsistencyPercent: Double?,
    /** Intervals longer than 2 x the median interval. */
    val pauseCount: Int,
    val amplitudeTrend: TrendWindows,
    /** Taps per second in each third. */
    val rateTrend: TrendWindows,
    /** Linear-regression slope of amplitude over time, as % of the mean amplitude per second. */
    val amplitudeSlopePercentPerSecond: Double?,
    val meanEventConfidence: Double?,
    /** Mean closing speed of the taps, palm sizes per second. */
    val meanClosingVelocity: Double?,
    val closingVelocityCvPercent: Double?,
    /** Early / middle / late thirds (taps assigned by their closing time). */
    val segments: List<TapSegmentMetrics>,
    val frames: FrameStatistics,
    /** Recorded duration / planned duration x 100 (max 100). */
    val recordingCompletenessPercent: Double,
    /** Share of the recording covered by valid hand data (100 - dropout share). */
    val validCoveragePercent: Double
)

object MotorMetricsCalculator {

    private const val PAUSE_FACTOR = 2.0
    private const val MIN_TAPS_PER_WINDOW = 2

    fun compute(
        detection: DetectionResult,
        frames: FrameStatistics,
        startMs: Long,
        endMs: Long,
        plannedDurationMs: Long
    ): MotorMetrics {
        val taps = detection.events
        val durationMs = (endMs - startMs).coerceAtLeast(0L)
        val durationS = durationMs / 1000.0
        val n = taps.size
        val rate = if (durationS > 0) n / durationS else 0.0

        val intervals = taps.zipWithNext { a, b -> (b.timestampMs - a.timestampMs).toDouble() }
        val meanInterval = Stats.mean(intervals)
        val medianInterval = Stats.median(intervals)
        val intervalCv = Stats.cvPercent(intervals)
        val successive = intervals.zipWithNext { a, b -> abs(b - a) }
        val tapToTap = if (successive.isNotEmpty() && meanInterval != null && meanInterval > 0) {
            successive.average() / meanInterval * 100.0
        } else null

        val amplitudes = taps.map { it.amplitude }
        val amplitudeCv = Stats.cvPercent(amplitudes)
        val consistency = if (intervalCv != null && amplitudeCv != null) {
            (100.0 - (intervalCv + amplitudeCv) / 2.0).coerceIn(0.0, 100.0)
        } else null

        val pauses = if (intervals.size >= 3 && medianInterval != null) {
            intervals.count { it > medianInterval * PAUSE_FACTOR }
        } else 0

        val dropoutShare = if (durationMs > 0) frames.dropoutTotalMs.toDouble() / durationMs else 1.0

        return MotorMetrics(
            plannedDurationMs = plannedDurationMs,
            recordingDurationMs = durationMs,
            tapCount = n,
            tapRateHz = rate,
            tapsPer10Seconds = rate * 10.0,
            meanIntervalMs = meanInterval,
            medianIntervalMs = medianInterval,
            intervalSdMs = Stats.sampleSd(intervals),
            intervalCvPercent = intervalCv,
            tapToTapVariabilityPercent = tapToTap,
            meanAmplitude = Stats.mean(amplitudes),
            medianAmplitude = Stats.median(amplitudes),
            amplitudeSd = Stats.sampleSd(amplitudes),
            amplitudeCvPercent = amplitudeCv,
            minAmplitude = amplitudes.minOrNull(),
            maxAmplitude = amplitudes.maxOrNull(),
            meanTapDurationMs = Stats.mean(taps.map { it.durationMs.toDouble() }),
            medianTapDurationMs = Stats.median(taps.map { it.durationMs.toDouble() }),
            movementConsistencyPercent = consistency,
            pauseCount = pauses,
            amplitudeTrend = amplitudeTrend(taps, startMs, durationMs),
            rateTrend = rateTrend(taps, startMs, durationMs),
            amplitudeSlopePercentPerSecond = amplitudeSlope(taps, startMs),
            meanEventConfidence = Stats.mean(taps.map { it.confidence }),
            meanClosingVelocity = Stats.mean(taps.map { it.closingVelocity }),
            closingVelocityCvPercent = Stats.cvPercent(taps.map { it.closingVelocity }),
            segments = segments(taps, startMs, durationMs),
            frames = frames,
            recordingCompletenessPercent = if (plannedDurationMs > 0) {
                (durationMs * 100.0 / plannedDurationMs).coerceAtMost(100.0)
            } else 0.0,
            validCoveragePercent = ((1.0 - dropoutShare) * 100.0).coerceIn(0.0, 100.0)
        )
    }

    private fun segments(taps: List<TapEvent>, startMs: Long, durationMs: Long): List<TapSegmentMetrics> =
        (0..2).map { w ->
            val inThird = taps.filter { thirdOf(it, startMs, durationMs) == w }
            val intervals = inThird.zipWithNext { a, b -> (b.timestampMs - a.timestampMs).toDouble() }
            val amplitudes = inThird.map { it.amplitude }
            TapSegmentMetrics(
                segment = w,
                tapCount = inThird.size,
                rateHz = if (durationMs > 0) inThird.size / (durationMs / 3000.0) else 0.0,
                meanAmplitude = if (inThird.size >= MIN_TAPS_PER_WINDOW) amplitudes.average() else null,
                amplitudeCvPercent = Stats.cvPercent(amplitudes),
                intervalCvPercent = Stats.cvPercent(intervals)
            )
        }

    private fun thirdOf(tap: TapEvent, startMs: Long, durationMs: Long): Int {
        if (durationMs <= 0) return 0
        val rel = (tap.timestampMs - startMs).toDouble() / durationMs
        return (rel * 3).toInt().coerceIn(0, 2)
    }

    private fun amplitudeTrend(taps: List<TapEvent>, startMs: Long, durationMs: Long): TrendWindows {
        val groups = (0..2).map { w -> taps.filter { thirdOf(it, startMs, durationMs) == w }.map { it.amplitude } }
        val means = groups.map { if (it.size >= MIN_TAPS_PER_WINDOW) it.average() else null }
        return trend(means)
    }

    private fun rateTrend(taps: List<TapEvent>, startMs: Long, durationMs: Long): TrendWindows {
        if (durationMs <= 0) return TrendWindows(null, null, null, null)
        val thirdS = durationMs / 3000.0
        val rates = (0..2).map { w -> taps.count { thirdOf(it, startMs, durationMs) == w } / thirdS }
        return trend(rates)
    }

    private fun trend(values: List<Double?>): TrendWindows {
        val early = values[0]
        val late = values[2]
        val change = if (early != null && late != null && early > 0) (late - early) / early * 100.0 else null
        return TrendWindows(early, values[1], late, change)
    }

    private fun amplitudeSlope(taps: List<TapEvent>, startMs: Long): Double? {
        if (taps.size < 4) return null
        val xs = taps.map { (it.timestampMs - startMs) / 1000.0 }
        val ys = taps.map { it.amplitude }
        val mx = xs.average()
        val my = ys.average()
        val sxx = xs.sumOf { (it - mx) * (it - mx) }
        if (sxx <= 0 || my <= 0) return null
        val sxy = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) }
        return sxy / sxx / my * 100.0
    }
}
