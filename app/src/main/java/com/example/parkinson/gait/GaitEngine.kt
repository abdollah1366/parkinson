package com.example.parkinson.gait

import com.example.parkinson.assessment.QualityStatus
import kotlin.math.abs
import kotlin.math.sqrt

/** The pose frames of one walking recording, in arrival order, plus the planned length. */
data class GaitRecording(
    val frames: List<PoseFrame>,
    val plannedDurationMs: Long,
)

/**
 * Data-quality findings. Any INVALID finding makes the recording INVALID, otherwise any INSUFFICIENT_DATA
 * finding, otherwise any LOW_QUALITY finding. None of them describes the person's gait.
 */
enum class GaitQualityIssue(val status: QualityStatus) {
    /** Timestamps went backwards: the clock data cannot be trusted. */
    NON_MONOTONIC_TIMESTAMPS(QualityStatus.INVALID),

    TOO_FEW_FRAMES(QualityStatus.INSUFFICIENT_DATA),
    RECORDING_TOO_SHORT(QualityStatus.INSUFFICIENT_DATA),
    LOW_VALID_FRAMES(QualityStatus.INSUFFICIENT_DATA),
    LONG_GAP(QualityStatus.INSUFFICIENT_DATA),
    LOW_FRAME_RATE(QualityStatus.INSUFFICIENT_DATA),
    /** Fewer steps than needed: cadence and step-time variability cannot be measured. */
    TOO_FEW_STEPS(QualityStatus.INSUFFICIENT_DATA),

    REDUCED_VALID_FRAMES(QualityStatus.LOW_QUALITY),
    FREQUENT_GAPS(QualityStatus.LOW_QUALITY),
}

data class GaitQualityReport(
    val status: QualityStatus,
    val issues: List<GaitQualityIssue>,
) {
    val primaryIssue: GaitQualityIssue? get() = issues.firstOrNull()

    /** Measurements may be shown and stored only for VALID or LOW_QUALITY recordings. */
    val isUsable: Boolean get() = status == QualityStatus.VALID || status == QualityStatus.LOW_QUALITY

    companion object {
        fun of(issues: Collection<GaitQualityIssue>): GaitQualityReport {
            val sorted = issues.distinct().sortedBy { it.ordinal }
            val status = when {
                sorted.any { it.status == QualityStatus.INVALID } -> QualityStatus.INVALID
                sorted.any { it.status == QualityStatus.INSUFFICIENT_DATA } -> QualityStatus.INSUFFICIENT_DATA
                sorted.any { it.status == QualityStatus.LOW_QUALITY } -> QualityStatus.LOW_QUALITY
                else -> QualityStatus.VALID
            }
            return GaitQualityReport(status, sorted)
        }
    }
}

/**
 * Measurements of one walking recording. Each optional metric is null when it could not be measured
 * (never zero). Lengths are in torso lengths (image plane, dimensionless), angles in degrees.
 */
data class GaitMetrics(
    val plannedDurationMs: Long,
    /** Last minus first frame timestamp (ms). */
    val actualDurationMs: Long,
    val totalFrames: Int,
    val validFrames: Int,
    /** Valid frames / all frames, percent. */
    val validFramePercent: Double,
    /** Longest gap between valid frames, including the head and tail of the recording (ms). */
    val longestGapMs: Long,
    /** Number of gaps longer than the interruption limit. */
    val gapCount: Int,
    val medianFrameIntervalMs: Double,
    /** 1000 / [medianFrameIntervalMs] (Hz). */
    val frameRateHz: Double,
    /** Detected step events (ms, relative to the first frame). Count: [stepCount]. */
    val stepTimesMs: List<Long>,
    /** Steps detected in the valid frames. */
    val stepCount: Int,
    /** (steps - 1) x 60 000 / (last step - first step) (steps per minute). Null with fewer than [GaitQualityThresholds.minSteps] steps. */
    val cadenceStepsPerMinute: Double?,
    /** Standard deviation / mean of the step intervals, percent. Null with fewer than [GaitQualityThresholds.minSteps] steps. */
    val stepIntervalCvPercent: Double?,
    /** Mean absolute trunk inclination from the image vertical (degrees). Image-plane, uncalibrated. */
    val trunkLeanMeanDeg: Double?,
    /** Range (max - min) of the left wrist offset from the shoulder, torso lengths. Null when too few frames show the wrist. */
    val leftArmSwingRangeTorso: Double?,
    val rightArmSwingRangeTorso: Double?,
)

data class GaitAnalysis(
    /** Null when the recording was too broken to measure at all. */
    val metrics: GaitMetrics?,
    val quality: GaitQualityReport,
)

/**
 * Walking analysis from the camera pose. Steps:
 *  1. Quality checks on timestamps, frame counts, valid share, gaps, frame rate.
 *  2. Valid frames are split into segments at gaps longer than the interruption limit (no bridging).
 *  3. Ankle separation (right minus left ankle x, torso lengths) is smoothed and its absolute value is
 *     peak-picked with hysteresis. Each peak is one step event.
 *  4. Cadence and step-interval variability from the step events; trunk lean and arm-swing range from the
 *     valid frames.
 *
 * Nothing here is a clinical gait measure, and no plantar-pressure data is used. The camera sees only the
 * image plane, so all values are uncalibrated.
 */
class GaitEngine(
    val signal: GaitSignalConfig = GaitSignalConfig(),
    private val thresholds: GaitQualityThresholds = GaitQualityThresholds(),
) {

    fun analyze(recording: GaitRecording): GaitAnalysis {
        val frames = recording.frames
        val t = thresholds
        for (i in 1 until frames.size) {
            if (frames[i].timestampMs < frames[i - 1].timestampMs) {
                return GaitAnalysis(null, GaitQualityReport.of(listOf(GaitQualityIssue.NON_MONOTONIC_TIMESTAMPS)))
            }
        }
        if (frames.size < t.minFrames) {
            return GaitAnalysis(null, GaitQualityReport.of(listOf(GaitQualityIssue.TOO_FEW_FRAMES)))
        }
        val valid = frames.filter { it.status == PoseFrameStatus.VALID && it.features != null }
        if (valid.size < 2) {
            return GaitAnalysis(null, GaitQualityReport.of(listOf(GaitQualityIssue.LOW_VALID_FRAMES)))
        }

        val intervals = DoubleArray(frames.size - 1) { (frames[it + 1].timestampMs - frames[it].timestampMs).toDouble() }
        val medianDt = median(intervals)
        if (medianDt <= 0.0) {
            return GaitAnalysis(null, GaitQualityReport.of(listOf(GaitQualityIssue.LOW_FRAME_RATE)))
        }
        val frameRate = 1000.0 / medianDt
        val origin = frames.first().timestampMs
        val actualDurationMs = frames.last().timestampMs - origin
        val validPercent = 100.0 * valid.size / frames.size

        // Gaps: head and tail count too, so a late start or an early stop is visible.
        val gaps = ArrayList<Long>(valid.size + 1)
        gaps += valid.first().timestampMs - origin
        for (i in 1 until valid.size) gaps += valid[i].timestampMs - valid[i - 1].timestampMs
        gaps += frames.last().timestampMs - valid.last().timestampMs
        val interruptions = gaps.filter { it > t.interruptionGapMs }
        val longestGap = gaps.maxOrNull() ?: 0L

        val segments = segmentsOf(valid)
        val stepTimes = detectSteps(segments).map { it - origin }
        val stepCount = stepTimes.size
        val enoughSteps = stepCount >= t.minSteps

        val absoluteStepTimes = stepTimes.map { it + origin }
        val cadence = if (enoughSteps) {
            (stepCount - 1) * 60_000.0 / (absoluteStepTimes.last() - absoluteStepTimes.first())
        } else null
        val cv = if (enoughSteps) {
            val stepIntervals = (1 until absoluteStepTimes.size).map { (absoluteStepTimes[it] - absoluteStepTimes[it - 1]).toDouble() }
            val mean = stepIntervals.average()
            val sd = sqrt(stepIntervals.sumOf { (it - mean) * (it - mean) } / stepIntervals.size)
            if (mean > 0) 100.0 * sd / mean else null
        } else null

        val leftWrist = valid.mapNotNull { it.features?.leftWristOffset }
        val rightWrist = valid.mapNotNull { it.features?.rightWristOffset }
        val lean = valid.mapNotNull { it.features?.trunkInclinationDeg }.map { abs(it) }
        val metrics = GaitMetrics(
            plannedDurationMs = recording.plannedDurationMs,
            actualDurationMs = actualDurationMs,
            totalFrames = frames.size,
            validFrames = valid.size,
            validFramePercent = validPercent,
            longestGapMs = longestGap,
            gapCount = interruptions.size,
            medianFrameIntervalMs = medianDt,
            frameRateHz = frameRate,
            stepTimesMs = stepTimes,
            stepCount = stepCount,
            cadenceStepsPerMinute = cadence,
            stepIntervalCvPercent = cv,
            trunkLeanMeanDeg = if (lean.isEmpty()) null else lean.average(),
            leftArmSwingRangeTorso = range(leftWrist, t.minWristFrames),
            rightArmSwingRangeTorso = range(rightWrist, t.minWristFrames),
        )

        val issues = ArrayList<GaitQualityIssue>()
        if (actualDurationMs < t.minRecordingFraction * recording.plannedDurationMs) {
            issues += GaitQualityIssue.RECORDING_TOO_SHORT
        }
        if (validPercent < t.minValidFramePercent) {
            issues += GaitQualityIssue.LOW_VALID_FRAMES
        } else if (validPercent < t.goodValidFramePercent) {
            issues += GaitQualityIssue.REDUCED_VALID_FRAMES
        }
        if (longestGap > t.maxGapMs) issues += GaitQualityIssue.LONG_GAP
        if (interruptions.size > t.frequentInterruptions) issues += GaitQualityIssue.FREQUENT_GAPS
        if (frameRate < t.minFrameRateHz) issues += GaitQualityIssue.LOW_FRAME_RATE
        if (!enoughSteps) issues += GaitQualityIssue.TOO_FEW_STEPS

        return GaitAnalysis(metrics, GaitQualityReport.of(issues))
    }

    /** Consecutive valid frames, split where the gap exceeds the interruption limit. */
    private fun segmentsOf(valid: List<PoseFrame>): List<List<PoseFrame>> {
        val out = ArrayList<MutableList<PoseFrame>>()
        for (f in valid) {
            val current = out.lastOrNull()
            if (current == null || f.timestampMs - current.last().timestampMs > thresholds.interruptionGapMs) {
                out += mutableListOf(f)
            } else {
                current += f
            }
        }
        return out
    }

    /**
     * Step events: peaks of the smoothed absolute ankle separation, picked with hysteresis
     * ([GaitSignalConfig.minStepPeakTorso]) inside each segment. Returns absolute timestamps (ms).
     */
    private fun detectSteps(segments: List<List<PoseFrame>>): List<Long> {
        val steps = ArrayList<Long>()
        val hysteresis = signal.minStepPeakTorso
        for (seg in segments) {
            if (seg.size < 3) continue
            val raw = seg.map { abs(it.features!!.ankleSeparationTorso) }
            val smooth = movingAverage(raw, signal.smoothingFrames)
            var rising = true
            var peakValue = Double.NEGATIVE_INFINITY
            var peakTime = 0L
            var troughValue = Double.POSITIVE_INFINITY
            for (i in seg.indices) {
                val v = smooth[i]
                val t = seg[i].timestampMs
                if (rising) {
                    if (v > peakValue) {
                        peakValue = v
                        peakTime = t
                    }
                    if (peakValue - v >= hysteresis) {
                        val last = steps.lastOrNull()
                        if (last == null || peakTime - last >= signal.minStepIntervalMs) steps += peakTime
                        rising = false
                        troughValue = v
                    }
                } else {
                    if (v < troughValue) troughValue = v
                    if (v - troughValue >= hysteresis) {
                        rising = true
                        peakValue = v
                        peakTime = t
                    }
                }
            }
        }
        return steps
    }

    private fun movingAverage(x: List<Double>, window: Int): List<Double> {
        val half = (window / 2).coerceAtLeast(0)
        return x.indices.map { i ->
            val from = maxOf(0, i - half)
            val to = minOf(x.size - 1, i + half)
            (from..to).sumOf { x[it] } / (to - from + 1)
        }
    }

    private fun range(values: List<Double>, minCount: Int): Double? =
        if (values.size < minCount) null else values.max() - values.min()

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
