package com.example.parkinson.biofeedback

import kotlin.random.Random

/**
 * Sequence memory on a 3 x 3 grid: cells light up one after another, then the person taps them in the same order.
 * After a correct answer the sequence grows by one; after [BiofeedbackLevels.MEMORY_MAX_MISTAKES] mistakes, or at the
 * longest length, it ends.
 */
class MemorySequenceEngine(level: Int, private val random: Random = Random.Default) {
    enum class Phase { SHOWING, ANSWERING, DONE }

    data class Snapshot(val phase: Phase, val length: Int, val sequence: List<Int>, val entered: Int, val mistakes: Int, val lastCorrect: Boolean?)

    private var length = BiofeedbackLevels.memoryStartLength(level)
    private var sequence = next(length)
    private var entered = 0
    private var mistakes = 0
    private var rounds = 0
    private var maxSpan = 0
    private var lastCorrect: Boolean? = null
    private var phase = Phase.SHOWING

    val snapshot: Snapshot get() = Snapshot(phase, length, sequence, entered, mistakes, lastCorrect)

    private fun next(n: Int): List<Int> {
        val cells = mutableListOf<Int>()
        while (cells.size < n) {
            val c = random.nextInt(CELLS)
            if (cells.lastOrNull() != c) cells += c // The same cell twice in a row is hard to see.
        }
        return cells
    }

    /** The cells were shown; the person answers now. */
    fun shown() {
        if (phase == Phase.SHOWING) phase = Phase.ANSWERING
    }

    /** The person tapped [cell]. */
    fun tap(cell: Int): Snapshot {
        if (phase != Phase.ANSWERING) return snapshot
        if (cell == sequence[entered]) {
            entered++
            if (entered == sequence.size) {
                rounds++
                maxSpan = maxOf(maxSpan, length)
                lastCorrect = true
                if (length >= BiofeedbackLevels.MEMORY_MAX_LENGTH) phase = Phase.DONE
                else newRound(length + 1)
            }
        } else {
            rounds++
            mistakes++
            lastCorrect = false
            if (mistakes >= BiofeedbackLevels.MEMORY_MAX_MISTAKES) phase = Phase.DONE else newRound(length)
        }
        return snapshot
    }

    private fun newRound(n: Int) {
        length = n
        sequence = next(n)
        entered = 0
        phase = Phase.SHOWING
    }

    val done: Boolean get() = phase == Phase.DONE

    /** The longest sequence repeated correctly, out of the longest there is. */
    fun score(): Int = (maxSpan * 100 / BiofeedbackLevels.MEMORY_MAX_LENGTH).coerceIn(0, 100)

    fun metrics(): Map<String, Double> = mapOf(
        "maxSpan" to maxSpan.toDouble(),
        "rounds" to rounds.toDouble(),
        "mistakes" to mistakes.toDouble(),
    )

    companion object {
        const val CELLS = 9
        const val SHOW_MS = 700L
        const val GAP_MS = 250L
    }
}
