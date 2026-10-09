package com.example.parkinson.openclose

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.mediapipe.HandTrackingQuality
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.Stats
import com.example.parkinson.tapping.raw.FrameStatus
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** Everything captured during RECORDING. Frames are raw; nothing is pre-processed. */
data class OpenCloseRecording(
    val frames: List<OpenCloseFrame>,
    val startMs: Long,
    val endMs: Long,
    val plannedDurationMs: Long,
    val hand: SelectedHand
)

/**
 * One completed cycle: fully open (start) -> closing -> closed -> opening -> fully open (end).
 * Times are monotonic milliseconds; [amplitude] is the raw extension range inside the cycle (0..1, unitless).
 */
data class OpenCloseCycle(
    val index: Int,
    /** The last fully open moment before the closing: the cycle start. */
    val startMs: Long,
    /** First moment below the open exit level. */
    val closingStartMs: Long,
    /** Last entry into the closed level before reopening. */
    val closedMs: Long,
    /** Last departure from the closed level. */
    val openingStartMs: Long,
    /** Fully open again: the cycle is complete and counted once. */
    val endMs: Long,
    val amplitude: Double
) {
    val durationMs: Long get() = endMs - startMs
    val closingMs: Long get() = closedMs - closingStartMs
    val openingMs: Long get() = endMs - openingStartMs
}

/** Cycle detection output. Partial excursions and rejected candidates are counted, never counted as cycles. */
data class OpenCloseDetection(
    val cycles: List<OpenCloseCycle>,
    /** Opened only partway (never reached the open level) and returned to closed. */
    val partialOpenings: Int,
    /** Closed only partway (never reached the closed level) and returned to open. */
    val partialClosings: Int,
    /** A full cycle or one of its stages shorter than the minimum. */
    val rejectedTooFast: Int,
    /** A full cycle longer than the maximum: a pause, not a repeated movement. */
    val rejectedTooSlow: Int,
    /** A closed-to-open return that did not reach the full range (amplitude, full opening or full closing). */
    val rejectedNotFullRange: Int,
    /** Cycles in progress when tracking dropped out: never counted. */
    val discardedByDropout: Int,
    /** The recording ended in the middle of a cycle: that partial cycle is not counted. */
    val incompleteFinalCycle: Boolean
) {
    val rejectedCandidates: Int get() = rejectedTooFast + rejectedTooSlow + rejectedNotFullRange

    companion object {
        val NONE = OpenCloseDetection(emptyList(), 0, 0, 0, 0, 0, 0, false)
    }
}

/** Frame counts and timing from the frame timestamps (never from the UI timer). */
data class OpenCloseFrameStats(
    val totalFrames: Int,
    val validFrames: Int,
    val noHandFrames: Int,
    val multipleHandsFrames: Int,
    val outOfFrameFrames: Int,
    val errorFrames: Int,
    val sideMatchesFrames: Int,
    val sideUncertainFrames: Int,
    val sideMismatchFrames: Int,
    /** Results per second actually delivered by camera + MediaPipe. */
    val fps: Double,
    val medianFrameIntervalMs: Double?,
    /** Periods without valid landmarks longer than the dropout threshold (recording edges included). */
    val dropoutCount: Int,
    val dropoutTotalMs: Long,
    val longestDropoutMs: Long,
    /** Window minus dropout time: the time with usable landmarks. */
    val usableDurationMs: Long,
    /** First to last result (+ one frame interval): how long frames really arrived. */
    val observedDurationMs: Long,
    /** usable / window: the TIME share with valid landmarks. */
    val trackingRate: Double,
    val meanLuma: Float?,
    val cameraFramesSkipped: Int,
    val pipelineFramesDropped: Int
) {
    val validFramePercent: Double get() = if (totalFrames > 0) validFrames * 100.0 / totalFrames else 0.0
    fun fraction(count: Int): Double = if (totalFrames > 0) count.toDouble() / totalFrames else 0.0
    val sideMismatchShare: Double?
        get() {
            val confident = sideMatchesFrames + sideMismatchFrames
            return if (confident == 0) null else sideMismatchFrames.toDouble() / confident
        }
}

/**
 * Measured values only (no interpretation, no score). Amplitudes and openings are finger extension
 * (0..1, unitless, see OpenCloseFrameExtractor); durations in ms. Null = not measurable.
 */
data class OpenCloseMetrics(
    val plannedDurationMs: Long,
    /** Recording window on the monotonic clock. */
    val measurementDurationMs: Long,
    val usableDurationMs: Long,
    val completedCycles: Int,
    /** completed cycles / usable seconds. */
    val cycleRatePerSecond: Double?,
    val meanCycleDurationMs: Double?,
    val cycleDurationSdMs: Double?,
    val cycleDurationCvPercent: Double?,
    /** Time between consecutive cycle completions. */
    val meanIntervalMs: Double?,
    val intervalSdMs: Double?,
    val intervalCvPercent: Double?,
    val meanAmplitude: Double?,
    val amplitudeSd: Double?,
    val amplitudeCvPercent: Double?,
    val meanOpeningMs: Double?,
    val meanClosingMs: Double?,
    val partialOpenings: Int,
    val partialClosings: Int,
    val rejectedCandidates: Int,
    val incompleteFinalCycle: Boolean,
    /** Robust noise of the opening signal divided by the median amplitude. */
    val noiseToAmplitude: Double?,
    val recordingCompletenessPercent: Double,
    val frames: OpenCloseFrameStats
)

data class OpenCloseQualityReport(
    val status: QualityStatus,
    /** Most severe first. */
    val issues: List<OpenCloseQualityIssue>,
    /** 0..100 TECHNICAL measurement quality (data reliability, not the person). */
    val qualityScore: Int,
    val cameraQuality: CameraQuality,
    val handTrackingQuality: HandTrackingQuality,
    val trackingRate: Double
) {
    val primaryIssue: OpenCloseQualityIssue? get() = issues.firstOrNull()

    /** VALID and LOW_QUALITY recordings produce a result; the others must be repeated. */
    val isUsable: Boolean get() = status == QualityStatus.VALID || status == QualityStatus.LOW_QUALITY
}

/** Quality issues, grouped by layer. Severity: INVALID > INSUFFICIENT_DATA > LOW_QUALITY. */
enum class OpenCloseQualityIssue(val severity: QualityStatus) {
    // Recording / data layer
    NO_FRAMES(QualityStatus.INVALID),
    RECORDING_INCOMPLETE(QualityStatus.INVALID),
    INSUFFICIENT_FPS(QualityStatus.INVALID),

    // Hand detection layer
    WRONG_HAND(QualityStatus.INVALID),
    MULTIPLE_HANDS(QualityStatus.INVALID),
    NO_HAND_DETECTED(QualityStatus.INSUFFICIENT_DATA),

    // Landmark tracking layer
    INSUFFICIENT_TRACKING(QualityStatus.INSUFFICIENT_DATA),
    EXCESSIVE_DROPOUT(QualityStatus.INSUFFICIENT_DATA),

    // Cycle layer
    NO_CYCLES_DETECTED(QualityStatus.INSUFFICIENT_DATA),
    TOO_FEW_CYCLES(QualityStatus.INSUFFICIENT_DATA),

    LOW_FPS(QualityStatus.LOW_QUALITY),
    REDUCED_TRACKING(QualityStatus.LOW_QUALITY),
    DROPOUTS_PRESENT(QualityStatus.LOW_QUALITY),
    OUT_OF_FRAME_FREQUENT(QualityStatus.LOW_QUALITY),
    HAND_SIDE_UNCERTAIN(QualityStatus.LOW_QUALITY),
    /** Image too dark/bright AND tracking below GOOD: lighting is likely part of the cause. */
    LIGHTING_AFFECTED_TRACKING(QualityStatus.LOW_QUALITY),
    NOISY_SIGNAL(QualityStatus.LOW_QUALITY),
    PARTIAL_MOVEMENTS_FREQUENT(QualityStatus.LOW_QUALITY)
}

/** Everything the engine produced for one recording (or one live snapshot). */
data class OpenCloseAnalysis(
    val hand: SelectedHand,
    val startMs: Long,
    val endMs: Long,
    /** Deduplicated frames inside the window, in time order, with outliers already marked OUT_OF_FRAME. */
    val frames: List<OpenCloseFrame>,
    /** Smoothed opening of each VALID frame, parallel to the valid frames of [frames]. */
    val smoothedOpenings: List<Double>,
    val lowLevel: Double?,
    val highLevel: Double?,
    val detection: OpenCloseDetection,
    val metrics: OpenCloseMetrics,
    val quality: OpenCloseQualityReport
)

/**
 * OPEN/CLOSED CYCLE ENGINE for the hand-opening test. Pure and deterministic: the same frames always
 * give the same analysis. Time comes only from frame timestamps.
 *
 * Pipeline: window + duplicate/outlier handling -> time-aware smoothing (per segment) -> adaptive
 * levels from the recording's own range -> hysteresis state machine -> cycle metrics -> quality.
 */
class HandOpenCloseEngine(
    private val detection: OpenCloseDetectionConfig = OpenCloseDetectionConfig(),
    private val thresholds: OpenCloseQualityThresholds = OpenCloseQualityThresholds()
) {

    /** Live path: the same processing, cycles only, for the counter shown during recording. */
    fun countCycles(frames: List<OpenCloseFrame>, startMs: Long, endMs: Long): List<OpenCloseCycle> =
        process(frames, startMs, endMs).detection.cycles

    fun analyze(recording: OpenCloseRecording): OpenCloseAnalysis {
        val p = process(recording.frames, recording.startMs, recording.endMs)
        val metrics = metrics(p, recording.startMs, recording.endMs, recording.plannedDurationMs)
        val quality = assessQuality(metrics)
        return OpenCloseAnalysis(
            hand = recording.hand,
            startMs = recording.startMs,
            endMs = recording.endMs,
            frames = p.frames,
            smoothedOpenings = p.smoothed,
            lowLevel = p.levels?.low,
            highLevel = p.levels?.high,
            detection = p.detection,
            metrics = metrics,
            quality = quality
        )
    }

    // --- Signal processing ---------------------------------------------------------------------

    private class Processed(
        val frames: List<OpenCloseFrame>,
        val points: List<SignalPoint>,
        val smoothed: List<Double>,
        val levels: Levels?,
        val detection: OpenCloseDetection
    )

    private fun process(input: List<OpenCloseFrame>, startMs: Long, endMs: Long): Processed {
        val cfg = detection
        val sorted = input.filter { it.timestampMs in startMs..endMs }.sortedBy { it.timestampMs }

        // A frame delivered twice (same timestamp) is counted once: the first one wins.
        val unique = ArrayList<OpenCloseFrame>(sorted.size)
        for (f in sorted) {
            if (unique.isEmpty() || unique.last().timestampMs != f.timestampMs) unique += f
        }

        // Valid landmarks with an implausible opening are glitches: they are treated like tracking loss.
        val frames = unique.map { f ->
            val plausible = f.opening.isFinite() && f.opening > 0.0 && f.opening <= cfg.maxPlausibleOpening
            if (f.isValid && !plausible) f.copy(status = FrameStatus.OUT_OF_FRAME, opening = Double.NaN) else f
        }

        val valid = frames.filter { it.isValid }
        val times = LongArray(valid.size) { valid[it].timestampMs }
        val raw = DoubleArray(valid.size) { valid[it].opening }
        // Segments: a gap longer than the bridge limit splits the signal. Smoothing and cycles never cross it.
        val segmentStart = BooleanArray(valid.size) { it == 0 || times[it] - times[it - 1] > cfg.maxBridgeGapMs }
        val smoothed = smooth(times, raw, segmentStart, cfg.smoothingTauMs)
        val points = List(valid.size) { SignalPoint(times[it], raw[it], smoothed[it], segmentStart[it]) }

        val levels = if (points.size >= MIN_POINTS_FOR_LEVELS) {
            val sortedSmoothed = smoothed.sorted()
            val low = Stats.percentileOfSorted(sortedSmoothed, cfg.envelopeLowPercentile)
            val high = Stats.percentileOfSorted(sortedSmoothed, cfg.envelopeHighPercentile)
            if (high - low >= cfg.minRange) Levels(low, high, cfg) else null
        } else null

        val cycles = if (levels != null) {
            OpenCloseCycleDetector.detect(points, levels, cfg)
        } else OpenCloseDetection.NONE

        return Processed(frames, points, smoothed.toList(), levels, cycles)
    }

    /** Forward + backward exponential smoothing (zero phase lag), restarted at every segment. */
    private fun smooth(times: LongArray, values: DoubleArray, segmentStart: BooleanArray, tauMs: Double): DoubleArray {
        val n = values.size
        val forward = DoubleArray(n)
        for (i in 0 until n) {
            forward[i] = if (segmentStart[i]) values[i] else {
                val a = alpha(times[i] - times[i - 1], tauMs)
                a * values[i] + (1 - a) * forward[i - 1]
            }
        }
        val backward = DoubleArray(n)
        for (i in n - 1 downTo 0) {
            val segmentEnd = i == n - 1 || segmentStart[i + 1]
            backward[i] = if (segmentEnd) forward[i] else {
                val a = alpha(times[i + 1] - times[i], tauMs)
                a * forward[i] + (1 - a) * backward[i + 1]
            }
        }
        return backward
    }

    private fun alpha(dtMs: Long, tauMs: Double): Double =
        if (tauMs <= 0.0) 1.0 else 1.0 - exp(-max(dtMs, 1L) / tauMs)

    // --- Metrics -------------------------------------------------------------------------------

    private fun metrics(p: Processed, startMs: Long, endMs: Long, plannedMs: Long): OpenCloseMetrics {
        val frames = frameStats(p.frames, startMs, endMs)
        val cycles = p.detection.cycles
        val usableS = frames.usableDurationMs / 1000.0
        val durations = cycles.map { it.durationMs.toDouble() }
        val intervals = cycles.zipWithNext { a, b -> (b.endMs - a.endMs).toDouble() }
        val amplitudes = cycles.map { it.amplitude }
        val median = Stats.median(amplitudes)
        val noise = if (p.points.size >= MIN_POINTS_FOR_LEVELS) {
            Stats.robustSigma(p.points.map { it.raw - it.smoothed })
        } else null
        val meanInterval = Stats.mean(intervals)
        return OpenCloseMetrics(
            plannedDurationMs = plannedMs,
            measurementDurationMs = (endMs - startMs).coerceAtLeast(0L),
            usableDurationMs = frames.usableDurationMs,
            completedCycles = cycles.size,
            cycleRatePerSecond = if (usableS > 0) cycles.size / usableS else null,
            meanCycleDurationMs = Stats.mean(durations),
            cycleDurationSdMs = Stats.sampleSd(durations),
            cycleDurationCvPercent = Stats.cvPercent(durations),
            meanIntervalMs = meanInterval,
            intervalSdMs = Stats.sampleSd(intervals),
            intervalCvPercent = Stats.cvPercent(intervals),
            meanAmplitude = Stats.mean(amplitudes),
            amplitudeSd = Stats.sampleSd(amplitudes),
            amplitudeCvPercent = Stats.cvPercent(amplitudes),
            meanOpeningMs = Stats.mean(cycles.map { it.openingMs.toDouble() }),
            meanClosingMs = Stats.mean(cycles.map { it.closingMs.toDouble() }),
            partialOpenings = p.detection.partialOpenings,
            partialClosings = p.detection.partialClosings,
            rejectedCandidates = p.detection.rejectedCandidates,
            incompleteFinalCycle = p.detection.incompleteFinalCycle,
            noiseToAmplitude = if (noise != null && median != null && median > 0) noise / median else null,
            recordingCompletenessPercent = if (plannedMs > 0) {
                ((endMs - startMs).coerceAtLeast(0L) * 100.0 / plannedMs).coerceAtMost(100.0)
            } else 0.0,
            frames = frames
        )
    }

    private fun frameStats(frames: List<OpenCloseFrame>, startMs: Long, endMs: Long): OpenCloseFrameStats {
        val intervals = frames.zipWithNext { a, b -> (b.timestampMs - a.timestampMs).toDouble() }
        val medianInterval = Stats.median(intervals)
        val fps = if (frames.size >= 2) {
            val span = frames.last().timestampMs - frames.first().timestampMs
            if (span > 0) (frames.size - 1) * 1000.0 / span else 0.0
        } else 0.0

        val threshold = max(MIN_DROPOUT_MS, 3 * (medianInterval ?: 0.0))
        val validTimes = frames.filter { it.isValid }.map { it.timestampMs }
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
        val observed = if (frames.size >= 2) {
            frames.last().timestampMs - frames.first().timestampMs + Math.round(medianInterval ?: 0.0)
        } else 0L

        val sequences = frames.mapNotNull { it.sequence }
        val pipelineDropped = if (sequences.size >= 2) {
            ((sequences.max() - sequences.min() + 1) - sequences.size).toInt().coerceAtLeast(0)
        } else 0
        val lumas = frames.mapNotNull { it.meanLuma }
        val withHand = frames.filter { it.isValid }
        fun count(status: FrameStatus) = frames.count { it.status == status }
        fun side(status: HandSideStatus) = withHand.count { it.sideStatus == status }
        return OpenCloseFrameStats(
            totalFrames = frames.size,
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
            cameraFramesSkipped = frames.sumOf { it.cameraFramesSkipped },
            pipelineFramesDropped = pipelineDropped
        )
    }

    // --- Quality -------------------------------------------------------------------------------

    private fun assessQuality(m: OpenCloseMetrics): OpenCloseQualityReport {
        val t = thresholds
        val f = m.frames
        val issues = mutableListOf<OpenCloseQualityIssue>()
        val durationMs = m.measurementDurationMs
        val dropoutShare = if (durationMs > 0) f.dropoutTotalMs.toDouble() / durationMs else 1.0
        val cameraQuality = t.vision.cameraQuality(f.meanLuma)
        val trackingQuality = t.vision.trackingQuality(f.trackingRate)

        // Recording / data layer (frame timestamps).
        if (f.totalFrames < t.minFrames) issues += OpenCloseQualityIssue.NO_FRAMES
        val observedCompleteness = if (m.plannedDurationMs > 0) f.observedDurationMs * 100.0 / m.plannedDurationMs else 0.0
        if (f.totalFrames >= t.minFrames && observedCompleteness < t.minObservedCompletenessPercent) {
            issues += OpenCloseQualityIssue.RECORDING_INCOMPLETE
        }
        if (f.totalFrames >= 2) {
            if (f.fps < t.absoluteMinFps) issues += OpenCloseQualityIssue.INSUFFICIENT_FPS
            else if (f.fps < t.goodFps) issues += OpenCloseQualityIssue.LOW_FPS
        }

        // Hand detection layer.
        if (f.validFrames == 0) issues += OpenCloseQualityIssue.NO_HAND_DETECTED
        if (f.fraction(f.multipleHandsFrames) >= t.multipleHandsMaxFraction) issues += OpenCloseQualityIssue.MULTIPLE_HANDS
        val confidentSide = f.sideMatchesFrames + f.sideMismatchFrames
        val mismatch = f.sideMismatchShare
        if (f.validFrames > 0) {
            when {
                confidentSide >= t.minSideConfidentFrames && mismatch != null && mismatch >= t.wrongHandMinShare ->
                    issues += OpenCloseQualityIssue.WRONG_HAND
                confidentSide < t.minSideConfidentFrames || (mismatch ?: 0.0) >= t.sideUncertainShare ->
                    issues += OpenCloseQualityIssue.HAND_SIDE_UNCERTAIN
            }
        }

        // Landmark tracking layer (time-based). The hand leaving the image counts here too.
        if (f.validFrames > 0) {
            when (trackingQuality) {
                HandTrackingQuality.POOR -> issues += OpenCloseQualityIssue.INSUFFICIENT_TRACKING
                HandTrackingQuality.WARNING -> issues += OpenCloseQualityIssue.REDUCED_TRACKING
                HandTrackingQuality.GOOD -> Unit
            }
            if (f.longestDropoutMs > t.maxLongestDropoutMs) {
                issues += OpenCloseQualityIssue.EXCESSIVE_DROPOUT
            } else if (dropoutShare > t.goodDropoutShare || f.longestDropoutMs > t.goodLongestDropoutMs) {
                issues += OpenCloseQualityIssue.DROPOUTS_PRESENT
            }
            if (f.fraction(f.outOfFrameFrames) > t.maxOutOfFrameShare) issues += OpenCloseQualityIssue.OUT_OF_FRAME_FREQUENT
        }
        if (cameraQuality != CameraQuality.GOOD && trackingQuality != HandTrackingQuality.GOOD) {
            issues += OpenCloseQualityIssue.LIGHTING_AFFECTED_TRACKING
        }

        // Cycle layer.
        if (f.validFrames > 0) {
            if (m.completedCycles == 0) {
                issues += OpenCloseQualityIssue.NO_CYCLES_DETECTED
            } else if (m.completedCycles < t.minCycles) {
                issues += OpenCloseQualityIssue.TOO_FEW_CYCLES
            } else {
                val excursions = m.completedCycles + m.partialOpenings + m.partialClosings
                if (m.partialOpenings + m.partialClosings >= 2 &&
                    (m.partialOpenings + m.partialClosings) * 1.0 / excursions > t.maxPartialShare
                ) {
                    issues += OpenCloseQualityIssue.PARTIAL_MOVEMENTS_FREQUENT
                }
                if (m.noiseToAmplitude != null && m.noiseToAmplitude > t.maxNoiseToAmplitude) {
                    issues += OpenCloseQualityIssue.NOISY_SIGNAL
                }
            }
        }

        val sorted = issues.distinct().sortedWith(
            compareByDescending<OpenCloseQualityIssue> { it.severity.ordinal }.thenBy { it.ordinal }
        )
        val status = sorted.maxOfOrNull { it.severity } ?: QualityStatus.VALID
        return OpenCloseQualityReport(
            status = status,
            issues = sorted,
            qualityScore = qualityScore(f.fps, f.trackingRate, dropoutShare, m),
            cameraQuality = cameraQuality,
            handTrackingQuality = trackingQuality,
            trackingRate = f.trackingRate
        )
    }

    /** Mean of four 0..1 technical factors: frame rate, tracking share, dropouts, and completed share of excursions. */
    private fun qualityScore(fps: Double, trackingRate: Double, dropoutShare: Double, m: OpenCloseMetrics): Int {
        val t = thresholds
        fun ramp(value: Double, bad: Double, good: Double) =
            if (good <= bad) (if (value >= good) 1.0 else 0.0) else ((value - bad) / (good - bad)).coerceIn(0.0, 1.0)
        val excursions = m.completedCycles + m.partialOpenings + m.partialClosings
        val completedShare = if (excursions > 0) m.completedCycles.toDouble() / excursions else 0.0
        val factors = listOf(
            ramp(fps, t.absoluteMinFps, max(t.goodFps, t.absoluteMinFps + 1)),
            ramp(trackingRate, t.vision.minTrackingRate, t.vision.goodTrackingRate),
            1.0 - ramp(dropoutShare, t.goodDropoutShare, 1.0 - t.vision.minTrackingRate),
            completedShare
        )
        return Math.round(factors.average() * 100.0).toInt()
    }

    companion object {
        private const val MIN_POINTS_FOR_LEVELS = 3
        private const val MIN_DROPOUT_MS = 150.0
    }
}

/** One valid frame on the processed signal. [raw] and [smoothed] are finger extension values. */
internal class SignalPoint(
    val timestampMs: Long,
    val raw: Double,
    val smoothed: Double,
    /** First valid frame after a gap that is too long to bridge: the cycle in progress is discarded. */
    val segmentStart: Boolean
)

/** Levels of the hysteresis state machine (extension units), from the recording's own range. */
internal class Levels(low: Double, high: Double, cfg: OpenCloseDetectionConfig) {
    private val range = high - low
    val low: Double = low
    val high: Double = high
    val closeEnter = low + cfg.closeFraction * range
    val closeExit = low + cfg.closeExitFraction * range
    val openExit = low + cfg.openExitFraction * range
    val openEnter = low + cfg.openFraction * range
}

/**
 * CYCLE STATE MACHINE. States and the only counted sequence:
 *
 *   OPEN --(below openExit)--> CLOSING --(at or below closeEnter)--> CLOSED
 *   CLOSED --(above closeExit)--> OPENING --(at or above openEnter)--> OPEN   => one cycle
 *
 * Returning from CLOSING to OPEN, or from OPENING to CLOSED, is a partial movement and never counts.
 * A cycle is counted once, when OPEN is re-entered from OPENING; that OPEN starts the next cycle, so
 * the same movement is never counted twice. A segment split (tracking loss) resets the machine and
 * discards the cycle in progress. Only a crossing observed inside the recording starts a cycle: an OPEN
 * found at the start of the recording (or after a gap) has an unobserved start, so the first complete
 * cycle begins at the first opening crossing and is counted when the next opening crossing arrives.
 */
internal object OpenCloseCycleDetector {

    private enum class Phase { UNKNOWN, OPEN, CLOSING, CLOSED, OPENING }

    fun detect(points: List<SignalPoint>, levels: Levels, cfg: OpenCloseDetectionConfig): OpenCloseDetection {
        var phase = Phase.UNKNOWN
        var openIdx = -1
        var closingStartMs = 0L
        var closedMs = 0L
        var openingStartMs = 0L
        val cycles = ArrayList<OpenCloseCycle>()
        var partialOpenings = 0
        var partialClosings = 0
        var tooFast = 0
        var notFullRange = 0
        var tooSlow = 0
        var discarded = 0

        for (i in points.indices) {
            val p = points[i]
            val t = p.timestampMs
            val v = p.smoothed
            if (p.segmentStart) {
                if (phase != Phase.UNKNOWN && phase != Phase.CLOSED) discarded++
                phase = Phase.UNKNOWN
                openIdx = -1
            }
            when (phase) {
                Phase.UNKNOWN -> when {
                    v >= levels.openEnter -> {
                        // Entered from an unknown state (start of the recording or after a gap): the
                        // moment the opening began is not observed, so it cannot start a cycle.
                        phase = Phase.OPEN
                        openIdx = -1
                    }
                    v <= levels.closeEnter -> {
                        phase = Phase.CLOSED
                        closedMs = t
                    }
                }

                Phase.OPEN -> if (v < levels.openExit) {
                    phase = Phase.CLOSING
                    closingStartMs = t
                }

                Phase.CLOSING -> when {
                    v >= levels.openEnter -> {
                        // Went back up before reaching closed: a partial closing. The cycle start stays.
                        phase = Phase.OPEN
                        partialClosings++
                    }
                    v <= levels.closeEnter -> {
                        phase = Phase.CLOSED
                        closedMs = t
                    }
                }

                Phase.CLOSED -> if (v > levels.closeExit) {
                    phase = Phase.OPENING
                    openingStartMs = t
                }

                Phase.OPENING -> when {
                    v <= levels.closeEnter -> {
                        // Went back down before reaching open: a partial opening.
                        phase = Phase.CLOSED
                        closedMs = t
                        partialOpenings++
                    }
                    v >= levels.openEnter -> {
                        phase = Phase.OPEN
                        if (openIdx >= 0) {
                            val cycleStart = points[openIdx].timestampMs
                            val duration = t - cycleStart
                            var lo = Double.MAX_VALUE
                            var hi = -Double.MAX_VALUE
                            for (k in openIdx..i) {
                                lo = min(lo, points[k].raw)
                                hi = max(hi, points[k].raw)
                            }
                            val amplitude = hi - lo
                            val fullRange = amplitude >= cfg.minRange &&
                                hi >= cfg.minFullOpening && lo <= cfg.maxFullClosing
                            when {
                                duration < cfg.minCycleMs -> tooFast++
                                duration > cfg.maxCycleMs -> tooSlow++
                                !fullRange -> notFullRange++
                                else -> cycles += OpenCloseCycle(
                                    index = cycles.size,
                                    startMs = cycleStart,
                                    closingStartMs = closingStartMs,
                                    closedMs = closedMs,
                                    openingStartMs = openingStartMs,
                                    endMs = t,
                                    amplitude = amplitude
                                )
                            }
                        }
                        // This OPEN is the start of the next cycle (never counted twice).
                        openIdx = i
                    }
                }
            }
        }
        return OpenCloseDetection(
            cycles = cycles,
            partialOpenings = partialOpenings,
            partialClosings = partialClosings,
            rejectedTooFast = tooFast,
            rejectedNotFullRange = notFullRange,
            rejectedTooSlow = tooSlow,
            discardedByDropout = discarded,
            incompleteFinalCycle = phase == Phase.CLOSING || phase == Phase.CLOSED || phase == Phase.OPENING
        )
    }
}
