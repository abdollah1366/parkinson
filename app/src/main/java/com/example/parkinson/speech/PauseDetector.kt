package com.example.parkinson.speech

/** Pause measurements inside the speech span. Null values mean "not measurable", never zero. */
data class PauseSummary(
    /** From the first to the last active frame (ms); null when no speech was found. */
    val speechSpanMs: Double?,
    /** Pauses of at least [SpeechSignalConfig.minPauseMs] inside the span. */
    val pauseCount: Int,
    val meanPauseMs: Double?,
    val longPauseCount: Int,
    /** Share of the speech span taken by long pauses (percent). Null without a speech span. */
    val longPauseRatioPercent: Double?,
    val pauseStartsMs: List<Double>,
    val pauseEndsMs: List<Double>,
)

/**
 * Pauses are inactive runs strictly between the first and the last active frame. Leading and trailing silence
 * is not a pause (it is the time before the person starts or after they stop). A run is a pause when it lasts at
 * least [SpeechSignalConfig.minPauseMs]; a pause of at least [SpeechSignalConfig.longPauseMs] is a long pause.
 */
object PauseDetector {

    fun summarize(activity: ActivityTrack, config: SpeechSignalConfig = SpeechSignalConfig()): PauseSummary {
        val active = activity.active
        val first = active.indexOfFirst { it }
        val last = active.indexOfLast { it }
        if (first < 0) {
            return PauseSummary(null, 0, null, 0, null, emptyList(), emptyList())
        }
        val hop = activity.hopMs
        val span = (last - first + 1) * hop

        val starts = ArrayList<Double>()
        val ends = ArrayList<Double>()
        var i = first
        while (i <= last) {
            if (!active[i]) {
                val runStart = i
                while (i <= last && !active[i]) i++
                val runFrames = i - runStart
                val duration = runFrames * hop
                if (duration >= config.minPauseMs) {
                    starts += runStart * hop
                    ends += i * hop
                }
            } else {
                i++
            }
        }
        val durations = starts.indices.map { ends[it] - starts[it] }
        val longTotal = durations.filter { it >= config.longPauseMs }.sum()
        val longCount = durations.count { it >= config.longPauseMs }
        return PauseSummary(
            speechSpanMs = span,
            pauseCount = durations.size,
            meanPauseMs = SignalFrames.mean(durations),
            longPauseCount = longCount,
            longPauseRatioPercent = 100.0 * longTotal / span,
            pauseStartsMs = starts,
            pauseEndsMs = ends,
        )
    }
}
