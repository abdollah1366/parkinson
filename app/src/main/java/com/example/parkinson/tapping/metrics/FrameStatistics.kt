package com.example.parkinson.tapping.metrics

import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.tapping.Stats
import com.example.parkinson.tapping.raw.FrameStatus
import com.example.parkinson.tapping.raw.TapFrame
import kotlin.math.max

/**
 * Technical statistics of the recorded frames (input to the quality layers). Every duration is
 * derived from the frame timestamps, not from the UI timer.
 */
data class FrameStatistics(
    /** MediaPipe results inside the recording window ("frames analyzed"). */
    val totalFrames: Int,
    /** Results with usable landmarks. */
    val validFrames: Int,
    val noHandFrames: Int,
    val multipleHandsFrames: Int,
    val outOfFrameFrames: Int,
    val errorFrames: Int,
    /** Per-frame left/right label vs the selected hand (frames with landmarks only). */
    val sideMatchesFrames: Int,
    val sideUncertainFrames: Int,
    val sideMismatchFrames: Int,
    /** Results per second actually delivered by camera + MediaPipe. */
    val fps: Double,
    val medianFrameIntervalMs: Double?,
    /** Periods without valid hand data longer than the dropout threshold (recording edges included). */
    val dropoutCount: Int,
    val dropoutTotalMs: Long,
    val longestDropoutMs: Long,
    /** Recording window covered by valid landmarks: window - dropout time. */
    val usableDurationMs: Long,
    /** First to last result (+ one frame interval): how long frames really arrived. */
    val observedDurationMs: Long,
    /** usable / window: the TIME share with valid landmarks (primary tracking measure). */
    val trackingRate: Double,
    val meanLuma: Float?,
    /** Camera frames dropped by CameraX while the analyzer was busy. */
    val cameraFramesSkipped: Int,
    /** Frames handed to MediaPipe that never produced a result (dropped while busy). */
    val pipelineFramesDropped: Int
) {
    val validFramePercent: Double get() = if (totalFrames > 0) validFrames * 100.0 / totalFrames else 0.0

    /** validFrames / totalFrames: count-based tracking rate (reported; quality uses [trackingRate]). */
    val frameTrackingRate: Double get() = if (totalFrames > 0) validFrames.toDouble() / totalFrames else 0.0
    fun fraction(count: Int): Double = if (totalFrames > 0) count.toDouble() / totalFrames else 0.0

    /** Share of the side-confident frames whose label is NOT the selected hand. */
    val sideMismatchShare: Double?
        get() {
            val confident = sideMatchesFrames + sideMismatchFrames
            return if (confident == 0) null else sideMismatchFrames.toDouble() / confident
        }
}

object FrameStatisticsCalculator {

    /** A gap without valid frames is a dropout when longer than max(this, 3 x median interval). */
    const val MIN_DROPOUT_MS = 150L

    fun compute(frames: List<TapFrame>, startMs: Long, endMs: Long): FrameStatistics {
        val sorted = frames.sortedBy { it.timestampMs }
        val intervals = sorted.zipWithNext { a, b -> (b.timestampMs - a.timestampMs).toDouble() }
        val medianInterval = Stats.median(intervals)
        val fps = if (sorted.size >= 2) {
            val span = sorted.last().timestampMs - sorted.first().timestampMs
            if (span > 0) (sorted.size - 1) * 1000.0 / span else 0.0
        } else 0.0

        val threshold = max(MIN_DROPOUT_MS.toDouble(), 3 * (medianInterval ?: 0.0))
        val validTimes = sorted.filter { it.isValid }.map { it.timestampMs }
        val gaps = ArrayList<Long>()
        if (validTimes.isEmpty()) {
            gaps += endMs - startMs
        } else {
            gaps += validTimes.first() - startMs
            validTimes.zipWithNext { a, b -> gaps += b - a }
            gaps += endMs - validTimes.last()
        }
        val dropouts = gaps.filter { it > threshold }
        val windowMs = (endMs - startMs).coerceAtLeast(0L)
        val usable = (windowMs - dropouts.sum()).coerceAtLeast(0L)

        val observed = if (sorted.size >= 2) {
            sorted.last().timestampMs - sorted.first().timestampMs + Math.round(medianInterval ?: 0.0)
        } else 0L

        val sequences = sorted.mapNotNull { it.sequence }
        val pipelineDropped = if (sequences.size >= 2) {
            ((sequences.max() - sequences.min() + 1) - sequences.size).toInt().coerceAtLeast(0)
        } else 0

        val lumas = sorted.mapNotNull { it.meanLuma }
        val withHand = sorted.filter { it.isValid }
        fun count(status: FrameStatus) = sorted.count { it.status == status }
        fun side(status: HandSideStatus) = withHand.count { it.sideStatus == status }
        return FrameStatistics(
            totalFrames = sorted.size,
            validFrames = count(FrameStatus.VALID),
            noHandFrames = count(FrameStatus.NO_HAND),
            multipleHandsFrames = count(FrameStatus.MULTIPLE_HANDS),
            outOfFrameFrames = count(FrameStatus.OUT_OF_FRAME),
            errorFrames = count(FrameStatus.ERROR),
            sideMatchesFrames = side(HandSideStatus.MATCHES),
            sideUncertainFrames = side(HandSideStatus.UNCERTAIN),
            sideMismatchFrames = side(HandSideStatus.MISMATCH),
            fps = fps,
            medianFrameIntervalMs = medianInterval,
            dropoutCount = dropouts.size,
            dropoutTotalMs = dropouts.sum(),
            longestDropoutMs = dropouts.maxOrNull() ?: 0L,
            usableDurationMs = usable,
            observedDurationMs = observed,
            trackingRate = if (windowMs > 0) usable.toDouble() / windowMs else 0.0,
            meanLuma = if (lumas.isEmpty()) null else lumas.average().toFloat(),
            cameraFramesSkipped = sorted.sumOf { it.cameraFramesSkipped },
            pipelineFramesDropped = pipelineDropped
        )
    }
}
