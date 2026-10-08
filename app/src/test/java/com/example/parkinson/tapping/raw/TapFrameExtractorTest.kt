package com.example.parkinson.tapping.raw

import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.FrameInfo
import com.example.parkinson.mediapipe.HandSide
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.mediapipe.HandTrackingResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TapFrameExtractorTest {

    private fun hand(
        thumb: Pair<Float, Float> = 0.4f to 0.5f,
        index: Pair<Float, Float> = 0.6f to 0.5f,
        count: Int = HandLandmarkIndex.COUNT
    ): HandTrackingResult.HandDetected {
        val lm = MutableList(count) { HandLandmark(it, 0.5f, 0.5f, 0f) }
        if (count >= HandLandmarkIndex.COUNT) {
            lm[HandLandmarkIndex.WRIST] = HandLandmark(0, 0.5f, 0.9f, 0f)
            lm[HandLandmarkIndex.INDEX_FINGER_MCP] = HandLandmark(5, 0.4f, 0.6f, 0f)
            lm[HandLandmarkIndex.MIDDLE_FINGER_MCP] = HandLandmark(9, 0.5f, 0.6f, 0f)
            lm[HandLandmarkIndex.PINKY_MCP] = HandLandmark(17, 0.6f, 0.6f, 0f)
            lm[HandLandmarkIndex.THUMB_TIP] = HandLandmark(4, thumb.first, thumb.second, 0f)
            lm[HandLandmarkIndex.INDEX_FINGER_TIP] = HandLandmark(8, index.first, index.second, 0f)
        }
        return HandTrackingResult.HandDetected(1_000L, lm, HandSide.RIGHT, 0.9f, 200, 400)
    }

    @Test
    fun validHandGivesPixelDistanceAndPalmScale() {
        val f = TapFrameExtractor.extract(3, hand())
        assertTrue(f.isValid)
        assertEquals(3, f.index)
        assertEquals(1_000L, f.timestampMs)
        // 0.2 x 200 px wide
        assertEquals(40.0, f.thumbIndexDistancePx, 1e-3)
        assertTrue(f.handScalePx > 0)
        assertEquals(HandSide.RIGHT, f.handSide)
    }

    @Test
    fun usesLandmarks4And8() {
        val f = TapFrameExtractor.extract(0, hand(thumb = 0.5f to 0.4f, index = 0.5f to 0.5f))
        // 0.1 x 400 px high
        assertEquals(40.0, f.thumbIndexDistancePx, 1e-3)
    }

    @Test
    fun fingertipOutsideImageIsOutOfFrame() {
        val f = TapFrameExtractor.extract(0, hand(index = 1.2f to 0.5f))
        assertEquals(FrameStatus.OUT_OF_FRAME, f.status)
        assertFalse(f.isValid)
    }

    @Test
    fun incompleteLandmarksAreOutOfFrame() {
        assertEquals(FrameStatus.OUT_OF_FRAME, TapFrameExtractor.extract(0, hand(count = 10)).status)
    }

    @Test
    fun nonHandResultsMapToStatuses() {
        assertEquals(FrameStatus.NO_HAND, TapFrameExtractor.extract(0, HandTrackingResult.NoHandDetected(1)).status)
        assertEquals(FrameStatus.NO_HAND, TapFrameExtractor.extract(0, HandTrackingResult.TrackingLost(1)).status)
        assertEquals(
            FrameStatus.MULTIPLE_HANDS,
            TapFrameExtractor.extract(0, HandTrackingResult.MultipleHandsDetected(1, 2)).status
        )
        assertEquals(FrameStatus.ERROR, TapFrameExtractor.extract(0, HandTrackingResult.Error(1, "x")).status)
    }

    @Test
    fun uncertainOrMismatchedHandednessKeepsTheLandmarks() {
        // The handedness score is left/right certainty, not landmark quality: never a reason to drop data.
        for (status in HandSideStatus.entries) {
            val h = hand()
            val f = TapFrameExtractor.extract(0, h.copy(confidence = 0.3f, sideStatus = status))
            assertEquals(status.name, FrameStatus.VALID, f.status)
            assertEquals(40.0, f.thumbIndexDistancePx, 1e-3)
            assertEquals(status, f.sideStatus)
        }
    }

    @Test
    fun frameInfoIsCarriedIntoTheFrame() {
        val f = TapFrameExtractor.extract(0, hand().copy(frameInfo = FrameInfo(sequence = 7, meanLuma = 42f, cameraFramesSkipped = 2)))
        assertEquals(7L, f.sequence)
        assertEquals(42f, f.meanLuma!!, 0f)
        assertEquals(2, f.cameraFramesSkipped)
        val none = TapFrameExtractor.extract(1, HandTrackingResult.NoHandDetected(1, FrameInfo(8, 30f, 0)))
        assertEquals(FrameStatus.NO_HAND, none.status)
        assertEquals(30f, none.meanLuma!!, 0f)
    }
}
