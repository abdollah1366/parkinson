package com.example.parkinson.speech

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * SYNTHETIC audio for deterministic algorithm tests only. Never a patient recording and never evidence of
 * clinical accuracy or real-world recording quality.
 */
object SyntheticSpeech {

    const val RATE = 16_000

    /** A pure tone of [frequencyHz] for [durationMs] at amplitude [amp] (unit scale). */
    fun tone(frequencyHz: Double, durationMs: Int, amp: Double = 0.3, rate: Int = RATE): DoubleArray {
        val n = durationMs * rate / 1000
        return DoubleArray(n) { amp * sin(2 * PI * frequencyHz * it / rate) }
    }

    fun silence(durationMs: Int, rate: Int = RATE): DoubleArray = DoubleArray(durationMs * rate / 1000)

    /** Uniform white noise with peak [amp] (seeded, so the test is deterministic). */
    fun noise(durationMs: Int, amp: Double, seed: Int = 7, rate: Int = RATE): DoubleArray {
        val rnd = Random(seed)
        return DoubleArray(durationMs * rate / 1000) { (rnd.nextDouble() * 2 - 1) * amp }
    }

    /** Bursts of a 1 kHz tone of [burstMs] every [periodMs], [count] bursts, starting after [leadMs] of silence. */
    fun bursts(count: Int, burstMs: Int, periodMs: Int, leadMs: Int = 500, amp: Double = 0.3, rate: Int = RATE): DoubleArray {
        val out = ArrayList<Double>()
        out.addAll(silence(leadMs, rate).toList())
        repeat(count) {
            out.addAll(tone(1000.0, burstMs, amp, rate).toList())
            out.addAll(silence(periodMs - burstMs, rate).toList())
        }
        out.addAll(silence(leadMs, rate).toList())
        return out.toDoubleArray()
    }

    fun concat(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return out
    }

    /** Converts unit-scale samples to 16-bit PCM, clamping to the 16-bit range. */
    fun toPcm(x: DoubleArray): ShortArray = ShortArray(x.size) {
        (x[it] * 32767.0).coerceIn(-32768.0, 32767.0).toInt().toShort()
    }

    fun capture(x: DoubleArray, rate: Int = RATE, requested: Int = RATE): AudioCapture =
        AudioCapture(toPcm(x), rate, requested)
}
