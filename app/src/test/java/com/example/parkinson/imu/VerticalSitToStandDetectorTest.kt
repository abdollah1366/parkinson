package com.example.parkinson.imu

import com.example.parkinson.sensors.MotionSample
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Sit-to-stand with the phone held at the chest, on SYNTHETIC data. The model: the trunk rises [riseM] and leans
 * forward up to [leanDeg] in the middle of each transfer, so the phone is upright both seated and standing and its tilt
 * never reaches the posture detector's standing threshold. Not a patient recording.
 */
class VerticalSitToStandDetectorTest {

    private val reference = Vec3(0.0, 1.0, 0.0)

    private class ChestTransfers(
        val calibrationS: Double = 3.0,
        val cycles: Int = 5,
        val riseS: Double = 1.2,
        val standS: Double = 1.0,
        val sitS: Double = 1.4,
        val seatedS: Double = 1.0,
        val riseM: Double = 0.4,
        val leanDeg: Double = 30.0,
        val forwardM: Double = 0.15,
        /** Hand tremor: vertical acceleration amplitude (m/s^2) at 5 Hz. */
        val tremor: Double = 0.0,
    ) {
        val durationS get() = calibrationS + cycles * (riseS + standS + sitS + seatedS) + 1.0

        /** (phase 0-1 inside a rise or a sit, +1 rising / -1 sitting), or null when still. */
        private fun phase(t: Double): Pair<Double, Int>? {
            var local = t - calibrationS
            if (local < 0) return null
            val cycle = riseS + standS + sitS + seatedS
            if (local >= cycles * cycle) return null
            local %= cycle
            return when {
                local < riseS -> local / riseS to 1
                local < riseS + standS -> null
                local < riseS + standS + sitS -> (local - riseS - standS) / sitS to -1
                else -> null
            }
        }

        private fun durationOf(dir: Int) = if (dir > 0) riseS else sitS

        /** Vertical acceleration (m/s^2): second derivative of riseM * (1 - cos(pi u)) / 2. */
        fun verticalAccel(t: Double): Double {
            val (u, dir) = phase(t) ?: return 0.0
            val d = durationOf(dir)
            return dir * riseM / 2 * (PI / d) * (PI / d) * cos(PI * u)
        }

        /** Forward acceleration of forwardM * sin(pi u)^2 (forward and back). */
        fun forwardAccel(t: Double): Double {
            val (u, dir) = phase(t) ?: return 0.0
            val d = durationOf(dir)
            return forwardM * 2 * (PI / d) * (PI / d) * cos(2 * PI * u)
        }

        fun lean(t: Double): Double {
            val (u, _) = phase(t) ?: return 0.0
            return Math.toRadians(leanDeg) * sin(PI * u)
        }

        fun leanRate(t: Double): Double {
            val (u, dir) = phase(t) ?: return 0.0
            return Math.toRadians(leanDeg) * PI / durationOf(dir) * cos(PI * u)
        }

        fun samples(seed: Int = 7): List<MotionSample> {
            val rnd = Random(seed)
            fun noise(sd: Double) = (rnd.nextDouble() + rnd.nextDouble() + rnd.nextDouble() - 1.5) * 2 * sd
            return SyntheticImu.samples(
                durationS = durationS,
                accelAt = { t ->
                    // Phone upright at the chest: "up" along +y; leaning by phi rotates the phone about x.
                    val phi = lean(t)
                    val up = doubleArrayOf(0.0, cos(phi), sin(phi))
                    val forward = doubleArrayOf(0.0, -sin(phi), cos(phi))
                    val v = SyntheticImu.G + verticalAccel(t) + tremor * sin(2 * PI * 5.0 * t)
                    val f = forwardAccel(t)
                    DoubleArray(3) { k -> v * up[k] + f * forward[k] + 0.03 + noise(0.05) }
                },
                // A physical rotation by +phi about x turns the world "up" seen by the phone the other way.
                gyroAt = { t -> doubleArrayOf(-leanRate(t) + noise(0.01), noise(0.01), noise(0.01)) },
            )
        }
    }

    private fun grid(samples: List<MotionSample>) = requireNotNull(ImuGridBuilder.build(ImuCleaning.clean(samples)))

    @Test
    fun fiveTransfersWithThePhoneAtTheChestAreCounted() {
        val p = ChestTransfers()
        val g = grid(p.samples())
        // The tilt alone does not reach the standing threshold: the posture detector counts nothing.
        assertEquals(0, SitToStandImuDetector.analyze(g, reference).repetitions.size)

        val a = SitToStandImuDetector.analyzeAnyPlacement(g, reference)
        assertTrue(a.byVerticalMovement)
        assertEquals(5, a.repetitions.size)
        // Rise 1.2 s + standing 1.0 s + sit 1.4 s.
        a.repetitions.forEach { assertEquals(3_600.0, it.durationMs, 400.0) }
        assertEquals(4 * 4_600.0 + 3_600.0, requireNotNull(a.totalTimeMs), 600.0)
    }

    @Test
    fun aSlowAndShallowTransferIsStillCounted() {
        val p = ChestTransfers(riseS = 2.2, sitS = 2.5, riseM = 0.25, leanDeg = 15.0)
        val a = SitToStandImuDetector.analyzeAnyPlacement(grid(p.samples()), reference)
        assertEquals(5, a.repetitions.size)
    }

    @Test
    fun aHalfRiseIsNotARepetition() {
        val p = ChestTransfers(cycles = 3, riseM = 0.08)
        val a = VerticalSitToStandDetector.analyze(grid(p.samples()), reference)
        assertEquals(0, a.repetitions.size)
    }

    @Test
    fun tremorOfAStillHandIsNotATransfer() {
        val p = ChestTransfers(cycles = 0, calibrationS = 20.0, tremor = 0.5)
        val a = VerticalSitToStandDetector.analyze(grid(p.samples()), reference)
        assertEquals(0, a.repetitions.size)
    }

    @Test
    fun aPocketRecordingKeepsThePostureResult() {
        val p = SyntheticImu.TransferProfile()
        val samples = SyntheticImu.samples(durationS = p.durationS, accelAt = { p.accel(it) }, gyroAt = { p.gyro(it) })
        val a = SitToStandImuDetector.analyzeAnyPlacement(grid(samples), Vec3(0.0, 0.0, 1.0))
        assertEquals(5, a.repetitions.size)
        assertFalse(a.byVerticalMovement)
    }
}
