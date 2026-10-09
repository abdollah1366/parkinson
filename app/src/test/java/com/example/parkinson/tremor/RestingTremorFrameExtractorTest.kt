package com.example.parkinson.tremor

import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandSide
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.tapping.raw.FrameStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SYNTHETIC landmarks for unit tests only. Image is 480 x 640 px (upright). */
class RestingTremorFrameExtractorTest {

    /** Hand with every landmark at the image centre, wrist-to-middle-knuckle distance set by [middleY]. */
    private fun hand(
        middleY: Float = 0.6f,
        overrides: Map<Int, Pair<Float, Float>> = emptyMap(),
        side: HandSideStatus = HandSideStatus.MATCHES,
    ): HandTrackingResult.HandDetected {
        val lm = MutableList(21) { i -> HandLandmark(i, 0.5f, 0.5f, 0f) }
        lm[9] = HandLandmark(9, 0.5f, middleY, 0f)
        overrides.forEach { (i, xy) -> lm[i] = HandLandmark(i, xy.first, xy.second, 0f) }
        return HandTrackingResult.HandDetected(
            timestampMs = 1000L,
            landmarks = lm,
            handSide = HandSide.RIGHT,
            confidence = 0.9f,
            imageWidth = 480,
            imageHeight = 640,
            sideStatus = side,
        )
    }

    private fun extract(result: HandTrackingResult) = RestingTremorFrameExtractor.extract(0, result)

    @Test
    fun validHandGivesPalmCentreInPixelsAndTheHandScale() {
        val f = extract(hand())
        assertEquals(FrameStatus.VALID, f.status)
        assertEquals(1000L, f.timestampMs)
        // Wrist-to-middle-knuckle: (0.6 - 0.5) x 640 = 64 px.
        assertEquals(64.0, f.handScalePx, 1e-2)
        // Palm = mean of wrist and four knuckles: x 0.5 x 480 = 240 px; y (4 x 0.5 + 0.6) / 5 x 640 = 332.8 px.
        assertEquals(240.0, f.palmXPx, 1e-2)
        assertEquals(332.8, f.palmYPx, 1e-2)
    }

    @Test
    fun nonFiniteLandmarkCoordinateIsOutOfFrameWithNoPosition() {
        val f = extract(hand(overrides = mapOf(0 to (Float.NaN to 0.5f))))
        assertEquals(FrameStatus.OUT_OF_FRAME, f.status)
        assertTrue(f.palmXPx.isNaN())
        assertTrue(f.handScalePx.isNaN())
    }

    @Test
    fun infiniteLandmarkCoordinateIsOutOfFrame() {
        val f = extract(hand(overrides = mapOf(5 to (Float.POSITIVE_INFINITY to 0.5f))))
        assertEquals(FrameStatus.OUT_OF_FRAME, f.status)
    }

    @Test
    fun palmLandmarkFarOutsideTheImageIsOutOfFrame() {
        val f = extract(hand(overrides = mapOf(13 to (1.2f to 0.5f))))
        assertEquals(FrameStatus.OUT_OF_FRAME, f.status)
    }

    @Test
    fun tinyHandBelowTheMinimumScaleIsOutOfFrame() {
        // 0.001 x 640 = 0.64 px: too small for a stable reference.
        val f = extract(hand(middleY = 0.501f))
        assertEquals(FrameStatus.OUT_OF_FRAME, f.status)
    }

    @Test
    fun wrongLandmarkCountIsOutOfFrame() {
        val short = hand().copy(landmarks = listOf(HandLandmark(0, 0.5f, 0.5f, 0f)))
        assertEquals(FrameStatus.OUT_OF_FRAME, extract(short).status)
    }

    @Test
    fun wrongHandKeepsTheLandmarksAndIsFlagged() {
        val f = extract(hand(side = HandSideStatus.MISMATCH))
        assertEquals(FrameStatus.VALID, f.status)
        assertTrue(f.wrongHand)
        assertFalse(f.palmXPx.isNaN())
    }

    @Test
    fun uncertainHandednessIsNotFlaggedAsWrongHand() {
        val f = extract(hand(side = HandSideStatus.UNCERTAIN))
        assertEquals(FrameStatus.VALID, f.status)
        assertFalse(f.wrongHand)
    }

    @Test
    fun noHandTrackingLostAndMultipleHandsMapToTheirStatus() {
        assertEquals(FrameStatus.NO_HAND, extract(HandTrackingResult.NoHandDetected(5L)).status)
        assertEquals(FrameStatus.NO_HAND, extract(HandTrackingResult.TrackingLost(5L)).status)
        assertEquals(FrameStatus.MULTIPLE_HANDS, extract(HandTrackingResult.MultipleHandsDetected(5L, 2)).status)
        assertEquals(FrameStatus.ERROR, extract(HandTrackingResult.Error(5L, "boom")).status)
    }

    @Test
    fun noFrameExceptValidHandHasAPosition() {
        for (r in listOf<HandTrackingResult>(
            HandTrackingResult.NoHandDetected(5L),
            HandTrackingResult.TrackingLost(5L),
            HandTrackingResult.Error(5L, "boom"),
        )) {
            val f = extract(r)
            assertTrue(f.palmXPx.isNaN() && f.palmYPx.isNaN())
        }
    }
}
