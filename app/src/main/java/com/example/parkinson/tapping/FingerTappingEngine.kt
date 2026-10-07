package com.example.parkinson.tapping

import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.HandTrackingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Detects taps from the thumb-tip / index-tip distance.
 *
 * opening = distance(thumbTip, indexTip) / distance(wrist, middleFingerMcp)
 * so the value does not depend on how far the hand is from the camera.
 *
 * A tap = the opening goes above [openThreshold] and then falls below [closeThreshold]
 * (hysteresis, so noise around a single threshold is not counted twice).
 *
 * The thresholds are starting values. Tune them with real recordings.
 *
 * Usage (timestamps must come from the same clock as HandTrackingResult.timestampMs,
 * i.e. SystemClock.uptimeMillis()):
 *   engine.start(SystemClock.uptimeMillis())
 *   handManager.result.collect { engine.onFrame(it) }
 *   val metrics = engine.finish(SystemClock.uptimeMillis())
 */
class FingerTappingEngine(
    private val closeThreshold: Float = 0.25f,
    private val openThreshold: Float = 0.50f,
    /** 0..1. Higher = less smoothing. */
    private val smoothing: Float = 0.5f,
    private val hesitationFactor: Float = 2.0f
) {

    private enum class Phase { UNKNOWN, OPEN, CLOSED }

    private var phase = Phase.UNKNOWN
    private var running = false
    private var startMs = 0L
    private var smoothedOpening: Float? = null
    private var peakInOpenPhase = 0f
    private val taps = mutableListOf<TapEvent>()
    private var totalFrames = 0
    private var trackedFrames = 0

    private val _tapCount = MutableStateFlow(0)
    val tapCount: StateFlow<Int> = _tapCount.asStateFlow()

    /** Latest smoothed opening value, handy for debugging or a live indicator. */
    private val _opening = MutableStateFlow(0f)
    val opening: StateFlow<Float> = _opening.asStateFlow()

    fun start(timestampMs: Long) {
        reset()
        running = true
        startMs = timestampMs
    }

    fun reset() {
        running = false
        phase = Phase.UNKNOWN
        smoothedOpening = null
        peakInOpenPhase = 0f
        taps.clear()
        totalFrames = 0
        trackedFrames = 0
        _tapCount.value = 0
        _opening.value = 0f
    }

    fun onFrame(result: HandTrackingResult) {
        if (!running) return
        totalFrames++

        // Only a confident, correct, single hand is used.
        val hand = result as? HandTrackingResult.HandDetected ?: return
        val raw = openingRatio(hand) ?: return
        trackedFrames++

        val previous = smoothedOpening
        val value = if (previous == null) raw else smoothing * raw + (1f - smoothing) * previous
        smoothedOpening = value
        _opening.value = value

        when (phase) {
            Phase.UNKNOWN -> {
                if (value >= openThreshold) {
                    phase = Phase.OPEN
                    peakInOpenPhase = value
                } else if (value <= closeThreshold) {
                    phase = Phase.CLOSED
                }
            }

            Phase.CLOSED -> {
                if (value >= openThreshold) {
                    phase = Phase.OPEN
                    peakInOpenPhase = value
                }
            }

            Phase.OPEN -> {
                peakInOpenPhase = max(peakInOpenPhase, value)
                if (value <= closeThreshold) {
                    taps += TapEvent(hand.timestampMs, peakInOpenPhase)
                    phase = Phase.CLOSED
                    _tapCount.value = taps.size
                }
            }
        }
    }

    fun finish(endTimestampMs: Long): FingerTappingMetrics {
        running = false
        return computeMetrics(endTimestampMs)
    }

    private fun openingRatio(hand: HandTrackingResult.HandDetected): Float? {
        val lm = hand.landmarks
        if (lm.size < HandLandmarkIndex.COUNT) return null

        // Normalized x/y have different scales; convert to pixels first.
        val w = hand.imageWidth.toFloat()
        val h = hand.imageHeight.toFloat()
        fun dist(a: HandLandmark, b: HandLandmark) = hypot((a.x - b.x) * w, (a.y - b.y) * h)

        val handSize = dist(lm[HandLandmarkIndex.WRIST], lm[HandLandmarkIndex.MIDDLE_FINGER_MCP])
        if (handSize < 1e-3f) return null

        return dist(lm[HandLandmarkIndex.THUMB_TIP], lm[HandLandmarkIndex.INDEX_FINGER_TIP]) / handSize
    }

    private fun computeMetrics(endMs: Long): FingerTappingMetrics {
        val n = taps.size
        val durationS = ((endMs - startMs) / 1000f).coerceAtLeast(0f)

        val intervals = taps.zipWithNext { a, b -> (b.timestampMs - a.timestampMs).toFloat() }
        val meanInterval = if (intervals.isNotEmpty()) intervals.average().toFloat() else 0f

        val frequency = if (n >= 2) {
            val span = (taps.last().timestampMs - taps.first().timestampMs) / 1000f
            if (span > 0f) (n - 1) / span else 0f
        } else 0f

        val variability = if (intervals.size >= 2 && meanInterval > 0f) {
            val variance = intervals.map { (it - meanInterval) * (it - meanInterval) }.average()
            (sqrt(variance).toFloat() / meanInterval) * 100f
        } else 0f

        val amplitudes = taps.map { it.peakOpening }
        val meanAmplitude = if (amplitudes.isNotEmpty()) amplitudes.average().toFloat() else 0f

        val decrement = if (n >= 6) {
            val third = n / 3
            val first = amplitudes.take(third).average().toFloat()
            val last = amplitudes.takeLast(third).average().toFloat()
            if (first > 0f) (first - last) / first * 100f else 0f
        } else 0f

        val hesitations = if (intervals.size >= 3) {
            val sorted = intervals.sorted()
            val median = if (sorted.size % 2 == 1) {
                sorted[sorted.size / 2]
            } else {
                (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2f
            }
            intervals.count { it > median * hesitationFactor }
        } else 0

        val quality = if (totalFrames > 0) trackedFrames.toFloat() / totalFrames else 0f

        return FingerTappingMetrics(
            tapCount = n,
            testDurationSeconds = durationS,
            frequencyHz = frequency,
            meanIntervalMs = meanInterval,
            intervalVariabilityPercent = variability,
            meanAmplitude = meanAmplitude,
            amplitudeDecrementPercent = decrement,
            hesitationCount = hesitations,
            trackingQuality = quality,
            taps = taps.toList()
        )
    }
}
