package com.example.parkinson.speech

/**
 * Acoustic energy events: local peaks of the smoothed frame level above the noise floor. Each syllable of a
 * rapid repetition usually produces one energy peak, so the peak rate estimates the repetition rate. This does
 * NOT identify syllables or phonemes: a peak may be a syllable, a click or a noise burst. The result is reported
 * as "acoustic energy events" and is unavailable when fewer than [MIN_EVENTS] peaks are found.
 */
data class EventSummary(
    val eventTimesMs: List<Double>,
    /** Events per second over the span from the first to the last event; null with fewer than [MIN_EVENTS] events. */
    val rateHz: Double?,
    /** Standard deviation / mean of the intervals between events (percent). Null with fewer than [MIN_EVENTS] events. */
    val intervalCvPercent: Double?,
) {
    val count: Int get() = eventTimesMs.size

    companion object {
        const val MIN_EVENTS = 5
    }
}

object SyllableEventDetector {

    fun detect(activity: ActivityTrack, config: SpeechSignalConfig = SpeechSignalConfig()): EventSummary {
        val noise = activity.noiseFloorDb ?: return EventSummary(emptyList(), null, null)
        val hop = activity.hopMs
        val level = activity.levelDb
        val smooth = DoubleArray(level.size) { i ->
            val a = level[maxOf(0, i - 1)]
            val b = level[i]
            val c = level[minOf(level.size - 1, i + 1)]
            (a + b + c) / 3.0
        }
        val threshold = noise + config.eventMarginDb
        val neighbourhood = maxOf(1, (config.minEventSpacingMs / 2.0 / hop).toInt())

        // Candidate peaks: above threshold, local maximum, and prominent over the surrounding minimum.
        val candidates = ArrayList<Int>()
        for (i in 1 until smooth.size - 1) {
            if (smooth[i] < threshold || smooth[i] < smooth[i - 1] || smooth[i] < smooth[i + 1]) continue
            var low = smooth[i]
            for (j in maxOf(0, i - neighbourhood)..minOf(smooth.size - 1, i + neighbourhood)) {
                if (smooth[j] < low) low = smooth[j]
            }
            if (smooth[i] - low >= config.eventProminenceDb) candidates += i
        }

        // Enforce the minimum spacing: strongest peaks first, then keep those far enough from every kept peak.
        val minSpacingFrames = config.minEventSpacingMs / hop
        val kept = ArrayList<Int>()
        for (i in candidates.sortedByDescending { smooth[it] }) {
            if (kept.none { kotlin.math.abs(it - i) < minSpacingFrames }) kept += i
        }
        kept.sort()
        val times = kept.map { it * hop }

        if (times.size < EventSummary.MIN_EVENTS) return EventSummary(times, null, null)
        val span = times.last() - times.first()
        val intervals = times.zipWithNext { a, b -> b - a }
        val mean = intervals.average()
        val cv = SignalFrames.sd(intervals)?.let { 100.0 * it / mean }
        return EventSummary(
            eventTimesMs = times,
            rateHz = if (span > 0) (times.size - 1) * 1000.0 / span else null,
            intervalCvPercent = cv,
        )
    }
}
