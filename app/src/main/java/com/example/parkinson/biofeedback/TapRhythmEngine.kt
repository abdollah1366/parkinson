package com.example.parkinson.biofeedback

import kotlin.math.sqrt

/**
 * Tapping two targets in turn with the index finger, as fast and as evenly as possible: the right hand for
 * [durationSec], then the left. Only a tap on the other target than the last one counts.
 */
class TapRhythmEngine(level: Int) {
    val durationSec = BiofeedbackLevels.tapDurationSec(level)
    val targetRate = BiofeedbackLevels.tapTargetRate(level)

    enum class Hand { RIGHT, LEFT }

    data class Snapshot(val hand: Hand, val running: Boolean, val taps: Int, val elapsedSec: Double, /** Taps a second over the last 3 s. */ val rate: Double, val next: Int)

    private val taps = mapOf(Hand.RIGHT to mutableListOf<Long>(), Hand.LEFT to mutableListOf<Long>())
    private var hand = Hand.RIGHT
    private var startMs = -1L
    private var lastTarget = -1
    private var finished = mutableSetOf<Hand>()

    var snapshot = Snapshot(Hand.RIGHT, false, 0, 0.0, 0.0, 0)
        private set

    /** A tap on target 0 or 1 at [timeMs]; the first tap of a hand starts its time. */
    fun tap(target: Int, timeMs: Long): Snapshot {
        if (hand in finished) return snapshot
        if (startMs < 0) startMs = timeMs
        tick(timeMs)
        if (hand in finished) return snapshot
        if (target != lastTarget) {
            taps.getValue(hand) += timeMs
            lastTarget = target
        }
        return tick(timeMs)
    }

    /** Moves the clock; ends the hand's time. */
    fun tick(timeMs: Long): Snapshot {
        if (startMs < 0 || hand in finished) return snapshot
        val elapsed = (timeMs - startMs) / 1000.0
        if (elapsed >= durationSec) finished += hand
        val list = taps.getValue(hand)
        val recent = list.count { timeMs - it <= 3000 }
        snapshot = Snapshot(hand, hand !in finished, list.size, minOf(elapsed, durationSec), recent / minOf(3.0, maxOf(elapsed, 0.5)), if (lastTarget < 0) 0 else 1 - lastTarget)
        return snapshot
    }

    /** After the right hand: the left hand, from its first tap. */
    fun switchHand() {
        if (hand == Hand.RIGHT && Hand.RIGHT in finished) {
            hand = Hand.LEFT
            startMs = -1
            lastTarget = -1
            snapshot = Snapshot(Hand.LEFT, false, 0, 0.0, 0.0, 0)
        }
    }

    val handDone: Boolean get() = hand in finished
    val done: Boolean get() = Hand.LEFT in finished

    private fun rate(h: Hand) = taps.getValue(h).size / durationSec

    /** Variation of the intervals between taps, % of their mean. */
    private fun cv(h: Hand): Double {
        val t = taps.getValue(h)
        if (t.size < 3) return 100.0
        val gaps = t.zipWithNext { a, b -> (b - a).toDouble() }
        val mean = gaps.average()
        val sd = sqrt(gaps.sumOf { (it - mean) * (it - mean) } / gaps.size)
        return if (mean <= 0) 100.0 else sd / mean * 100
    }

    private fun handScore(h: Hand): Double =
        60 * minOf(1.0, rate(h) / targetRate) + 40 * maxOf(0.0, 1 - cv(h) / MAX_CV)

    fun score(): Int {
        val hands = finished.ifEmpty { return 0 }
        return (hands.sumOf { handScore(it) } / 2).toInt().coerceIn(0, 100)
    }

    fun metrics(): Map<String, Double> = mapOf(
        "taps" to taps.values.sumOf { it.size }.toDouble(),
        "rateRight" to rate(Hand.RIGHT).round1(),
        "rateLeft" to rate(Hand.LEFT).round1(),
        "cvRight" to cv(Hand.RIGHT).round1(),
        "cvLeft" to cv(Hand.LEFT).round1(),
    )

    companion object {
        /** At this variation (%) the evenness part of the score is 0. */
        const val MAX_CV = 40.0
    }
}
