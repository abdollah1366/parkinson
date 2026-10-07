package com.example.parkinson.tapping.detection

import com.example.parkinson.tapping.signal.ProcessedSignal
import com.example.parkinson.tapping.signal.SignalSample
import kotlin.math.max
import kotlin.math.sqrt

data class DetectionConfig(
    /** Debounce: a closing sooner than this after the previous tap is a bounce, not a tap (max ~10 Hz). */
    val minInterTapIntervalMs: Long = 100L,
    /** Minimum time from closed fingers to the closing crossing; shorter cycles are noise spikes. */
    val minEventDurationMs: Long = 60L,
    /** A tap must open at least this fraction of the local envelope range... */
    val minAmplitudeFractionOfRange: Double = 0.4,
    /** ...and at least this fraction of the movement floor. */
    val minAmplitudeFractionOfFloor: Double = 0.5,
    /** A frame gap inside a tap cycle longer than this lowers the tap confidence. */
    val maxCycleGapMs: Long = 150L
)

/**
 * One detected tap: fingers open from a closed position and close again.
 * Amplitudes are in palm sizes (thumb-index distance / palm size).
 */
data class TapEvent(
    val index: Int,
    /** Closing moment: the signal fell below the close threshold. Used for intervals. */
    val timestampMs: Long,
    /** Fingers closed (lowest opening) before this tap. */
    val startMs: Long,
    /** Maximum opening. */
    val peakMs: Long,
    val amplitude: Double,
    val peakOpening: Double,
    val troughOpening: Double,
    /** Opening phase: start -> peak. */
    val openingDurationMs: Long,
    /** Closing phase: peak -> closing moment. */
    val closingDurationMs: Long,
    /** 0..1, from amplitude margin, frame continuity and hand-tracking confidence. */
    val confidence: Double,
    /** The recording began with the fingers open, so the start level is taken from the envelope. */
    val startEstimated: Boolean
) {
    val durationMs: Long get() = timestampMs - startMs
}

data class DetectionResult(
    val events: List<TapEvent>,
    val rejectedDebounce: Int,
    val rejectedTooShort: Int,
    val rejectedTooSmall: Int,
    /** Cycles in progress when tracking dropped out; never counted. */
    val discardedByDropout: Int,
    /** Cycles in progress when movement fell below the noise floor; never counted. */
    val discardedInactive: Int,
    /** The recording ended with the fingers open; that last cycle is not counted. */
    val incompleteFinalCycle: Boolean
)

/**
 * EVENT DETECTION: hysteresis state machine over the smoothed signal.
 *
 *   UNKNOWN --(<= close)--> CLOSED --(>= open)--> OPEN --(<= close)--> tap, CLOSED
 *
 * A tap is emitted only on the OPEN -> CLOSED transition and only if it passes the debounce,
 * minimum-duration and minimum-amplitude checks. A dropout (segment start) or loss of
 * movement resets to UNKNOWN and discards the cycle in progress, so a dropout is never a tap.
 * Not thread-safe; callers synchronize.
 */
class TapStateMachine(private val config: DetectionConfig = DetectionConfig()) {

    private enum class Phase { UNKNOWN, CLOSED, OPEN }

    private var phase = Phase.UNKNOWN
    private var troughValue = 0.0
    private var troughMs = 0L
    private var troughEstimated = false
    private var peakValue = 0.0
    private var peakMs = 0L

    // Amplitude is measured on the raw (unsmoothed) signal: smoothing attenuates peaks more
    // at higher tap rates, which would make the amplitude depend on the speed.
    private var rawTrough = 0.0
    private var rawPeak = 0.0
    private var cycleConfidenceSum = 0.0
    private var cycleSamples = 0
    private var cycleMaxGapMs = 0L
    private var lastSampleMs = -1L
    private var lastTapMs = -1L

    private val events = ArrayList<TapEvent>()
    private var rejectedDebounce = 0
    private var rejectedTooShort = 0
    private var rejectedTooSmall = 0
    private var discardedByDropout = 0
    private var discardedInactive = 0

    val tapCount: Int get() = events.size

    fun reset() {
        phase = Phase.UNKNOWN
        lastSampleMs = -1L
        lastTapMs = -1L
        events.clear()
        rejectedDebounce = 0
        rejectedTooShort = 0
        rejectedTooSmall = 0
        discardedByDropout = 0
        discardedInactive = 0
    }

    /** Returns the tap completed by this sample, if any. */
    fun onSample(s: SignalSample, close: Double, open: Double, movementFloor: Double): TapEvent? {
        if (s.segmentStart) {
            if (phase == Phase.OPEN) discardedByDropout++
            phase = Phase.UNKNOWN
        }
        val gap = if (lastSampleMs >= 0 && !s.segmentStart) s.timestampMs - lastSampleMs else 0L
        lastSampleMs = s.timestampMs

        if (!s.active) {
            if (phase == Phase.OPEN) discardedInactive++
            phase = Phase.UNKNOWN
            return null
        }

        val v = s.smoothed
        val t = s.timestampMs
        when (phase) {
            Phase.UNKNOWN -> if (v <= close) {
                enterClosed(s)
            } else if (v >= open) {
                // First-event handling: the fingers were already open. The start level is the
                // local baseline, and the tap is still counted when the fingers close.
                troughValue = s.low
                rawTrough = s.low
                troughMs = t
                troughEstimated = true
                enterOpen(s)
            }

            Phase.CLOSED -> {
                if (v < troughValue) {
                    troughValue = v
                    troughMs = t
                }
                if (s.raw < rawTrough) rawTrough = s.raw
                if (v >= open) enterOpen(s)
            }

            Phase.OPEN -> {
                cycleConfidenceSum += s.confidence
                cycleSamples++
                cycleMaxGapMs = max(cycleMaxGapMs, gap)
                if (v > peakValue) {
                    peakValue = v
                    peakMs = t
                }
                if (s.raw > rawPeak) rawPeak = s.raw
                if (v <= close) {
                    val event = evaluate(s, movementFloor)
                    enterClosed(s)
                    return event
                }
            }
        }
        return null
    }

    fun result(): DetectionResult = DetectionResult(
        events = events.toList(),
        rejectedDebounce = rejectedDebounce,
        rejectedTooShort = rejectedTooShort,
        rejectedTooSmall = rejectedTooSmall,
        discardedByDropout = discardedByDropout,
        discardedInactive = discardedInactive,
        incompleteFinalCycle = phase == Phase.OPEN
    )

    private fun enterClosed(s: SignalSample) {
        phase = Phase.CLOSED
        troughValue = s.smoothed
        rawTrough = s.raw
        troughMs = s.timestampMs
        troughEstimated = false
    }

    private fun enterOpen(s: SignalSample) {
        phase = Phase.OPEN
        peakValue = s.smoothed
        rawPeak = s.raw
        peakMs = s.timestampMs
        cycleConfidenceSum = s.confidence
        cycleSamples = 1
        cycleMaxGapMs = 0L
    }

    private fun evaluate(s: SignalSample, movementFloor: Double): TapEvent? {
        val t = s.timestampMs
        val amplitude = rawPeak - rawTrough
        val minAmplitude = max(
            config.minAmplitudeFractionOfRange * s.range,
            config.minAmplitudeFractionOfFloor * movementFloor
        )
        when {
            lastTapMs >= 0 && t - lastTapMs < config.minInterTapIntervalMs -> {
                rejectedDebounce++
                return null
            }

            !troughEstimated && t - troughMs < config.minEventDurationMs -> {
                rejectedTooShort++
                return null
            }

            amplitude < minAmplitude -> {
                rejectedTooSmall++
                return null
            }
        }

        val amplitudeFactor = (amplitude / max(s.range, 1e-9)).coerceIn(0.0, 1.0)
        val continuityFactor = if (cycleMaxGapMs > config.maxCycleGapMs) 0.6 else 1.0
        val trackingFactor = (cycleConfidenceSum / max(cycleSamples, 1)).coerceIn(0.0, 1.0)
        val startFactor = if (troughEstimated) 0.7 else 1.0
        val confidence = (sqrt(amplitudeFactor) * continuityFactor * trackingFactor * startFactor).coerceIn(0.0, 1.0)

        val event = TapEvent(
            index = events.size,
            timestampMs = t,
            startMs = troughMs,
            peakMs = peakMs,
            amplitude = amplitude,
            peakOpening = rawPeak,
            troughOpening = rawTrough,
            openingDurationMs = peakMs - troughMs,
            closingDurationMs = t - peakMs,
            confidence = confidence,
            startEstimated = troughEstimated
        )
        events += event
        lastTapMs = t
        return event
    }
}

/** Offline detection over a complete processed recording (the authoritative result). */
object TapDetector {

    fun detect(signal: ProcessedSignal, config: DetectionConfig = DetectionConfig()): DetectionResult {
        val machine = TapStateMachine(config)
        for (s in signal.samples) {
            machine.onSample(s, signal.closeThreshold(s), signal.openThreshold(s), signal.movementFloor)
        }
        return machine.result()
    }
}
