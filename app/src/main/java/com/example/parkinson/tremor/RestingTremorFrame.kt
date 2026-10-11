package com.example.parkinson.tremor

import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.tapping.raw.FrameStatus
import kotlin.math.hypot

/**
 * One MediaPipe result reduced to the resting-hand features. Positions are in pixels of the upright
 * image; [palmXPx]/[palmYPx] and [handScalePx] are NaN unless [status] is VALID. [timestampMs] is the
 * time the frame was handed to MediaPipe, on the same monotonic clock as the session window.
 */
data class RestingTremorFrame(
    val index: Int,
    val timestampMs: Long,
    val status: FrameStatus,
    /** Palm centre (see [RestingTremorFrameExtractor]), upright-image pixels. */
    val palmXPx: Double = Double.NaN,
    val palmYPx: Double = Double.NaN,
    /** Hand size reference: distance wrist -> middle-finger knuckle, in pixels. */
    val handScalePx: Double = Double.NaN,
    /** MediaPipe's handedness label disagrees with the selected hand (landmarks are still kept). */
    val wrongHand: Boolean = false,
) {
    val isValid: Boolean get() = status == FrameStatus.VALID
}

/**
 * RAW DATA layer of the resting-hand test. Every landmark used must be present, finite and inside the
 * image (with a small margin); otherwise the frame is OUT_OF_FRAME and gives no position.
 *
 * Feature: the palm centre is the mean of the wrist and the four finger knuckles (MCP joints of index,
 * middle, ring, little finger). Fingertips and the thumb are not used: they move most when the fingers
 * are relaxed or curled, which is not the signal of the hand as a whole. The knuckle-and-wrist mean is
 * the most stable part of the hand in the image. The hand size is the wrist-to-middle-knuckle distance
 * in pixels, so the aspect ratio is respected. Hand size is only used to convert pixels to hand lengths
 * (see [RestingTremorEngine]); it is not a measure of the person.
 */
object RestingTremorFrameExtractor {

    private const val EDGE_MARGIN = 0.02f

    /** Wrist and the four finger knuckles. */
    private val PALM_POINTS = intArrayOf(
        HandLandmarkIndex.WRIST,
        HandLandmarkIndex.INDEX_FINGER_MCP,
        HandLandmarkIndex.MIDDLE_FINGER_MCP,
        HandLandmarkIndex.RING_FINGER_MCP,
        HandLandmarkIndex.PINKY_MCP
    )

    /** A hand smaller than this in pixels cannot give a stable reference: the frame is not usable. */
    private const val MIN_HAND_SCALE_PX = 8.0

    fun extract(index: Int, result: HandTrackingResult): RestingTremorFrame {
        val base = RestingTremorFrame(index = index, timestampMs = result.timestampMs, status = FrameStatus.NO_HAND)
        return when (result) {
            is HandTrackingResult.HandDetected -> fromHand(base, result.landmarks, result.imageWidth, result.imageHeight, result.sideStatus)
            is HandTrackingResult.NoHandDetected,
            is HandTrackingResult.TrackingLost -> base
            is HandTrackingResult.MultipleHandsDetected -> base.copy(status = FrameStatus.MULTIPLE_HANDS)
            is HandTrackingResult.Error -> base.copy(status = FrameStatus.ERROR)
        }
    }

    private fun fromHand(
        base: RestingTremorFrame,
        lm: List<HandLandmark>,
        width: Int,
        height: Int,
        sideStatus: HandSideStatus,
    ): RestingTremorFrame {
        val wrong = sideStatus == HandSideStatus.MISMATCH
        val outOfFrame = base.copy(status = FrameStatus.OUT_OF_FRAME, wrongHand = wrong)
        val w = width.toDouble()
        val h = height.toDouble()
        if (lm.size < HandLandmarkIndex.COUNT || w <= 0.0 || h <= 0.0) return outOfFrame
        if (lm.any { !it.x.isFinite() || !it.y.isFinite() }) return outOfFrame
        if (PALM_POINTS.any { !inImage(lm[it]) }) return outOfFrame

        val wrist = lm[HandLandmarkIndex.WRIST]
        val middle = lm[HandLandmarkIndex.MIDDLE_FINGER_MCP]
        val scale = hypot((middle.x - wrist.x) * w, (middle.y - wrist.y) * h)
        if (!scale.isFinite() || scale < MIN_HAND_SCALE_PX) return outOfFrame

        var sumX = 0.0
        var sumY = 0.0
        for (i in PALM_POINTS) {
            sumX += lm[i].x * w
            sumY += lm[i].y * h
        }
        return base.copy(
            status = FrameStatus.VALID,
            palmXPx = sumX / PALM_POINTS.size,
            palmYPx = sumY / PALM_POINTS.size,
            handScalePx = scale,
            wrongHand = wrong
        )
    }

    private fun inImage(p: HandLandmark): Boolean =
        p.x >= -EDGE_MARGIN && p.x <= 1f + EDGE_MARGIN && p.y >= -EDGE_MARGIN && p.y <= 1f + EDGE_MARGIN
}
