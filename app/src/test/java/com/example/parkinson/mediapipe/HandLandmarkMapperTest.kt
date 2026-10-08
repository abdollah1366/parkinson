package com.example.parkinson.mediapipe

import androidx.compose.ui.geometry.Offset
import com.example.parkinson.tapping.raw.TapFrameExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.sign

/**
 * Coordinate chain: MediaPipe (normalized to the unrotated sensor-frame bitmap)
 * -> HandLandmarkMapper.toUpright (ImageInfo.rotationDegrees, clockwise)
 * -> HandLandmarkMapper.mapToView (FILL_CENTER, front camera mirrors x).
 */
class HandLandmarkMapperTest {

    private val idx = HandLandmarkIndex

    /** An upright hand, fingers pointing up (wrist at the bottom), as seen in the upright image. */
    private fun uprightHand(side: HandSide): List<HandLandmark> {
        val lm = MutableList(idx.COUNT) { HandLandmark(it, 0.5f, 0.5f, 0f) }
        // Thumb on the image-left for a right palm facing the camera, image-right for a left one.
        val s = if (side == HandSide.RIGHT) -1f else 1f
        fun put(i: Int, dx: Float, y: Float) { lm[i] = HandLandmark(i, 0.5f + s * dx, y, 0f) }
        put(idx.WRIST, 0f, 0.85f)
        put(idx.THUMB_CMC, 0.06f, 0.78f); put(idx.THUMB_MCP, 0.11f, 0.70f)
        put(idx.THUMB_IP, 0.15f, 0.63f); put(idx.THUMB_TIP, 0.18f, 0.57f)
        put(idx.INDEX_FINGER_MCP, 0.07f, 0.60f); put(idx.INDEX_FINGER_PIP, 0.08f, 0.48f)
        put(idx.INDEX_FINGER_DIP, 0.085f, 0.40f); put(idx.INDEX_FINGER_TIP, 0.09f, 0.33f)
        put(idx.MIDDLE_FINGER_MCP, 0.02f, 0.58f); put(idx.MIDDLE_FINGER_PIP, 0.02f, 0.45f)
        put(idx.MIDDLE_FINGER_DIP, 0.02f, 0.36f); put(idx.MIDDLE_FINGER_TIP, 0.02f, 0.28f)
        put(idx.RING_FINGER_MCP, -0.03f, 0.59f); put(idx.RING_FINGER_PIP, -0.035f, 0.47f)
        put(idx.RING_FINGER_DIP, -0.04f, 0.39f); put(idx.RING_FINGER_TIP, -0.045f, 0.32f)
        put(idx.PINKY_MCP, -0.08f, 0.62f); put(idx.PINKY_PIP, -0.09f, 0.53f)
        put(idx.PINKY_DIP, -0.10f, 0.47f); put(idx.PINKY_TIP, -0.105f, 0.42f)
        return lm
    }

    /** What MediaPipe returns for that hand: the same points in the unrotated sensor frame. */
    private fun sensorFrame(upright: List<HandLandmark>, rotationDegrees: Int) =
        HandLandmarkMapper.toUpright(upright, (360 - rotationDegrees) % 360)

    private fun assertSame(expected: List<HandLandmark>, actual: List<HandLandmark>) {
        expected.zip(actual).forEach { (e, a) ->
            assertEquals("x of ${e.index}", e.x, a.x, 1e-5f)
            assertEquals("y of ${e.index}", e.y, a.y, 1e-5f)
            assertEquals(e.index, a.index)
            assertEquals(e.z, a.z, 0f)
        }
    }

    /** Sign of the 2D cross product wrist->index MCP x wrist->pinky MCP: encodes chirality. */
    private fun chirality(p: List<Offset>): Float {
        val w = p[idx.WRIST]; val i = p[idx.INDEX_FINGER_MCP]; val k = p[idx.PINKY_MCP]
        return sign((i.x - w.x) * (k.y - w.y) - (i.y - w.y) * (k.x - w.x))
    }

    private fun toOffsets(lm: List<HandLandmark>) = lm.map { Offset(it.x, it.y) }

    @Test
    fun rotationFormulasMatchCameraXClockwiseRotation() {
        // Sensor frame W x H; top-left (0,0) and top-right (1,0) after a clockwise rotation.
        val tl = HandLandmark(0, 0f, 0f, 0f)
        val tr = HandLandmark(1, 1f, 0f, 0f)
        fun at(r: Int) = HandLandmarkMapper.toUpright(listOf(tl, tr), r).map { it.x to it.y }
        assertEquals(listOf(0f to 0f, 1f to 0f), at(0))
        assertEquals(listOf(1f to 0f, 1f to 1f), at(90))
        assertEquals(listOf(1f to 1f, 0f to 1f), at(180))
        assertEquals(listOf(0f to 1f, 0f to 0f), at(270))
        assertEquals(at(270), at(-90))
    }

    @Test
    fun recoversUprightHandForEveryRotationAndBothHands() {
        for (side in HandSide.entries) for (r in listOf(0, 90, 180, 270)) {
            val upright = uprightHand(side)
            assertSame(upright, HandLandmarkMapper.toUpright(sensorFrame(upright, r), r))
        }
    }

    @Test
    fun fingertipsAppearAboveWristOnScreenForBothLensesAndHands() {
        // Typical portrait CameraX values: front lens 270, back lens 90; 640x480 sensor frame.
        for (side in HandSide.entries) for (r in listOf(90, 270)) for (mirror in listOf(true, false)) {
            val upright = HandLandmarkMapper.toUpright(sensorFrame(uprightHand(side), r), r)
            val view = upright.map {
                HandLandmarkMapper.mapToView(it, 480, 640, 1080f, 2340f, mirror)
            }
            val wrist = view[idx.WRIST]
            for (tip in listOf(idx.THUMB_TIP, idx.INDEX_FINGER_TIP, idx.MIDDLE_FINGER_TIP, idx.RING_FINGER_TIP, idx.PINKY_TIP)) {
                assertTrue("tip $tip above wrist ($side, r=$r, mirror=$mirror)", view[tip].y < wrist.y)
            }
            // Middle fingertip is the topmost point, wrist the bottom-most.
            assertEquals(idx.MIDDLE_FINGER_TIP, view.indices.minBy { view[it].y })
            assertEquals(idx.WRIST, view.indices.maxBy { view[it].y })
        }
    }

    @Test
    fun withoutRotationTheSkeletonIsNotUpright() {
        // Regression for the device bug: drawing sensor-frame points as if upright.
        for (r in listOf(90, 180, 270)) {
            val raw = sensorFrame(uprightHand(HandSide.RIGHT), r)
            val wristBelowAllTips = listOf(idx.INDEX_FINGER_TIP, idx.MIDDLE_FINGER_TIP, idx.PINKY_TIP)
                .all { raw[it].y < raw[idx.WRIST].y }
            assertTrue("r=$r", !wristBelowAllTips)
        }
    }

    @Test
    fun rotationPreservesChiralityOnlyMirrorFlipsIt() {
        for (side in HandSide.entries) {
            val upright = uprightHand(side)
            val reference = chirality(toOffsets(upright))
            for (r in listOf(0, 90, 180, 270)) {
                val sensor = sensorFrame(upright, r)
                assertEquals(reference, chirality(toOffsets(sensor)))
                assertEquals(reference, chirality(toOffsets(HandLandmarkMapper.toUpright(sensor, r))))
            }
            val mirrored = upright.map { HandLandmarkMapper.mapToView(it, 480, 640, 480f, 640f, mirror = true) }
            assertEquals(-reference, chirality(mirrored))
        }
        // Left and right hands have opposite chirality.
        assertEquals(-chirality(toOffsets(uprightHand(HandSide.LEFT))), chirality(toOffsets(uprightHand(HandSide.RIGHT))))
    }

    @Test
    fun frontCameraMirrorsOnlyX() {
        val p = HandLandmark(0, 0.25f, 0.75f, 0f)
        val plain = HandLandmarkMapper.mapToView(p, 480, 640, 480f, 640f, mirror = false)
        val mirrored = HandLandmarkMapper.mapToView(p, 480, 640, 480f, 640f, mirror = true)
        assertEquals(Offset(120f, 480f), plain)
        assertEquals(Offset(360f, 480f), mirrored)
    }

    @Test
    fun tappingDistancesEqualTrueImageDistancesAfterRotation() {
        // Sensor bitmap 640x480, rotation 270 -> upright 480x640 (as HandLandmarkerManager reports).
        val sensorW = 640.0; val sensorH = 480.0
        for (side in HandSide.entries) for (r in listOf(0, 90, 180, 270)) {
            val sensor = sensorFrame(uprightHand(side), r)
            val swapped = r == 90 || r == 270
            val upright = HandLandmarkMapper.toUpright(sensor, r)
            val hand = HandTrackingResult.HandDetected(
                1_000L, upright, side, 0.9f,
                imageWidth = if (swapped) 480 else 640,
                imageHeight = if (swapped) 640 else 480
            )
            val frame = TapFrameExtractor.extract(0, hand)
            assertTrue(frame.isValid)
            val t = sensor[idx.THUMB_TIP]; val i = sensor[idx.INDEX_FINGER_TIP]
            val expected = hypot((t.x - i.x) * sensorW, (t.y - i.y) * sensorH)
            assertEquals("side=$side r=$r", expected, frame.thumbIndexDistancePx, 1e-3)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonRightAngleRotation() {
        HandLandmarkMapper.toUpright(uprightHand(HandSide.RIGHT), 45)
    }
}
