package com.example.parkinson.sensors

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Timing and validity of one sensor stream, measured from its own timestamps. */
data class StreamStats(
    val type: MotionSensorType,
    val receivedSamples: Int,
    val validSamples: Int,
    val validPercent: Double,
    val spanMs: Double,
    /** Measured, not requested: (n - 1) / span. 0 with fewer than 2 samples. */
    val samplingRateHz: Double,
    val medianIntervalMs: Double,
    val intervalSdMs: Double?,
    val dropoutCount: Int,
    /** Time missing in gaps, beyond one normal interval per gap. */
    val dropoutTotalMs: Double,
    val longestGapMs: Double,
    val completenessPercent: Double,
    val unreliablePercent: Double
) {
    val dropoutShare: Double get() = if (spanMs > 0) dropoutTotalMs / spanMs else 1.0
}

/**
 * Cleaning and timing statistics shared by the IMU tests (Hand Stability, Pronation/Supination).
 * The sampling rate is never assumed: everything is measured from the event timestamps.
 */
object MotionStreams {

    const val RAD_TO_DEG = 180.0 / PI

    /** +-16 g: beyond any phone accelerometer range = corrupt sample. */
    const val ACCEL_LIMIT = 16 * 9.80665

    /** 2000 deg/s, the usual gyroscope full scale. */
    const val GYRO_LIMIT_RAD_S = 2000.0 / RAD_TO_DEG

    /** A dropout is an interval longer than max(GAP_FACTOR x median interval, MIN_GAP_MS). */
    const val GAP_FACTOR = 3.0
    const val MIN_GAP_MS = 40.0

    fun limitFor(type: MotionSensorType): Double =
        if (type == MotionSensorType.ACCELEROMETER) ACCEL_LIMIT else GYRO_LIMIT_RAD_S

    /** Drops non-finite, physically implausible and out-of-order/duplicate samples; sorted by time. */
    fun clean(samples: List<MotionSample>, limit: Double): List<MotionSample> {
        val plausible = samples.filter {
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite() &&
                abs(it.x) <= limit && abs(it.y) <= limit && abs(it.z) <= limit
        }.sortedBy { it.timestampNs }
        val out = ArrayList<MotionSample>(plausible.size)
        for (s in plausible) {
            if (out.isEmpty() || s.timestampNs > out.last().timestampNs) out += s
        }
        return out
    }

    fun streamStats(
        type: MotionSensorType,
        raw: List<MotionSample>,
        valid: List<MotionSample>,
        plannedMs: Long
    ): StreamStats {
        val n = valid.size
        val validPercent = if (raw.isEmpty()) 0.0 else n * 100.0 / raw.size
        val unreliablePercent = if (n == 0) 0.0 else valid.count { it.unreliable } * 100.0 / n
        if (n < 2) {
            return StreamStats(type, raw.size, n, validPercent, 0.0, 0.0, 0.0, null, 0, 0.0, 0.0, 0.0, unreliablePercent)
        }
        val intervals = DoubleArray(n - 1) { (valid[it + 1].timestampNs - valid[it].timestampNs) / 1e6 }
        val sorted = intervals.sorted()
        val median = percentile(sorted, 0.5)
        val spanMs = (valid.last().timestampNs - valid.first().timestampNs) / 1e6
        val gapThreshold = max(GAP_FACTOR * median, MIN_GAP_MS)
        var dropouts = 0
        var dropoutMs = 0.0
        for (i in intervals) {
            if (i > gapThreshold) {
                dropouts++
                dropoutMs += i - median
            }
        }
        val mean = intervals.average()
        val sd = if (intervals.size >= 2) sqrt(intervals.sumOf { (it - mean) * (it - mean) } / (intervals.size - 1)) else null
        val completeness = if (plannedMs > 0) min(100.0, (spanMs + median) * 100.0 / plannedMs) else 0.0
        return StreamStats(
            type = type,
            receivedSamples = raw.size,
            validSamples = n,
            validPercent = validPercent,
            spanMs = spanMs,
            samplingRateHz = if (spanMs > 0) (n - 1) * 1000.0 / spanMs else 0.0,
            medianIntervalMs = median,
            intervalSdMs = sd,
            dropoutCount = dropouts,
            dropoutTotalMs = dropoutMs,
            longestGapMs = sorted.last(),
            completenessPercent = completeness,
            unreliablePercent = unreliablePercent
        )
    }

    /** Linear-interpolated percentile of an ascending list (p in 0..1). */
    fun percentile(sorted: List<Double>, p: Double): Double {
        if (sorted.size == 1) return sorted[0]
        val pos = p * (sorted.size - 1)
        val lo = floor(pos).toInt()
        val hi = min(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
