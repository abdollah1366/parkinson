package com.example.parkinson.mediapipe

import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.raw.FrameStatus
import com.example.parkinson.tapping.raw.TapFrameExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HandednessMapperTest {

    private val camera = HandednessMapper.CAMERAX_ANALYSIS

    @Test
    fun cameraXAnalysisFramesAreUnmirrored() {
        assertEquals(FrameMirroring.NONE, camera)
    }

    @Test
    fun realLeftHandIsReportedAsLeft() {
        // MediaPipe labels the hand as it appears in the unmirrored frame = the physical hand.
        assertEquals(HandSide.LEFT, HandednessMapper.toPhysicalHand("Left", camera))
    }

    @Test
    fun realRightHandIsReportedAsRight() {
        assertEquals(HandSide.RIGHT, HandednessMapper.toPhysicalHand("Right", camera))
    }

    @Test
    fun mirroredFramesInvertTheLabel() {
        assertEquals(HandSide.RIGHT, HandednessMapper.toPhysicalHand("Left", FrameMirroring.HORIZONTAL))
        assertEquals(HandSide.LEFT, HandednessMapper.toPhysicalHand("Right", FrameMirroring.HORIZONTAL))
    }

    @Test
    fun labelParsingIsCaseAndWhitespaceInsensitive() {
        assertEquals(HandSide.LEFT, HandednessMapper.toPhysicalHand(" left ", camera))
        assertEquals(HandSide.RIGHT, HandednessMapper.toPhysicalHand("RIGHT", camera))
    }

    @Test
    fun unknownLabelsAreNotGuessed() {
        assertNull(HandednessMapper.toPhysicalHand(null, camera))
        assertNull(HandednessMapper.toPhysicalHand("", camera))
        assertNull(HandednessMapper.toPhysicalHand("Unknown", camera))
    }

    @Test
    fun selectedHandMapsToTheSamePhysicalSide() {
        assertEquals(HandSide.LEFT, SelectedHand.LEFT.toHandSide())
        assertEquals(HandSide.RIGHT, SelectedHand.RIGHT.toHandSide())
    }

    @Test
    fun selectingLeftAndShowingLeftIsAccepted() {
        val d = HandednessMapper.decide("Left", 0.95f, camera, SelectedHand.LEFT.toHandSide(), 0.6f)
        assertEquals(HandednessMapper.Decision.Accepted(HandSide.LEFT), d)
    }

    @Test
    fun selectingRightAndShowingRightIsAccepted() {
        val d = HandednessMapper.decide("Right", 0.95f, camera, SelectedHand.RIGHT.toHandSide(), 0.6f)
        assertEquals(HandednessMapper.Decision.Accepted(HandSide.RIGHT), d)
    }

    @Test
    fun showingTheOtherHandIsWrongHand() {
        val d = HandednessMapper.decide("Right", 0.95f, camera, HandSide.LEFT, 0.6f)
        assertEquals(HandednessMapper.Decision.WrongHand(expected = HandSide.LEFT, detected = HandSide.RIGHT), d)
    }

    @Test
    fun lowScoreOrUnknownLabelIsLowConfidence() {
        assertEquals(HandednessMapper.Decision.LowConfidence, HandednessMapper.decide("Left", 0.4f, camera, HandSide.LEFT, 0.6f))
        assertEquals(HandednessMapper.Decision.LowConfidence, HandednessMapper.decide(null, 0.9f, camera, HandSide.LEFT, 0.6f))
    }

    @Test
    fun noExpectedHandAcceptsEither() {
        assertEquals(HandednessMapper.Decision.Accepted(HandSide.LEFT), HandednessMapper.decide("Left", 0.9f, camera, null, 0.6f))
        assertEquals(HandednessMapper.Decision.Accepted(HandSide.RIGHT), HandednessMapper.decide("Right", 0.9f, camera, null, 0.6f))
    }

    @Test
    fun displayMirroringMovesOverlayPointsButNotTheHand() {
        val landmark = HandLandmark(HandLandmarkIndex.THUMB_TIP, x = 0.25f, y = 0.5f, z = 0f)
        val plain = HandLandmarkMapper.mapToView(landmark, 100, 100, 100f, 100f, mirror = false)
        val mirrored = HandLandmarkMapper.mapToView(landmark, 100, 100, 100f, 100f, mirror = true)
        assertEquals(25f, plain.x, 1e-4f)
        assertEquals(75f, mirrored.x, 1e-4f)
        assertEquals(plain.y, mirrored.y, 1e-4f)
        // The label mapping has no lens/preview input at all: the same label gives the same hand.
        assertEquals(HandSide.LEFT, HandednessMapper.toPhysicalHand("Left", camera))
    }

    @Test
    fun physicalSideFlowsUnchangedIntoTheTappingEngine() {
        // Spread points so the palm has a real size (a degenerate hand is rejected as out of frame).
        val landmarks = (0 until HandLandmarkIndex.COUNT).map { HandLandmark(it, 0.3f + 0.02f * it, 0.7f - 0.015f * it, 0f) }
        val detected = HandTrackingResult.HandDetected(1L, landmarks, HandSide.LEFT, 0.9f, 480, 640)
        assertEquals(HandSide.LEFT, TapFrameExtractor.extract(0, detected).handSide)

        // A confident label of the other hand keeps the landmarks (VALID); the side is judged over
        // the whole recording by the quality engine.
        val wrong = HandTrackingResult.HandDetected(2L, landmarks, HandSide.RIGHT, 0.9f, 480, 640, HandSideStatus.MISMATCH)
        val frame = TapFrameExtractor.extract(1, wrong)
        assertEquals(FrameStatus.VALID, frame.status)
        assertEquals(HandSide.RIGHT, frame.handSide)
        assertEquals(HandSideStatus.MISMATCH, frame.sideStatus)
    }
}
