package com.example.parkinson.gait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** SYNTHETIC body landmarks for unit tests only. Image is 480 x 640 px (upright). */
class CameraPoseFeatureExtractorTest {

    private val w = 480
    private val h = 640
    private val extractor = CameraPoseFeatureExtractor()

    /** Upright standing body; [lean] shifts the shoulders horizontally (normalized x). */
    private fun body(lean: Double = 0.0, visibility: Double = 0.95): MutableList<PosePoint?> {
        val p = MutableList<PosePoint?>(PoseLandmarkIndex.COUNT) { null }
        fun set(i: Int, x: Double, y: Double) { p[i] = PosePoint(x, y, visibility) }
        set(PoseLandmarkIndex.LEFT_SHOULDER, 0.40 + lean, 0.30)
        set(PoseLandmarkIndex.RIGHT_SHOULDER, 0.60 + lean, 0.30)
        set(PoseLandmarkIndex.LEFT_HIP, 0.42, 0.50)
        set(PoseLandmarkIndex.RIGHT_HIP, 0.58, 0.50)
        set(PoseLandmarkIndex.LEFT_ANKLE, 0.42, 0.90)
        set(PoseLandmarkIndex.RIGHT_ANKLE, 0.58, 0.90)
        set(PoseLandmarkIndex.LEFT_WRIST, 0.30, 0.55)
        set(PoseLandmarkIndex.RIGHT_WRIST, 0.70, 0.55)
        return p
    }

    private fun extract(points: List<PosePoint?>, t: Long = 0L) = extractor.extract(t, points, w, h)

    @Test
    fun uprightBodyGivesNearZeroTrunkInclinationAndExpectedTorsoLength() {
        val frame = extract(body())
        assertEquals(PoseFrameStatus.VALID, frame.status)
        val f = requireNotNull(frame.features)
        // Torso = (0.50 - 0.30) x 640 px = 128 px.
        assertEquals(128.0, f.torsoLengthPx, 1e-9)
        assertEquals(0.0, f.trunkInclinationDeg, 1e-9)
        assertEquals(0.0, f.shoulderTiltDeg, 1e-9)
        // Ankles: (0.90 - 0.50) x 640 / 128 = 2.0 torso lengths below the mid-hip.
        assertEquals(2.0, f.leftAnkleHeight, 1e-9)
        assertEquals(2.0, f.rightAnkleHeight, 1e-9)
    }

    @Test
    fun leaningTrunkGivesTheImageAngleFromVertical() {
        // Shoulders shifted by +0.05 (24 px) relative to the hips: atan(24 / 128) = 10.62 degrees.
        val f = requireNotNull(extract(body(lean = 0.05)).features)
        assertEquals(Math.toDegrees(Math.atan2(24.0, 128.0)), f.trunkInclinationDeg, 1e-6)
    }

    @Test
    fun wristOffsetsAreInTorsoLengthsFromTheShoulder() {
        val f = requireNotNull(extract(body()).features)
        // Left wrist x = 0.30 x 480 = 144 px; mid-shoulder x = 0.50 x 480 = 240 px; torso 128 px.
        assertEquals((144.0 - 240.0) / 128.0, requireNotNull(f.leftWristOffset), 1e-9)
        assertEquals((336.0 - 240.0) / 128.0, requireNotNull(f.rightWristOffset), 1e-9)
    }

    @Test
    fun missingWristKeepsTheFrameValidAndBlanksOnlyThatArm() {
        val p = body()
        p[PoseLandmarkIndex.LEFT_WRIST] = PosePoint(0.30, 0.55, 0.1)
        val frame = extract(p)
        assertEquals(PoseFrameStatus.VALID, frame.status)
        assertNull(frame.features!!.leftWristOffset)
        requireNotNull(frame.features!!.rightWristOffset)
    }

    @Test
    fun lowVisibilityAnkleMakesTheFrameIncomplete() {
        val p = body()
        p[PoseLandmarkIndex.RIGHT_ANKLE] = PosePoint(0.58, 0.90, 0.2)
        val frame = extract(p)
        assertEquals(PoseFrameStatus.INCOMPLETE, frame.status)
        assertNull(frame.features)
    }

    @Test
    fun feetOutsideTheImageMakeTheFrameIncomplete() {
        val p = body()
        p[PoseLandmarkIndex.LEFT_ANKLE] = PosePoint(0.42, 1.2, 0.95)
        assertEquals(PoseFrameStatus.INCOMPLETE, extract(p).status)
    }

    @Test
    fun noPersonIsNoPose() {
        val empty = MutableList<PosePoint?>(PoseLandmarkIndex.COUNT) { null }
        assertEquals(PoseFrameStatus.NO_POSE, extract(empty).status)
    }

    @Test
    fun smallBodyInTheImageIsTooSmall() {
        // Torso of 0.02 x 640 = 12.8 px: the subject is too far away for a stable measurement.
        val p = body()
        p[PoseLandmarkIndex.LEFT_HIP] = PosePoint(0.42, 0.32, 0.95)
        p[PoseLandmarkIndex.RIGHT_HIP] = PosePoint(0.58, 0.32, 0.95)
        assertEquals(PoseFrameStatus.TOO_SMALL, extract(p).status)
    }

    @Test
    fun wrongLandmarkCountIsAnError() {
        assertEquals(PoseFrameStatus.ERROR, extractor.extract(0L, listOf(null), w, h).status)
    }

    @Test
    fun timestampIsCarriedThrough() {
        assertEquals(1234L, extract(body(), t = 1234L).timestampMs)
    }

    @Test
    fun seriesQualityCountsValidFramesAndTheLongestGap() {
        // 10 frames at 100 ms; frames 3..6 invalid (a 400 ms gap from the last valid frame to the next).
        val frames = (0 until 10).map { i ->
            val valid = i !in 3..6
            PoseFrame(i * 100L, if (valid) PoseFrameStatus.VALID else PoseFrameStatus.NO_POSE, if (valid) extract(body()).features else null)
        }
        val q = PoseSeriesQuality.of(frames)
        assertEquals(10, q.frameCount)
        assertEquals(6, q.validFrames)
        assertEquals(60.0, q.validFramePercent, 1e-9)
        assertEquals(300L, q.longestGapMs)
        assertEquals(10.0, q.frameRateHz, 1e-9)
        assertEquals(4, q.statusCounts[PoseFrameStatus.NO_POSE])
        assertTrue(abs(q.validFramePercent - 60.0) < 1e-9)
    }

    @Test
    fun emptySeriesHasNoFramesAndNoRate() {
        val q = PoseSeriesQuality.of(emptyList())
        assertEquals(0, q.frameCount)
        assertEquals(0.0, q.validFramePercent, 1e-9)
        assertEquals(0.0, q.frameRateHz, 1e-9)
    }
}
