package com.example.parkinson.sensors

/**
 * Maps timestamps from one clock (for example SensorEvent.timestamp, nanoseconds) onto another (for
 * example camera-side milliseconds) using a constant offset estimated from paired events.
 *
 * Method: for every pair (sensor event, camera event that describes the same physical event, such as a
 * hand or foot touching a reference mark) the difference `camera - sensor` is computed. The offset is the
 * median of these differences, and the uncertainty is half the interquartile range. A constant offset is
 * only valid for a short recording; the caller must check [Offset.uncertaintyMs] before comparing events.
 *
 * Limits: MediaPipe callback time is NOT the camera exposure time, so the camera side carries latency
 * that this class cannot remove. Both clocks must share one time base (elapsedRealtime-based) before
 * the offset is estimated; mixing SystemClock.uptimeMillis with sensor timestamps gives a wrong offset.
 */
class SensorTimeSynchronizer(
    /** Uncertainty above this (ms) makes the mapping unreliable. */
    private val maxUncertaintyMs: Double = 20.0,
) {

    data class Offset(
        /** camera_ms = sensor_ms + offsetMs */
        val offsetMs: Double,
        /** Half the interquartile range of the paired differences (ms). */
        val uncertaintyMs: Double,
        val pairCount: Int,
    )

    sealed interface Result {
        data class Mapped(val offset: Offset) : Result
        /** Too few pairs, or the uncertainty is too large: cross-modal comparison is unavailable. */
        data class Unavailable(val reason: String) : Result
    }

    /** [sensorTimesNs] and [cameraTimesMs] are paired by index (same physical event). */
    fun estimate(sensorTimesNs: List<Long>, cameraTimesMs: List<Double>): Result {
        if (sensorTimesNs.size != cameraTimesMs.size) return Result.Unavailable("unpaired events")
        if (sensorTimesNs.size < MIN_PAIRS) return Result.Unavailable("too few paired events")
        val diffs = sensorTimesNs.indices.map { cameraTimesMs[it] - sensorTimesNs[it] / 1e6 }.sorted()
        val median = quantile(diffs, 0.5)
        val iqr = quantile(diffs, 0.75) - quantile(diffs, 0.25)
        val offset = Offset(offsetMs = median, uncertaintyMs = iqr / 2.0, pairCount = diffs.size)
        return if (offset.uncertaintyMs <= maxUncertaintyMs) Result.Mapped(offset)
        else Result.Unavailable("uncertainty ${"%.1f".format(offset.uncertaintyMs)} ms exceeds $maxUncertaintyMs ms")
    }

    /** Maps a sensor timestamp onto the camera clock. */
    fun toCameraMs(offset: Offset, sensorTimeNs: Long): Double = sensorTimeNs / 1e6 + offset.offsetMs

    private fun quantile(sorted: List<Double>, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val lo = kotlin.math.floor(pos).toInt()
        val hi = kotlin.math.ceil(pos).toInt()
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }

    companion object {
        const val MIN_PAIRS = 5
    }
}
