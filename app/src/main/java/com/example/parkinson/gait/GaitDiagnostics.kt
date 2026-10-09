package com.example.parkinson.gait

import android.util.Log
import java.util.Locale

/**
 * One diagnostic line per walking recording (debuggable builds only, see ParkinsonApplication). Counts,
 * timing and quality reasons only: no keypoint coordinates, no images, no personal data.
 */
object GaitDiagnostics {

    const val TAG = "GaitDiag"

    /** Off by default; ParkinsonApplication enables it only in debuggable builds. */
    @Volatile
    var enabled = false

    fun report(resultsInWindow: Int, resultsOutsideWindow: Int, recording: GaitRecording, analysis: GaitAnalysis) {
        if (enabled) Log.d(TAG, summary(resultsInWindow, resultsOutsideWindow, recording, analysis))
    }

    /**
     * Pure text summary (no Android calls, so it can be unit-tested). An unavailable metric is printed as
     * "unavailable", never as zero.
     */
    fun summary(resultsInWindow: Int, resultsOutsideWindow: Int, recording: GaitRecording, analysis: GaitAnalysis): String {
        val frames = recording.frames
        val statusCounts = PoseFrameStatus.entries.joinToString(",") { s ->
            "${s.name}:${frames.count { it.status == s }}"
        }
        val nonMonotonicAt = (1 until frames.size).firstOrNull { frames[it].timestampMs < frames[it - 1].timestampMs }
        val issues = analysis.quality.issues.joinToString(",") { it.name }.ifEmpty { "none" }
        val head = "RECORDING resultsInWindow=$resultsInWindow resultsOutsideWindow=$resultsOutsideWindow " +
            "frames=${frames.size} status=[$statusCounts] nonMonotonicAt=${nonMonotonicAt ?: -1} " +
            "quality=${analysis.quality.status.name} issues=$issues"
        val m = analysis.metrics ?: return "$head metrics=unavailable"
        return "$head durationMs=${m.actualDurationMs} plannedMs=${m.plannedDurationMs} " +
            "validPct=${f(m.validFramePercent)} longestGapMs=${m.longestGapMs} gaps=${m.gapCount} " +
            "rateHz=${f(m.frameRateHz)} steps=${m.stepCount} " +
            "cadence=${m.cadenceStepsPerMinute?.let { f(it) } ?: "unavailable"} " +
            "stepCvPct=${m.stepIntervalCvPercent?.let { f(it) } ?: "unavailable"}"
    }

    private fun f(value: Double, decimals: Int = 2): String =
        if (value.isFinite()) String.format(Locale.US, "%.${decimals}f", value) else "NaN"
}
