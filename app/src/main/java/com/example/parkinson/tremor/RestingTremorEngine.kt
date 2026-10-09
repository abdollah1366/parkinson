package com.example.parkinson.tremor

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.tapping.raw.FrameStatus
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** The raw frames of one recording, in arrival order, plus the planned length. */
data class RestingTremorRecording(
    val frames: List<RestingTremorFrame>,
    val plannedDurationMs: Long,
)

/**
 * Data-quality findings, ordered from most to least severe. The status follows from the findings: any
 * INVALID finding makes the recording INVALID, otherwise any INSUFFICIENT_DATA finding, otherwise any
 * LOW_QUALITY finding. None of them describes the person's tremor.
 */
enum class RestingTremorQualityIssue(val status: QualityStatus) {
    /** The hand or the camera moved as a whole: not a resting-state recording. */
    GROSS_MOVEMENT(QualityStatus.INVALID),
    MULTIPLE_HANDS(QualityStatus.INVALID),
    WRONG_HAND(QualityStatus.INVALID),
    /** A frame arrived with an earlier timestamp than its predecessor: the clock data cannot be trusted. */
    NON_MONOTONIC_TIMESTAMPS(QualityStatus.INVALID),

    TOO_FEW_FRAMES(QualityStatus.INSUFFICIENT_DATA),
    RECORDING_TOO_SHORT(QualityStatus.INSUFFICIENT_DATA),
    LOW_VALID_FRAMES(QualityStatus.INSUFFICIENT_DATA),
    LONG_INTERRUPTION(QualityStatus.INSUFFICIENT_DATA),
    LOW_FRAME_RATE(QualityStatus.INSUFFICIENT_DATA),
    IRREGULAR_SAMPLING(QualityStatus.INSUFFICIENT_DATA),

    REDUCED_VALID_FRAMES(QualityStatus.LOW_QUALITY),
    FREQUENT_INTERRUPTIONS(QualityStatus.LOW_QUALITY),
}

/** Quality status and its findings. [isUsable] is true only when the measurements may be shown and stored. */
data class RestingTremorQualityReport(
    val status: QualityStatus,
    val issues: List<RestingTremorQualityIssue>,
) {
    val primaryIssue: RestingTremorQualityIssue? get() = issues.firstOrNull()
    val isUsable: Boolean get() = status == QualityStatus.VALID || status == QualityStatus.LOW_QUALITY

    companion object {
        fun of(issues: Collection<RestingTremorQualityIssue>): RestingTremorQualityReport {
            val sorted = issues.distinct().sortedBy { it.ordinal }
            val status = when {
                sorted.any { it.status == QualityStatus.INVALID } -> QualityStatus.INVALID
                sorted.any { it.status == QualityStatus.INSUFFICIENT_DATA } -> QualityStatus.INSUFFICIENT_DATA
                sorted.any { it.status == QualityStatus.LOW_QUALITY } -> QualityStatus.LOW_QUALITY
                else -> QualityStatus.VALID
            }
            return RestingTremorQualityReport(status, sorted)
        }
    }
}

/**
 * Measurements of one recording. Every value is computed from the recorded frames only. Units are
 * stated on each field. Hand lengths are the recording's median wrist-to-knuckle distance (in pixels),
 * so displacement is a fraction of the hand's own size in the image plane, not millimetres.
 */
data class RestingTremorMetrics(
    val plannedDurationMs: Long,
    /** Last minus first frame timestamp (ms). */
    val actualDurationMs: Long,
    val totalFrames: Int,
    val validFrames: Int,
    val noHandFrames: Int,
    val outOfFrameFrames: Int,
    val multipleHandsFrames: Int,
    val wrongHandFrames: Int,
    val errorFrames: Int,
    /** Valid frames / all frames, in percent. */
    val validFramePercent: Double,
    /** Gaps between consecutive valid frames (including the head and tail of the recording) longer than the interruption limit. */
    val interruptionCount: Int,
    /** Sum of those gaps (ms). */
    val interruptionTotalMs: Long,
    /** Longest such gap (ms); 0 when there was none. */
    val longestInterruptionMs: Long,
    /** Median time between consecutive frames, all statuses (ms). */
    val medianFrameIntervalMs: Double,
    /** 1000 / [medianFrameIntervalMs] (Hz). */
    val frameRateHz: Double,
    /** Frame intervals outside [0.5, 2] x median, in percent of all intervals. */
    val irregularIntervalPercent: Double,
    /** Median hand size reference (pixels). */
    val medianHandScalePx: Double,
    /** Share of the analysis grid covered by valid (or bridged) samples, in percent. */
    val signalCoveragePercent: Double,
    /**
     * Largest range (max - min) of the 1 s local mean of the palm position, over x and y, in percent of a
     * hand length. The slow component of the movement (hand or camera drift).
     */
    val slowDriftPercent: Double,
    /** RMS of the radial residual displacement after detrending, in percent of a hand length (image plane). Null when not computable. */
    val amplitudeRmsPercent: Double?,
    /** Frequency of the spectral peak, in Hz. Null when the spectrum has no reliable peak. */
    val dominantFrequencyHz: Double?,
    /** Peak power divided by the mean power of the analysed band (unitless). Null when no frequency. */
    val spectralProminence: Double?,
    /** Share of the band power within [peakHalfWidthHz] of the peak, in percent. Null when no frequency. */
    val periodicityPercent: Double?,
)

/** Output of [RestingTremorEngine.analyze]. [metrics] is null when the recording was too broken to measure. */
data class RestingTremorAnalysis(
    val metrics: RestingTremorMetrics?,
    val quality: RestingTremorQualityReport,
)

/**
 * Signal processing of the resting-hand recording. Steps:
 *  1. Quality checks on timestamps, frame counts, valid share, interruptions, sampling, hand identity.
 *  2. Slow drift: range of the 1 s local mean of the palm position (gross movement check).
 *  3. Linear resampling of the valid palm positions onto a uniform [RestingTremorSignalConfig.gridHz] grid.
 *     Gaps longer than the interruption limit are NOT bridged (those grid samples stay empty).
 *  4. Detrending: subtract the centred 1 s moving average (removes the slow component).
 *  5. Amplitude: RMS of the radial residual displacement, in hand lengths.
 *  6. Periodogram (Hann window, direct DFT, x and y power summed) over the analysis band. The dominant
 *     frequency is reported only with enough coverage, enough cycles and a clear peak.
 *
 * Nothing here is a clinical tremor measure. Image-plane pixels are not calibrated physical distance.
 */
class RestingTremorEngine(
    val signal: RestingTremorSignalConfig = RestingTremorSignalConfig(),
    private val thresholds: RestingTremorQualityThresholds = RestingTremorQualityThresholds(),
) {

    fun analyze(recording: RestingTremorRecording): RestingTremorAnalysis {
        val frames = recording.frames
        val t = thresholds

        for (i in 1 until frames.size) {
            if (frames[i].timestampMs < frames[i - 1].timestampMs) {
                return RestingTremorAnalysis(null, RestingTremorQualityReport.of(listOf(RestingTremorQualityIssue.NON_MONOTONIC_TIMESTAMPS)))
            }
        }
        if (frames.size < t.minFrames) {
            return RestingTremorAnalysis(null, RestingTremorQualityReport.of(listOf(RestingTremorQualityIssue.TOO_FEW_FRAMES)))
        }
        val valid = frames.filter { it.isValid }
        if (valid.size < 2) {
            return RestingTremorAnalysis(null, RestingTremorQualityReport.of(listOf(RestingTremorQualityIssue.LOW_VALID_FRAMES)))
        }

        val intervals = DoubleArray(frames.size - 1) { (frames[it + 1].timestampMs - frames[it].timestampMs).toDouble() }
        val medianDt = median(intervals)
        if (medianDt <= 0.0) {
            return RestingTremorAnalysis(null, RestingTremorQualityReport.of(listOf(RestingTremorQualityIssue.LOW_FRAME_RATE)))
        }
        val frameRate = 1000.0 / medianDt
        val irregularShare = 100.0 * intervals.count { it < 0.5 * medianDt || it > 2.0 * medianDt } / intervals.size
        val actualDurationMs = frames.last().timestampMs - frames.first().timestampMs
        val validPercent = 100.0 * valid.size / frames.size

        // Interruptions: head and tail count too, so a late start or early end is visible.
        val gaps = ArrayList<Long>(valid.size + 1)
        gaps += valid.first().timestampMs - frames.first().timestampMs
        for (i in 1 until valid.size) gaps += valid[i].timestampMs - valid[i - 1].timestampMs
        gaps += frames.last().timestampMs - valid.last().timestampMs
        val interruptions = gaps.filter { it > signal.interruptionGapMs }
        val longestInterruption = interruptions.maxOrNull() ?: 0L

        val wrongShare = 100.0 * valid.count { it.wrongHand } / valid.size
        val multipleShare = 100.0 * frames.count { it.status == FrameStatus.MULTIPLE_HANDS } / frames.size

        // Hand-length units: pixels divided by the recording's median hand size.
        val medianScale = median(valid.map { it.handScalePx }.toDoubleArray())
        val validTimes = valid.map { it.timestampMs.toDouble() }.toDoubleArray()
        val px = valid.map { it.palmXPx / medianScale }.toDoubleArray()
        val py = valid.map { it.palmYPx / medianScale }.toDoubleArray()

        val halfWindowMs = signal.detrendWindowMs / 2.0
        val driftX = localMean(validTimes, px, halfWindowMs)
        val driftY = localMean(validTimes, py, halfWindowMs)
        val slowDriftPercent = 100.0 * maxOf(range(driftX), range(driftY))

        val grid = resample(valid, frames.first().timestampMs, actualDurationMs, medianScale)
        val coveragePercent = 100.0 * grid.count { it != null } / grid.size
        val gridX = DoubleArray(grid.size) { grid[it]?.first ?: Double.NaN }
        val gridY = DoubleArray(grid.size) { grid[it]?.second ?: Double.NaN }
        val residualX = detrend(gridX)
        val residualY = detrend(gridY)

        var sumSq = 0.0
        var count = 0
        for (i in residualX.indices) {
            if (residualX[i].isNaN()) continue
            sumSq += residualX[i] * residualX[i] + residualY[i] * residualY[i]
            count++
        }
        val amplitudeRms = if (count > 0) 100.0 * sqrt(sumSq / count) else null

        val spectrum = dominantFrequency(residualX, residualY, actualDurationMs, coveragePercent)

        val metrics = RestingTremorMetrics(
            plannedDurationMs = recording.plannedDurationMs,
            actualDurationMs = actualDurationMs,
            totalFrames = frames.size,
            validFrames = valid.size,
            noHandFrames = frames.count { it.status == FrameStatus.NO_HAND },
            outOfFrameFrames = frames.count { it.status == FrameStatus.OUT_OF_FRAME },
            multipleHandsFrames = frames.count { it.status == FrameStatus.MULTIPLE_HANDS },
            wrongHandFrames = valid.count { it.wrongHand },
            errorFrames = frames.count { it.status == FrameStatus.ERROR },
            validFramePercent = validPercent,
            interruptionCount = interruptions.size,
            interruptionTotalMs = interruptions.sum(),
            longestInterruptionMs = longestInterruption,
            medianFrameIntervalMs = medianDt,
            frameRateHz = frameRate,
            irregularIntervalPercent = irregularShare,
            medianHandScalePx = medianScale,
            signalCoveragePercent = coveragePercent,
            slowDriftPercent = slowDriftPercent,
            amplitudeRmsPercent = amplitudeRms,
            dominantFrequencyHz = spectrum?.frequencyHz,
            spectralProminence = spectrum?.prominence,
            periodicityPercent = spectrum?.periodicityPercent,
        )

        val issues = ArrayList<RestingTremorQualityIssue>()
        if (slowDriftPercent / 100.0 > t.maxSlowDriftHandLengths) issues += RestingTremorQualityIssue.GROSS_MOVEMENT
        if (multipleShare > t.maxMultipleHandsPercent) issues += RestingTremorQualityIssue.MULTIPLE_HANDS
        if (wrongShare > t.maxWrongHandPercent) issues += RestingTremorQualityIssue.WRONG_HAND
        if (actualDurationMs < t.minRecordingFraction * recording.plannedDurationMs) {
            issues += RestingTremorQualityIssue.RECORDING_TOO_SHORT
        }
        if (validPercent < t.minValidFramePercent) {
            issues += RestingTremorQualityIssue.LOW_VALID_FRAMES
        } else if (validPercent < t.goodValidFramePercent) {
            issues += RestingTremorQualityIssue.REDUCED_VALID_FRAMES
        }
        if (longestInterruption > t.maxInterruptionMs) issues += RestingTremorQualityIssue.LONG_INTERRUPTION
        if (interruptions.size > t.frequentInterruptions) issues += RestingTremorQualityIssue.FREQUENT_INTERRUPTIONS
        if (frameRate < t.minFrameRateHz) issues += RestingTremorQualityIssue.LOW_FRAME_RATE
        if (irregularShare > t.maxIrregularIntervalPercent) issues += RestingTremorQualityIssue.IRREGULAR_SAMPLING

        return RestingTremorAnalysis(metrics, RestingTremorQualityReport.of(issues))
    }

    /**
     * Linear resampling onto the grid, in hand lengths ([handScalePx] is the recording's median hand size).
     * Returns null for a grid point that is outside the valid frames or inside an interruption longer than
     * [RestingTremorSignalConfig.interruptionGapMs].
     */
    private fun resample(
        valid: List<RestingTremorFrame>,
        startMs: Long,
        durationMs: Long,
        handScalePx: Double,
    ): List<Pair<Double, Double>?> {
        val n = floor(durationMs * signal.gridHz / 1000.0).toInt() + 1
        val out = ArrayList<Pair<Double, Double>?>(n)
        var k = 0
        for (g in 0 until n) {
            val tg = startMs + g * 1000.0 / signal.gridHz
            while (k + 1 < valid.size && valid[k + 1].timestampMs <= tg) k++
            val a = valid[k]
            val hasNext = k + 1 < valid.size
            if (a.timestampMs > tg || !hasNext) {
                out += null
                continue
            }
            val b = valid[k + 1]
            val span = (b.timestampMs - a.timestampMs).toDouble()
            if (span > signal.interruptionGapMs) {
                out += null
                continue
            }
            val f = if (span > 0.0) (tg - a.timestampMs) / span else 0.0
            out += Pair(
                lerp(a.palmXPx, b.palmXPx, f) / handScalePx,
                lerp(a.palmYPx, b.palmYPx, f) / handScalePx
            )
        }
        return out
    }

    private fun lerp(a: Double, b: Double, f: Double) = a + (b - a) * f

    /**
     * Residual after removing the centred moving average (window [RestingTremorSignalConfig.detrendWindowMs]),
     * computed over the non-empty samples only. Empty samples stay NaN.
     */
    private fun detrend(values: DoubleArray): DoubleArray {
        val half = (signal.detrendWindowMs * signal.gridHz / 2000.0).roundToInt().coerceAtLeast(1)
        val out = DoubleArray(values.size) { Double.NaN }
        for (i in values.indices) {
            if (values[i].isNaN()) continue
            var sum = 0.0
            var n = 0
            for (j in maxOf(0, i - half)..minOf(values.size - 1, i + half)) {
                if (!values[j].isNaN()) {
                    sum += values[j]
                    n++
                }
            }
            out[i] = values[i] - sum / n
        }
        return out
    }

    private data class Spectrum(val frequencyHz: Double?, val prominence: Double?, val periodicityPercent: Double?)

    /**
     * Periodogram of the residual (both axes summed). Returns null when the coverage is below 90 %, the
     * peak has fewer than [RestingTremorSignalConfig.minCyclesInRecording] cycles, or its prominence is
     * below [RestingTremorSignalConfig.minPeakProminence]. A null result means "frequency unavailable".
     */
    private fun dominantFrequency(rx: DoubleArray, ry: DoubleArray, durationMs: Long, coveragePercent: Double): Spectrum? {
        if (coveragePercent < 90.0) return null
        val n = rx.size
        if (n < 4) return null
        val x = DoubleArray(n) { if (rx[it].isNaN()) 0.0 else rx[it] }
        val y = DoubleArray(n) { if (ry[it].isNaN()) 0.0 else ry[it] }
        val window = DoubleArray(n) { 0.5 - 0.5 * cos(2 * PI * it / (n - 1)) }

        val freqCount = ((signal.analysisMaxHz - signal.analysisMinHz) / signal.frequencyStepHz).toInt() + 1
        val freqs = DoubleArray(freqCount) { signal.analysisMinHz + it * signal.frequencyStepHz }
        val power = DoubleArray(freqCount)
        for (k in freqs.indices) {
            var xc = 0.0
            var xs = 0.0
            var yc = 0.0
            var ys = 0.0
            for (i in 0 until n) {
                val phase = 2 * PI * freqs[k] * i / signal.gridHz
                val c = cos(phase) * window[i]
                val s = sin(phase) * window[i]
                xc += x[i] * c
                xs += x[i] * s
                yc += y[i] * c
                ys += y[i] * s
            }
            power[k] = xc * xc + xs * xs + yc * yc + ys * ys
        }

        val meanPower = power.average()
        if (!meanPower.isFinite() || meanPower <= 0.0) return null
        var peak = 0
        for (k in power.indices) if (power[k] > power[peak]) peak = k
        val prominence = power[peak] / meanPower
        val peakHz = freqs[peak]
        val cycles = peakHz * durationMs / 1000.0
        if (prominence < signal.minPeakProminence || cycles < signal.minCyclesInRecording) {
            return null
        }
        val total = power.sum()
        val near = freqs.indices.filter { abs(freqs[it] - peakHz) <= signal.peakHalfWidthHz }.sumOf { power[it] }
        return Spectrum(peakHz, prominence, 100.0 * near / total)
    }

    private fun median(values: DoubleArray): Double {
        if (values.isEmpty()) return Double.NaN
        val sorted = values.sortedArray()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }

    /** Mean of the values within +-[halfWindowMs] of each time (times must be ascending). */
    private fun localMean(times: DoubleArray, values: DoubleArray, halfWindowMs: Double): DoubleArray {
        val out = DoubleArray(values.size)
        var lo = 0
        var hi = 0
        var sum = 0.0
        for (i in values.indices) {
            while (times[i] - times[lo] > halfWindowMs) {
                sum -= values[lo]
                lo++
            }
            while (hi < values.size && times[hi] - times[i] <= halfWindowMs) {
                sum += values[hi]
                hi++
            }
            out[i] = sum / (hi - lo)
        }
        return out
    }

    private fun range(values: DoubleArray): Double = if (values.isEmpty()) 0.0 else values.max() - values.min()
}
