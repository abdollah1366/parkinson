package com.example.parkinson.tremor

import android.util.Log
import com.example.parkinson.tapping.raw.FrameStatus
import java.util.Locale

/**
 * One diagnostic line per resting-hand recording (debuggable builds only, see ParkinsonApplication).
 * Counts, timing and the quality reasons only: no landmark coordinates, no images, no personal data.
 * Read-only: nothing here changes detection, quality or the stored result.
 */
object RestingTremorDiagnostics {

    const val TAG = "RTDiag"

    /** Off by default; ParkinsonApplication enables it only in debuggable builds. */
    @Volatile
    var enabled = false

    /** Logs [summary] when diagnostics are enabled. */
    fun report(resultsInWindow: Int, resultsOutsideWindow: Int, recording: RestingTremorRecording, analysis: RestingTremorAnalysis) {
        if (enabled) Log.d(TAG, summary(resultsInWindow, resultsOutsideWindow, recording, analysis))
    }

    /**
     * Pure text summary (no Android calls, so it can be unit-tested). [resultsInWindow] are MediaPipe
     * results whose timestamp fell inside the recording window; [resultsOutsideWindow] the rest.
     * An unavailable metric is printed as "unavailable", never as zero.
     */
    fun summary(
        resultsInWindow: Int,
        resultsOutsideWindow: Int,
        recording: RestingTremorRecording,
        analysis: RestingTremorAnalysis,
    ): String {
        val frames = recording.frames
        val statusCounts = FrameStatus.entries.joinToString(",") { s ->
            "${s.name}:${frames.count { it.status == s }}"
        }
        val nonMonotonicAt = (1 until frames.size).firstOrNull { frames[it].timestampMs < frames[it - 1].timestampMs }
        val issues = analysis.quality.issues.joinToString(",") { it.name }.ifEmpty { "none" }
        val head = "RECORDING resultsInWindow=$resultsInWindow resultsOutsideWindow=$resultsOutsideWindow " +
            "frames=${frames.size} status=[$statusCounts] nonMonotonicAt=${nonMonotonicAt ?: -1} " +
            "quality=${analysis.quality.status.name} issues=$issues"
        val m = analysis.metrics ?: return "$head metrics=unavailable"
        return "$head durationMs=${m.actualDurationMs} plannedMs=${m.plannedDurationMs} " +
            "validFrames=${m.validFrames} validPct=${f(m.validFramePercent)} " +
            "medianIntervalMs=${f(m.medianFrameIntervalMs)} rateHz=${f(m.frameRateHz)} " +
            "irregularPct=${f(m.irregularIntervalPercent)} interruptions=${m.interruptionCount} " +
            "longestInterruptionMs=${m.longestInterruptionMs} coveragePct=${f(m.signalCoveragePercent)} " +
            "driftHandLengths=${f(m.slowDriftPercent / 100.0, 3)} " +
            "amplitudePct=${m.amplitudeRmsPercent?.let { f(it) } ?: "unavailable"} " +
            "frequencyHz=${m.dominantFrequencyHz?.let { f(it) } ?: "unavailable"}"
    }

    private fun f(value: Double, decimals: Int = 2): String =
        if (value.isFinite()) String.format(Locale.US, "%.${decimals}f", value) else "NaN"
}
