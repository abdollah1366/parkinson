package com.example.parkinson.sts

import com.example.parkinson.gait.PoseLandmarkIndex
import com.example.parkinson.gait.PoseOrientation
import com.example.parkinson.gait.PosePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** SYNTHETIC landmarks for unit tests only. The image is 480 x 640 px (upright). */
class SitToStandPoseTest {

    private val extractor = SitToStandPoseExtractor()

    /** Right side view: shoulder above hip, knee and ankle below; [kneeX] moves the ankle to bend the knee. */
    private fun side(
        ankleX: Double = 0.5,
        ankleY: Double = 0.9,
        visibility: Double = 0.95,
        shoulderX: Double = 0.5,
    ): MutableList<PosePoint?> {
        val p = MutableList<PosePoint?>(PoseLandmarkIndex.COUNT) { null }
        fun set(i: Int, x: Double, y: Double) { p[i] = PosePoint(x, y, visibility) }
        set(PoseLandmarkIndex.RIGHT_SHOULDER, shoulderX, 0.2)
        set(PoseLandmarkIndex.RIGHT_HIP, 0.5, 0.4)
        set(PoseLandmarkIndex.RIGHT_KNEE, 0.5, 0.6)
        set(PoseLandmarkIndex.RIGHT_ANKLE, ankleX, ankleY)
        return p
    }

    private fun extract(points: List<PosePoint?>?, side: SitToStandSide = SitToStandSide.RIGHT) =
        extractor.extract(1_000L, points, 480, 640, side)

    @Test
    fun straightLegGivesAKneeAngleOf180AndTheShinLength() {
        val s = extract(side())
        assertTrue(s.isValid)
        // Hip above the knee, ankle below it: a straight leg.
        assertEquals(180.0, s.kneeAngleDeg, 1e-6)
        // Shin: (0.9 - 0.6) x 640 = 192 px.
        assertEquals(192.0, s.shinPx, 1e-6)
        // Hip y: 0.4 x 640 = 256 px.
        assertEquals(256.0, s.hipYPx, 1e-6)
        assertEquals(0.0, s.trunkLeanDeg, 1e-6)
    }

    @Test
    fun bentKneeGivesTheRightAngle() {
        // Ankle forward at knee height (x 0.6, y 0.6): knee-to-ankle horizontal, knee-to-hip vertical: 90 degrees.
        val s = extract(side(ankleX = 0.6, ankleY = 0.6))
        assertEquals(90.0, s.kneeAngleDeg, 1e-6)
    }

    @Test
    fun trunkLeanIsTheAngleFromTheImageVertical() {
        // Shoulder 0.05 x 480 = 24 px to the right of the hip; the hip-to-shoulder vertical is 0.2 x 640 = 128 px.
        val s = extract(side(shoulderX = 0.55))
        assertEquals(Math.toDegrees(Math.atan2(24.0, 128.0)), s.trunkLeanDeg, 1e-6)
    }

    @Test
    fun noPersonIsNoPose() {
        assertEquals(SitToStandIssue.NO_POSE, extract(null).issue)
    }

    @Test
    fun aLowVisibilityJointMakesTheFrameInvalidAndIsNotFilledIn() {
        val s = extract(side(visibility = 0.3))
        assertEquals(SitToStandIssue.LOW_VISIBILITY, s.issue)
        assertTrue(s.kneeAngleDeg.isNaN())
        assertTrue(s.hipYPx.isNaN())
    }

    @Test
    fun aJointOutsideTheImageIsOutOfFrame() {
        val p = side()
        p[PoseLandmarkIndex.RIGHT_ANKLE] = PosePoint(0.5, 1.3, 0.95)
        assertEquals(SitToStandIssue.OUT_OF_FRAME, extract(p).issue)
    }

    @Test
    fun aTinyLowerLegIsTooSmall() {
        val p = side()
        p[PoseLandmarkIndex.RIGHT_ANKLE] = PosePoint(0.5, 0.6 + 0.01, 0.95) // 0.01 x 640 = 6.4 px
        assertEquals(SitToStandIssue.TOO_SMALL, extract(p).issue)
    }

    @Test
    fun wrongLandmarkCountIsAnError() {
        assertEquals(SitToStandIssue.ERROR, extractor.extract(0L, listOf(null), 480, 640, SitToStandSide.RIGHT).issue)
    }

    @Test
    fun theSideWithBetterVisibilityIsChosen() {
        val p = MutableList<PosePoint?>(PoseLandmarkIndex.COUNT) { null }
        fun set(i: Int, v: Double) { p[i] = PosePoint(0.5, 0.5, v) }
        set(PoseLandmarkIndex.LEFT_HIP, 0.2); set(PoseLandmarkIndex.LEFT_KNEE, 0.2); set(PoseLandmarkIndex.LEFT_ANKLE, 0.2)
        set(PoseLandmarkIndex.RIGHT_HIP, 0.9); set(PoseLandmarkIndex.RIGHT_KNEE, 0.9); set(PoseLandmarkIndex.RIGHT_ANKLE, 0.8)
        assertEquals(SitToStandSide.RIGHT, extractor.chooseSide(p))
    }

    @Test
    fun calibrationAcceptsAStableSeatedBodyAndDerivesTheBaseline() {
        val calibrator = SeatedCalibrator()
        for (i in 0 until 40) {
            // Seated: knee about 95 degrees with small jitter; hip at y 330 px; shin 180 px.
            calibrator.add(
                SitToStandSample(
                    timestampMs = i * 50L,
                    issue = null,
                    side = SitToStandSide.LEFT,
                    visibility = 0.9,
                    kneeAngleDeg = 95.0 + if (i % 2 == 0) 1.5 else -1.5,
                    hipYPx = 330.0,
                    shinPx = 180.0,
                    trunkLeanDeg = 3.0,
                )
            )
        }
        val outcome = calibrator.result()
        val ready = (outcome as CalibrationOutcome.Ready).baseline
        assertEquals(SitToStandSide.LEFT, ready.side)
        assertEquals(95.0, ready.kneeAngleDeg, 1.6)
        assertEquals(330.0, ready.hipYPx, 1e-9)
        assertEquals(180.0, ready.shinPx, 1e-9)
    }

    @Test
    fun calibrationRejectsAStandingPersonAsNotSeated() {
        val calibrator = SeatedCalibrator()
        repeat(40) { calibrator.add(sampleWithKnee(it * 50L, 170.0)) }
        assertEquals(CalibrationOutcome.Failed(CalibrationIssue.NOT_SEATED), calibrator.result())
    }

    @Test
    fun calibrationRejectsAMovingKneeAsUnstable() {
        val calibrator = SeatedCalibrator()
        repeat(40) { calibrator.add(sampleWithKnee(it * 50L, if (it % 2 == 0) 70.0 else 120.0)) }
        assertEquals(CalibrationOutcome.Failed(CalibrationIssue.UNSTABLE), calibrator.result())
    }

    @Test
    fun calibrationNeedsEnoughValidFrames() {
        val calibrator = SeatedCalibrator()
        repeat(5) { calibrator.add(sampleWithKnee(it * 50L, 95.0)) }
        repeat(40) { calibrator.add(SitToStandSample.invalid(1_000L + it, SitToStandIssue.NO_POSE)) }
        assertEquals(CalibrationOutcome.Failed(CalibrationIssue.TOO_FEW_VALID_FRAMES), calibrator.result())
    }

    private fun sampleWithKnee(t: Long, knee: Double) = SitToStandSample(
        timestampMs = t,
        issue = null,
        side = SitToStandSide.RIGHT,
        visibility = 0.9,
        kneeAngleDeg = knee,
        hipYPx = 330.0,
        shinPx = 180.0,
        trunkLeanDeg = 2.0,
    )

    @Test
    fun uprightLandmarksAreUnchangedAtZeroDegrees() {
        val p = listOf(PosePoint(0.2, 0.7, 0.9))
        assertEquals(p, PoseOrientation.toUpright(p, 0))
    }

    @Test
    fun rotationsMapLandmarksLikeTheHandPipeline() {
        val p = listOf(PosePoint(0.2, 0.7, 0.9))
        assertEquals(PosePoint(1.0 - 0.7, 0.2, 0.9), PoseOrientation.toUpright(p, 90).single())
        assertEquals(PosePoint(1.0 - 0.2, 1.0 - 0.7, 0.9), PoseOrientation.toUpright(p, 180).single())
        assertEquals(PosePoint(0.7, 1.0 - 0.2, 0.9), PoseOrientation.toUpright(p, 270).single())
    }

    @Test
    fun missingLandmarksStayMissingAfterRotation() {
        assertNull(PoseOrientation.toUpright(listOf(null), 90).single())
    }

    @Test
    fun aNonRightAngleRotationIsRejected() {
        try {
            PoseOrientation.toUpright(listOf(PosePoint(0.1, 0.1, 1.0)), 45)
            fail("expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message.orEmpty().contains("45"))
        }
    }
}
