package com.example.parkinson.imu

import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import com.example.parkinson.sensors.MotionStreams
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sit-to-stand detection on SYNTHETIC inertial data (device frame, see [SyntheticImu]). */
class SitToStandImuDetectorTest {

    private val reference = Vec3(0.0, 0.0, 1.0)

    private fun analyze(samples: List<MotionSample>): SitToStandImuAnalysis {
        val clean = ImuCleaning.clean(samples)
        val grid = requireNotNull(ImuGridBuilder.build(clean))
        return SitToStandImuDetector.analyze(grid, reference)
    }

    private fun profileSamples(p: SyntheticImu.TransferProfile, jitterNs: Long = 0L, dropFrom: Double = -1.0, dropTo: Double = -1.0) =
        SyntheticImu.samples(
            durationS = p.durationS,
            accelAt = p.let { { t: Double -> it.accel(t) } },
            gyroAt = p.let { { t: Double -> it.gyro(t) } },
            jitterNs = jitterNs,
            dropFrom = dropFrom,
            dropTo = dropTo,
        )

    @Test
    fun fiveCompleteTransfersAreCountedWithTheirDurations() {
        val p = SyntheticImu.TransferProfile()
        val a = analyze(profileSamples(p))
        assertEquals(5, a.repetitions.size)
        assertTrue(a.rejections.isEmpty())
        // Each transfer: about 2.7 s from the start of the rise to the sustained seated posture (the sit-down holds
        // 300 ms below 12 degrees). The gravity low-pass adds a lag of about 0.3 s to the posture angle.
        a.repetitions.forEach { assertEquals(2_700.0, it.durationMs, 350.0) }
        // The total time spans first start to last seated posture: about 4 transfers plus one transfer.
        assertEquals(4 * 4_000.0 + 2_700.0, requireNotNull(a.totalTimeMs), 500.0)
        // Each repetition has rotation evidence: the gyroscope peak is about the 80 deg/s of the synthetic rise.
        a.repetitions.forEach { assertTrue(it.peakRotationDegPerSec >= 25.0) }
    }

    @Test
    fun slowTransfersAreStillCounted() {
        val p = SyntheticImu.TransferProfile(riseS = 2.0, standS = 1.5, sitS = 2.0, seatedS = 1.0, peakDeg = 85.0)
        val a = analyze(profileSamples(p))
        assertEquals(5, a.repetitions.size)
        assertTrue(a.repetitions.all { it.durationMs > 4_000.0 })
    }

    @Test
    fun incompleteStandingBelowTheThresholdIsAPartialRiseNotARepetition() {
        // Peak posture 25 degrees: above the onset, below the 35 degree standing threshold.
        val p = SyntheticImu.TransferProfile(cycles = 3, peakDeg = 25.0)
        val a = analyze(profileSamples(p))
        assertTrue(a.repetitions.isEmpty())
        assertEquals(3, a.rejections.count { it.reason == RejectionReason.PARTIAL_RISE })
    }

    @Test
    fun aRisingPostureWithoutTheSittingTransitionIsNotCounted() {
        // The profile ends while the person is still standing: the open transfer is neither counted nor rejected.
        val p = SyntheticImu.TransferProfile(cycles = 1, standS = 5.0)
        val a = analyze(SyntheticImu.samples(
            durationS = 3.0 + 1.0 + 5.0,
            accelAt = { t -> p.accel(t) },
            gyroAt = { t -> p.gyro(t) },
        ))
        assertTrue(a.repetitions.isEmpty())
        assertTrue(a.rejections.isEmpty())
        assertTrue((a.maxPostureDeg ?: 0.0) >= 35.0)
    }

    @Test
    fun aSingleAccelerationSpikeWithoutPostureChangeCountsNothing() {
        val samples = SyntheticImu.samples(
            durationS = 15.0,
            accelAt = { t ->
                // One spike of 6 m/s^2 along the device's x axis (no change in gravity direction).
                if (t in 7.0..7.1) doubleArrayOf(6.0, 0.0, SyntheticImu.G) else doubleArrayOf(0.0, 0.0, SyntheticImu.G)
            },
        )
        val a = analyze(samples)
        assertTrue(a.repetitions.isEmpty())
        // The gravity low-pass moves a little under the spike, but never enough to start a transfer.
        assertTrue(a.rejections.isEmpty())
    }

    @Test
    fun gyroscopeNoiseAtRestDoesNotCreateTransfers() {
        val rnd = kotlin.random.Random(5)
        val samples = SyntheticImu.samples(
            durationS = 20.0,
            gyroAt = { doubleArrayOf((rnd.nextDouble() - 0.5) * 0.1, (rnd.nextDouble() - 0.5) * 0.1, (rnd.nextDouble() - 0.5) * 0.1) },
        )
        val a = analyze(samples)
        assertTrue(a.repetitions.isEmpty())
        assertTrue(a.rejections.isEmpty())
    }

    @Test
    fun aSensorGapInsideATransferDiscardsIt() {
        // The gap starts inside the first rise (3.0 to 3.8 s): that transfer cannot be trusted.
        val p = SyntheticImu.TransferProfile()
        val a = analyze(profileSamples(p, dropFrom = 3.4, dropTo = 4.2))
        assertTrue(a.rejections.any { it.reason == RejectionReason.SENSOR_GAP })
        assertEquals(1, a.invalidIntervals.size)
        // The four transfers after the gap are still counted.
        assertEquals(4, a.repetitions.size)
        assertTrue(a.repetitions.all { it.standOnsetMs > 4_200.0 })
    }

    @Test
    fun irregularTimestampsDoNotChangeTheCount() {
        val p = SyntheticImu.TransferProfile()
        val a = analyze(profileSamples(p, jitterNs = 3_000_000L))
        assertEquals(5, a.repetitions.size)
    }

    @Test
    fun duplicateAndOutOfOrderTimestampsAreRemovedBeforeAnalysis() {
        val p = SyntheticImu.TransferProfile()
        val base = profileSamples(p)
        // Duplicate every 50th event and move a few events back in time.
        val messy = base.flatMapIndexed { i, s ->
            when {
                i % 50 == 0 -> listOf(s, s)
                i % 97 == 0 && i > 0 -> listOf(s.copy(timestampNs = s.timestampNs - 5_000_000L), s)
                else -> listOf(s)
            }
        }
        val a = analyze(messy)
        assertEquals(5, a.repetitions.size)
    }

    @Test
    fun aRotationWithoutRotationEvidenceIsRejected() {
        // The posture changes over 6 seconds: about 13 degrees per second, below the 25 degree per second evidence.
        val p = SyntheticImu.TransferProfile(calibrationS = 2.0, cycles = 1, riseS = 6.0, standS = 1.0, sitS = 6.0, seatedS = 1.0)
        val a = analyze(profileSamples(p))
        assertTrue(a.repetitions.isEmpty())
        assertTrue(a.rejections.any { it.reason == RejectionReason.NO_ROTATION_EVIDENCE })
    }

    @Test
    fun calibrationAcceptsAStillPhoneAndRejectsAMovingOne() {
        val still = requireNotNull(ImuGridBuilder.build(SyntheticImu.samples(durationS = 4.0)))
        val ok = SensorCalibration.calibrate(still, 0.0, 4_000.0)
        assertTrue(ok is CalibrationOutcome.Ready)
        val rotating = run {
            requireNotNull(ImuGridBuilder.build(profileSamples(SyntheticImu.TransferProfile(calibrationS = 0.0, cycles = 1))))
        }
        assertEquals(CalibrationOutcome.Failed(CalibrationIssue.NOT_STILL), SensorCalibration.calibrate(rotating, 0.0, 3_000.0))
    }

    @Test
    fun calibrationRejectsImplausibleGravity() {
        val weird = requireNotNull(ImuGridBuilder.build(SyntheticImu.samples(durationS = 4.0, accelAt = { doubleArrayOf(0.0, 0.0, 3.0) })))
        assertEquals(CalibrationOutcome.Failed(CalibrationIssue.IMPLAUSIBLE_GRAVITY), SensorCalibration.calibrate(weird, 0.0, 4_000.0))
    }

    @Test
    fun tooLittleDataGivesNoGridAndInsufficientCalibration() {
        assertNull(ImuGridBuilder.build(SyntheticImu.samples(durationS = 0.005)))
        val short = requireNotNull(ImuGridBuilder.build(SyntheticImu.samples(durationS = 1.2)))
        assertEquals(CalibrationOutcome.Failed(CalibrationIssue.INSUFFICIENT_DATA), SensorCalibration.calibrate(short, 0.0, 1_200.0))
    }

    @Test
    fun gridCoverageDropsWhereTheSensorsWereSilent() {
        val grid = requireNotNull(ImuGridBuilder.build(profileSamples(SyntheticImu.TransferProfile(), dropFrom = 1.0, dropTo = 2.0)))
        // A 1 s gap in a 23 s recording is about 4 % of the grid.
        assertTrue(grid.coveragePercent < 97.0 && grid.coveragePercent > 90.0)
        assertFalse(grid.valid[grid.size / 4] && grid.timeMs[grid.size / 4] in 1_100.0..1_900.0)
        assertNotNull(grid.gravityAngleDeg(0, reference).takeIf { it.isFinite() })
    }

    @Test
    fun postureAngleAndTotalTimeHaveTheExpectedUnits() {
        val p = SyntheticImu.TransferProfile(calibrationS = 0.0, cycles = 1, riseS = 1.0, standS = 1.0, sitS = 1.0, seatedS = 1.0, peakDeg = 80.0)
        val grid = requireNotNull(ImuGridBuilder.build(profileSamples(p)))
        // At the end of the stand hold the posture is 80 degrees (the gravity estimate tracks the rotation with a lag).
        val i = (2_000.0 * grid.gridHz / 1000.0).toInt()
        assertEquals(80.0, grid.gravityAngleDeg(i, reference), 2.0)
    }


}
