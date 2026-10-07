package com.example.parkinson.tapping.signal

import com.example.parkinson.tapping.Stats
import com.example.parkinson.tapping.raw.TapFrame
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Parameters of the SIGNAL PROCESSING layer. Opening values are in "hand scale" units
 * (thumb-index distance / palm size), so no parameter is a pixel value.
 */
data class SignalConfig(
    /** Palm size is taken as the median over +-this window to suppress per-frame jitter. */
    val handScaleHalfWindowMs: Long = 500L,
    /** Openings above this are landmark glitches (a real thumb-index opening is ~0.5-2 palms). */
    val maxPlausibleOpening: Double = 3.0,
    /** Gaps without valid frames up to this length are bridged; longer gaps split the signal. */
    val maxBridgeGapMs: Long = 250L,
    /** Time constant of the zero-phase exponential smoother. */
    val smoothingTauMs: Double = 25.0,
    /** Adaptive envelope (baseline and peak level) is computed over +-this window. */
    val envelopeHalfWindowMs: Long = 1_500L,
    val envelopeLowPercentile: Double = 0.05,
    val envelopeHighPercentile: Double = 0.95,
    /** With fewer samples in the window the envelope of the whole recording is used. */
    val minEnvelopeSamples: Int = 8,
    /** Movement smaller than this (in palm sizes) is never counted as tapping. */
    val minMovementRange: Double = 0.15,
    /** The movement floor is also at least this many robust noise sigmas... */
    val noiseFloorMultiplier: Double = 6.0,
    /** ...but never more than this, so heavy noise cannot hide real large taps. */
    val maxMovementFloor: Double = 0.40,
    /** Hysteresis thresholds as fractions of the local envelope range. */
    val closeFraction: Double = 0.35,
    val openFraction: Double = 0.65
)

/** One processed sample of the tapping signal. */
data class SignalSample(
    val frameIndex: Int,
    val timestampMs: Long,
    /** Thumb-index distance / palm size. */
    val raw: Double,
    val smoothed: Double,
    /** Adaptive baseline (fingers closed level). */
    val low: Double,
    /** Adaptive peak level (fingers open level). */
    val high: Double,
    /** True when the local range is large enough to be movement rather than noise. */
    val active: Boolean,
    /** First sample after a dropout longer than [SignalConfig.maxBridgeGapMs] (or the first sample). */
    val segmentStart: Boolean,
    val confidence: Double
) {
    val range: Double get() = high - low
}

data class ProcessedSignal(
    val samples: List<SignalSample>,
    val closeFraction: Double,
    val openFraction: Double,
    /** Robust estimate of frame-to-frame noise, in palm sizes. */
    val noiseSigma: Double,
    /** Minimum local range treated as movement. */
    val movementFloor: Double,
    /** Coefficient of variation of the palm size, in percent (hand moving towards/away). */
    val handScaleCvPercent: Double?,
    /** Valid frames rejected as implausible landmark glitches. */
    val rejectedOutliers: Int,
    val segmentCount: Int
) {
    fun closeThreshold(s: SignalSample): Double = s.low + closeFraction * s.range
    fun openThreshold(s: SignalSample): Double = s.low + openFraction * s.range
}

/**
 * SIGNAL PROCESSING: builds the normalized, smoothed tapping signal with an adaptive envelope
 * from the raw frames of a complete recording. Pure function of its inputs.
 */
object TapSignalProcessor {

    fun process(frames: List<TapFrame>, config: SignalConfig = SignalConfig()): ProcessedSignal {
        val valid = frames.filter {
            it.isValid && it.handScalePx.isFinite() && it.handScalePx > 0.0 && it.thumbIndexDistancePx.isFinite()
        }.sortedBy { it.timestampMs }

        val scales = stableHandScales(valid, config.handScaleHalfWindowMs)

        // Normalize and drop implausible glitches.
        val times = ArrayList<Long>(valid.size)
        val raw = ArrayList<Double>(valid.size)
        val kept = ArrayList<TapFrame>(valid.size)
        var outliers = 0
        valid.forEachIndexed { i, frame ->
            val opening = frame.thumbIndexDistancePx / scales[i]
            if (!opening.isFinite() || opening > config.maxPlausibleOpening) {
                outliers++
            } else {
                times += frame.timestampMs
                raw += opening
                kept += frame
            }
        }

        val n = raw.size
        if (n == 0) {
            return ProcessedSignal(
                emptyList(), config.closeFraction, config.openFraction, 0.0, config.minMovementRange,
                null, outliers, 0
            )
        }

        // Segments: split at gaps that are too long to bridge.
        val segmentStart = BooleanArray(n)
        segmentStart[0] = true
        for (i in 1 until n) segmentStart[i] = times[i] - times[i - 1] > config.maxBridgeGapMs
        val segmentCount = segmentStart.count { it }

        val smoothed = zeroPhaseSmooth(times, raw, segmentStart, config.smoothingTauMs)

        val noiseSigma = Stats.robustSigma(raw.indices.map { raw[it] - smoothed[it] }) ?: 0.0
        val floor = min(
            max(config.minMovementRange, config.noiseFloorMultiplier * noiseSigma),
            max(config.maxMovementFloor, config.minMovementRange)
        )

        val (low, high) = envelope(times, smoothed, config)

        val samples = List(n) { i ->
            SignalSample(
                frameIndex = kept[i].index,
                timestampMs = times[i],
                raw = raw[i],
                smoothed = smoothed[i],
                low = low[i],
                high = high[i],
                active = high[i] - low[i] >= floor,
                segmentStart = segmentStart[i],
                confidence = kept[i].confidence
            )
        }

        return ProcessedSignal(
            samples = samples,
            closeFraction = config.closeFraction,
            openFraction = config.openFraction,
            noiseSigma = noiseSigma,
            movementFloor = floor,
            handScaleCvPercent = Stats.cvPercent(valid.map { it.handScalePx }),
            rejectedOutliers = outliers,
            segmentCount = segmentCount
        )
    }

    /** Median palm size of the valid frames within +-[halfWindowMs] of each frame. */
    private fun stableHandScales(valid: List<TapFrame>, halfWindowMs: Long): DoubleArray {
        val result = DoubleArray(valid.size)
        var from = 0
        var to = 0
        for (i in valid.indices) {
            val t = valid[i].timestampMs
            while (valid[from].timestampMs < t - halfWindowMs) from++
            while (to < valid.size && valid[to].timestampMs <= t + halfWindowMs) to++
            val window = ArrayList<Double>(to - from)
            for (j in from until to) window += valid[j].handScalePx
            result[i] = Stats.median(window) ?: valid[i].handScalePx
        }
        return result
    }

    /**
     * Forward + backward exponential smoothing (zero phase lag), restarted at every segment.
     * alpha depends on the real frame interval, so irregular frame timing is handled.
     */
    private fun zeroPhaseSmooth(
        times: List<Long>,
        values: List<Double>,
        segmentStart: BooleanArray,
        tauMs: Double
    ): DoubleArray {
        val n = values.size
        val forward = DoubleArray(n)
        for (i in 0 until n) {
            forward[i] = if (segmentStart[i]) {
                values[i]
            } else {
                val a = alpha(times[i] - times[i - 1], tauMs)
                a * values[i] + (1 - a) * forward[i - 1]
            }
        }
        val backward = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            val segmentEnd = i == n - 1 || segmentStart[i + 1]
            backward[i] = if (segmentEnd) {
                forward[i]
            } else {
                val a = alpha(times[i + 1] - times[i], tauMs)
                a * forward[i] + (1 - a) * backward[i + 1]
            }
        }
        return backward
    }

    private fun alpha(dtMs: Long, tauMs: Double): Double =
        if (tauMs <= 0.0) 1.0 else 1.0 - exp(-max(dtMs, 1L) / tauMs)

    /** Adaptive baseline/peak: percentiles of the smoothed signal in a centered window. */
    private fun envelope(times: List<Long>, smoothed: DoubleArray, config: SignalConfig): Pair<DoubleArray, DoubleArray> {
        val n = smoothed.size
        val globalSorted = smoothed.sorted()
        val globalLow = Stats.percentileOfSorted(globalSorted, config.envelopeLowPercentile)
        val globalHigh = Stats.percentileOfSorted(globalSorted, config.envelopeHighPercentile)

        val low = DoubleArray(n)
        val high = DoubleArray(n)
        val scratch = DoubleArray(n)
        var from = 0
        var to = 0
        for (i in 0 until n) {
            val t = times[i]
            while (times[from] < t - config.envelopeHalfWindowMs) from++
            while (to < n && times[to] <= t + config.envelopeHalfWindowMs) to++
            val count = to - from
            if (count < config.minEnvelopeSamples) {
                low[i] = globalLow
                high[i] = globalHigh
            } else {
                System.arraycopy(smoothed, from, scratch, 0, count)
                scratch.sort(0, count)
                low[i] = Stats.percentileOfSorted(scratch, count, config.envelopeLowPercentile)
                high[i] = Stats.percentileOfSorted(scratch, count, config.envelopeHighPercentile)
            }
        }
        return low to high
    }

    /** Exposed for the live counter so both paths use the same smoothing law. */
    internal fun smoothingAlpha(dtMs: Long, tauMs: Double): Double = alpha(dtMs, tauMs)
}
