package com.example.parkinson.tapping.detection

import com.example.parkinson.tapping.Stats
import com.example.parkinson.tapping.raw.TapFrame
import com.example.parkinson.tapping.signal.SignalConfig
import com.example.parkinson.tapping.signal.SignalSample
import com.example.parkinson.tapping.signal.TapSignalProcessor

/**
 * Approximate real-time tap count for the RECORDING screen. DISPLAY ONLY.
 *
 * Uses the same [TapStateMachine] and parameters as the offline analysis, but with causal
 * (past-only) smoothing and envelope, because future frames are not known yet. The final
 * result is always computed offline by TapSignalProcessor + TapDetector and may differ by a tap.
 *
 * Fixed-size buffers: no allocation per frame except the SignalSample passed to the machine.
 * Not thread-safe; the session calls it under its lock.
 */
class LiveTapCounter(
    private val signalConfig: SignalConfig = SignalConfig(),
    detectionConfig: DetectionConfig = DetectionConfig()
) {
    private val machine = TapStateMachine(detectionConfig)

    private val capacity = 512
    private val times = LongArray(capacity)
    private val values = DoubleArray(capacity)
    private val scratch = DoubleArray(capacity)
    private var head = 0
    private var size = 0

    private var lastTs = -1L
    private var smoothed = 0.0
    private var scale = 0.0

    val tapCount: Int get() = machine.tapCount

    fun reset() {
        machine.reset()
        head = 0
        size = 0
        lastTs = -1L
    }

    /** Returns the current live tap count. */
    fun onFrame(frame: TapFrame): Int {
        if (!frame.isValid || frame.handScalePx <= 0.0 || frame.timestampMs <= lastTs) return machine.tapCount
        val t = frame.timestampMs
        val segmentStart = lastTs < 0 || t - lastTs > signalConfig.maxBridgeGapMs

        scale = if (segmentStart) {
            frame.handScalePx
        } else {
            val a = TapSignalProcessor.smoothingAlpha(t - lastTs, signalConfig.handScaleHalfWindowMs.toDouble())
            a * frame.handScalePx + (1 - a) * scale
        }
        val raw = frame.thumbIndexDistancePx / scale
        if (!raw.isFinite() || raw > signalConfig.maxPlausibleOpening) return machine.tapCount

        smoothed = if (segmentStart) {
            size = 0
            raw
        } else {
            val a = TapSignalProcessor.smoothingAlpha(t - lastTs, signalConfig.smoothingTauMs)
            a * raw + (1 - a) * smoothed
        }
        lastTs = t
        push(t, smoothed)

        // Trailing envelope window of the same total width as the offline centered window.
        val windowMs = 2 * signalConfig.envelopeHalfWindowMs
        var count = 0
        for (k in 0 until size) {
            val idx = (head - 1 - k + capacity) % capacity
            if (t - times[idx] > windowMs) break
            scratch[count++] = values[idx]
        }
        val low: Double
        val high: Double
        if (count < signalConfig.minEnvelopeSamples) {
            low = smoothed
            high = smoothed
        } else {
            scratch.sort(0, count)
            low = Stats.percentileOfSorted(scratch, count, signalConfig.envelopeLowPercentile)
            high = Stats.percentileOfSorted(scratch, count, signalConfig.envelopeHighPercentile)
        }
        val floor = signalConfig.minMovementRange
        val sample = SignalSample(
            frameIndex = frame.index,
            timestampMs = t,
            raw = raw,
            smoothed = smoothed,
            low = low,
            high = high,
            active = high - low >= floor,
            segmentStart = segmentStart,
            confidence = frame.confidence
        )
        val range = high - low
        machine.onSample(
            sample,
            close = low + signalConfig.closeFraction * range,
            open = low + signalConfig.openFraction * range,
            movementFloor = floor
        )
        return machine.tapCount
    }

    private fun push(t: Long, v: Double) {
        times[head] = t
        values[head] = v
        head = (head + 1) % capacity
        if (size < capacity) size++
    }
}
