package com.example.parkinson.imu

import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Gait step detection on SYNTHETIC inertial data (device frame, see [SyntheticImu]). Not clinical evidence. */
class GaitStepDetectorTest {

    private fun analyze(samples: List<MotionSample>): GaitImuAnalysis {
        val clean = ImuCleaning.clean(samples)
        val grid = requireNotNull(ImuGridBuilder.build(clean))
        return GaitStepDetector.analyze(grid)
    }

    @Test
    fun steadyWalkingGivesTwoStepsPerStrideAndTheExpectedCadence() {
        // Stride 1 Hz: two steps per stride, so 2 steps per second, about 120 steps per minute, for 30 s.
        val samples = SyntheticImu.samples(durationS = 30.0, accelAt = { t -> SyntheticImu.walkingAccel(t, strideHz = 1.0) })
        val a = analyze(samples)
        assertTrue("steps=${a.steps.size}", a.steps.size in 52..62)
        val mean = a.intervalsMs.average()
        assertEquals(500.0, mean, 40.0)
        assertTrue(a.bouts.size >= 1)
        assertEquals(0, a.turnRejected)
    }

    @Test
    fun slowWalkingIsDetectedAtItsLowerRate() {
        // Stride 0.6 Hz: 1.2 steps per second, about 72 per minute; intervals about 830 ms (inside the plausible band).
        val samples = SyntheticImu.samples(durationS = 30.0, accelAt = { t -> SyntheticImu.walkingAccel(t, strideHz = 0.6) })
        val a = analyze(samples)
        assertTrue("steps=${a.steps.size}", a.steps.size in 30..40)
        assertEquals(833.0, a.intervalsMs.average(), 60.0)
    }

    @Test
    fun variableStepIntervalsAreFollowedWithoutFixedRateAssumptions() {
        // Stride frequency f(t) = 0.9 + 0.2 sin(0.9 t) Hz, so the stride phase is the exact integral of f.
        // The phase is 0.9 t - (0.2 / 0.9) cos(0.9 t) (plus a constant), so the signal is a pure function of t.
        val rnd = Random(21)
        val samples = SyntheticImu.samples(durationS = 30.0, accelAt = { t ->
            val phase = 0.9 * t - (0.2 / 0.9) * kotlin.math.cos(0.9 * t)
            val v = 2.5 * kotlin.math.cos(2 * PI * phase) + (rnd.nextDouble() - 0.5) * 0.1
            doubleArrayOf(0.0, 0.0, SyntheticImu.G + v)
        })
        val a = analyze(samples)
        assertTrue("steps=${a.steps.size}", a.steps.size in 30..60)
        val cv = ImuStats.cv(a.intervalsMs)
        assertNotNull(cv)
        assertTrue("cv=$cv", cv!! > 0.0 && cv < 30.0)
    }

    @Test
    fun isolatedFalsePeaksAreNotCountedAsSteps() {
        // A single 3 m/s^2 jolt every 4 s (phone handling), no walking.
        val samples = SyntheticImu.samples(durationS = 30.0, accelAt = { t ->
            val jolt = (t % 4.0) in 1.0..1.06
            doubleArrayOf(0.0, 0.0, SyntheticImu.G + if (jolt) 3.0 else 0.0)
        })
        val a = analyze(samples)
        assertTrue("steps=${a.steps.size}", a.steps.isEmpty())
        assertTrue(a.implausibleRejected + a.candidatePeaks >= 0)
    }

    @Test
    fun pureNoiseDoesNotProduceWalkingBouts() {
        val rnd = Random(9)
        val samples = SyntheticImu.samples(durationS = 30.0, accelAt = {
            doubleArrayOf((rnd.nextDouble() - 0.5) * 0.3, (rnd.nextDouble() - 0.5) * 0.3, SyntheticImu.G + (rnd.nextDouble() - 0.5) * 0.3)
        })
        val a = analyze(samples)
        assertTrue("steps=${a.steps.size}", a.steps.size <= 4)
    }

    @Test
    fun standingStillGivesNoSteps() {
        val a = analyze(SyntheticImu.samples(durationS = 30.0))
        assertTrue(a.steps.isEmpty())
        assertTrue(a.bouts.isEmpty())
    }

    @Test
    fun aPauseSplitsTheWalkingAndTheStepsResume() {
        // Walking 0-10 s, rest 10-15 s, walking 15-25 s.
        val samples = SyntheticImu.samples(durationS = 25.0, accelAt = { t ->
            if (t in 10.0..15.0) doubleArrayOf(0.0, 0.0, SyntheticImu.G)
            else SyntheticImu.walkingAccel(t, strideHz = 1.0)
        })
        val a = analyze(samples)
        assertEquals(2, a.bouts.size)
        assertTrue("steps=${a.steps.size}", a.steps.size in 36..44)
        assertTrue(a.steps.none { it.timeMs in 10_500.0..14_500.0 })
    }

    @Test
    fun aTurnExcludesTheStepsNearIt() {
        val turns = listOf(10.0 to 11.5)
        val samples = SyntheticImu.samples(
            durationS = 25.0,
            accelAt = { t -> SyntheticImu.walkingAccel(t, strideHz = 1.0) },
            gyroAt = { t -> SyntheticImu.turnGyro(t, turns, degPerS = 60.0) },
        )
        val a = analyze(samples)
        assertTrue("turnRejected=${a.turnRejected}", a.turnRejected > 0)
        assertEquals(1500.0, a.turningMs, 150.0)
        assertTrue(a.steps.none { it.timeMs in 9_500.0..12_000.0 })
    }

    @Test
    fun aSensorGapBreaksTheBoutsButKeepsTheRest() {
        val samples = SyntheticImu.samples(
            durationS = 25.0,
            accelAt = { t -> SyntheticImu.walkingAccel(t, strideHz = 1.0) },
            dropFrom = 10.0,
            dropTo = 11.0,
        )
        val a = analyze(samples)
        assertTrue(a.invalidIntervals.isNotEmpty())
        assertTrue(a.coveragePercent < 98.0)
        assertTrue("steps=${a.steps.size}", a.steps.size >= 40)
    }

    @Test
    fun noisyWalkingStillYieldsPlausibleSteps() {
        val rnd = Random(4)
        val samples = SyntheticImu.samples(durationS = 30.0, accelAt = { t ->
            val v = SyntheticImu.walkingAccel(t, strideHz = 1.0)[2] - SyntheticImu.G
            doubleArrayOf((rnd.nextDouble() - 0.5) * 0.4, 0.0, SyntheticImu.G + v + (rnd.nextDouble() - 0.5) * 0.4)
        })
        val a = analyze(samples)
        assertTrue("steps=${a.steps.size}", a.steps.size in 40..70)
    }

    @Test
    fun timestampsAndDurationsAreComputedFromTheSensorClock() {
        val samples = SyntheticImu.samples(durationS = 20.0, accelAt = { t -> SyntheticImu.walkingAccel(t) })
        val a = analyze(samples)
        val bout = a.bouts.first()
        assertEquals(bout.steps, a.steps.count { it.timeMs in bout.startMs..bout.endMs })
        assertTrue(bout.endMs > bout.startMs)
    }
}
