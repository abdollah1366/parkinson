package com.example.parkinson.biofeedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class BiofeedbackEnginesTest {

    @Test
    fun loudVoiceLearnsTheNormalVoiceThenCountsHolds() {
        val e = LoudVoiceEngine(level = 1) // 4 dB louder for 3 s.
        var t = 0L
        fun feed(db: Double, seconds: Double) = repeat((seconds * 20).toInt()) { e.onLevel(t, db); t += 50 }
        feed(-30.0, 3.0) // Normal voice.
        assertEquals(LoudVoiceEngine.Phase.RESTING, e.snapshot.phase)
        feed(-80.0, 4.0)
        assertEquals(LoudVoiceEngine.Phase.WAITING, e.snapshot.phase)
        // Three good repetitions, then two too soft and short.
        repeat(3) { feed(-25.0, 3.5); feed(-80.0, 5.0) }
        repeat(2) { feed(-28.0, 2.0); feed(-80.0, 5.0) }
        assertTrue(e.done)
        assertEquals(3.0, e.metrics()["repsInTarget"])
        assertEquals(-30.0, e.metrics()["baselineDb"])
        assertEquals(60, e.score())
    }

    @Test
    fun balanceScoresTheTimeInsideTheCircle() {
        val e = BalanceEngine(level = 1) // 5 degrees, 20 s.
        var ns = 0L
        fun feed(sideDeg: Double, seconds: Double) = repeat((seconds * 50).toInt()) {
            val a = Math.toRadians(sideDeg)
            // Upright: gravity along y; leaning sideways moves it into x.
            e.onAccelerometer(ns, (9.81 * sin(a)).toFloat(), (9.81 * cos(a)).toFloat(), 0f)
            ns += 20_000_000
        }
        feed(0.0, 2.1)
        assertFalse(e.snapshot.calibrating)
        feed(1.0, 10.0)
        feed(12.0, 10.5)
        assertTrue(e.done)
        assertTrue("score ${e.score()}", e.score() in 45..55)
        assertTrue(e.metrics()["maxSwayDeg"]!! > 10)
    }

    @Test
    fun metronomeWalkCountsStepsAndComparesTheCadence() {
        val e = MetronomeWalkEngine(level = 1) // 90 a minute for 60 s.
        var ns = 0L
        val stepHz = 1.5 // 90 steps a minute.
        while (!e.done) {
            val t = ns / 1e9
            val bump = 3.0 * maxOf(0.0, sin(2 * PI * stepHz * t))
            e.onAccelerometer(ns, 0f, (9.81 + bump).toFloat(), 0f)
            ns += 20_000_000
        }
        assertTrue("cadence ${e.cadence()}", e.cadence() in 85.0..95.0)
        assertTrue(e.score() >= 90)
    }

    @Test
    fun memoryGrowsAfterACorrectAnswerAndEndsAfterTwoMistakes() {
        val e = MemorySequenceEngine(level = 1, random = Random(7))
        e.shown()
        e.snapshot.sequence.forEach { e.tap(it) }
        assertEquals(4, e.snapshot.length)
        assertEquals(true, e.snapshot.lastCorrect)
        repeat(2) {
            e.shown()
            val first = e.snapshot.sequence.first()
            e.tap((first + 1) % MemorySequenceEngine.CELLS)
        }
        assertTrue(e.done)
        assertEquals(3.0, e.metrics()["maxSpan"])
        assertEquals(33, e.score())
    }

    @Test
    fun tappingCountsOnlyAlternatingTapsForEachHand() {
        val e = TapRhythmEngine(level = 1) // 15 s, target 2 a second.
        var t = 0L
        var target = 0
        while (!e.handDone) { e.tap(target, t); e.tap(target, t + 10); target = 1 - target; t += 400 } // 2.5 a second, even.
        e.switchHand()
        t += 5000
        while (!e.done) { e.tap(target, t); target = 1 - target; t += if (target == 0) 300 else 700 } // 2 a second, uneven.
        val m = e.metrics()
        assertEquals(2.5, m["rateRight"]!!, 0.1)
        assertEquals(2.0, m["rateLeft"]!!, 0.1)
        assertTrue(m["cvLeft"]!! > m["cvRight"]!!)
        assertTrue(e.score() in 60..95)
    }
}
