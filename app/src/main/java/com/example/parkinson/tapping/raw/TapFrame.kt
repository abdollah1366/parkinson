package com.example.parkinson.tapping.raw

import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.HandSide
import com.example.parkinson.mediapipe.HandTrackingResult
import kotlin.math.hypot

/** Why a frame can or cannot be used for the tapping signal. */
enum class FrameStatus {
    VALID,
    NO_HAND,
    WRONG_HAND,
    MULTIPLE_HANDS,
    LOW_CONFIDENCE,

    /** Thumb or index tip outside the image, or landmarks not finite. */
    OUT_OF_FRAME,
    ERROR
}

/**
 * RAW DATA layer: one MediaPipe result reduced to what the tapping analysis needs.
 * Distances are in pixels of the upright analysis image; nothing is normalized yet.
 *
 * @param index position of this frame in the recording (0-based, every result counts)
 * @param timestampMs SystemClock.uptimeMillis() of the camera frame
 * @param thumbIndexDistancePx distance between thumb tip (4) and index tip (8), NaN if not VALID
 * @param handScalePx palm size reference (see [TapFrameExtractor]), NaN if not VALID
 */
data class TapFrame(
    val index: Int,
    val timestampMs: Long,
    val status: FrameStatus,
    val thumbIndexDistancePx: Double = Double.NaN,
    val handScalePx: Double = Double.NaN,
    val confidence: Double = 0.0,
    val handSide: HandSide? = null
) {
    val isValid: Boolean get() = status == FrameStatus.VALID
}

object TapFrameExtractor {

    /** Normalized coordinates may be slightly outside 0..1 for a point at the image edge. */
    private const val EDGE_MARGIN = 0.02f

    fun extract(index: Int, result: HandTrackingResult): TapFrame {
        val ts = result.timestampMs
        return when (result) {
            is HandTrackingResult.HandDetected -> fromHand(index, result)
            is HandTrackingResult.NoHandDetected,
            is HandTrackingResult.TrackingLost -> TapFrame(index, ts, FrameStatus.NO_HAND)

            is HandTrackingResult.WrongHandDetected ->
                TapFrame(index, ts, FrameStatus.WRONG_HAND, handSide = result.detected)

            is HandTrackingResult.MultipleHandsDetected -> TapFrame(index, ts, FrameStatus.MULTIPLE_HANDS)
            is HandTrackingResult.LowConfidence ->
                TapFrame(index, ts, FrameStatus.LOW_CONFIDENCE, confidence = result.confidence.toDouble())

            is HandTrackingResult.Error -> TapFrame(index, ts, FrameStatus.ERROR)
        }
    }

    private fun fromHand(index: Int, hand: HandTrackingResult.HandDetected): TapFrame {
        val ts = hand.timestampMs
        val lm = hand.landmarks
        val w = hand.imageWidth.toDouble()
        val h = hand.imageHeight.toDouble()
        val outOfFrame = TapFrame(index, ts, FrameStatus.OUT_OF_FRAME, confidence = hand.confidence.toDouble())
        if (lm.size < HandLandmarkIndex.COUNT || w <= 0.0 || h <= 0.0) return outOfFrame
        if (lm.any { !it.x.isFinite() || !it.y.isFinite() }) return outOfFrame

        val thumb = lm[HandLandmarkIndex.THUMB_TIP]
        val indexTip = lm[HandLandmarkIndex.INDEX_FINGER_TIP]
        if (!inImage(thumb) || !inImage(indexTip)) return outOfFrame

        fun dist(a: HandLandmark, b: HandLandmark) = hypot((a.x - b.x) * w, (a.y - b.y) * h)

        // Palm size: mean of four palm segments. Steadier than one segment when the hand rotates.
        val wrist = lm[HandLandmarkIndex.WRIST]
        val indexMcp = lm[HandLandmarkIndex.INDEX_FINGER_MCP]
        val middleMcp = lm[HandLandmarkIndex.MIDDLE_FINGER_MCP]
        val pinkyMcp = lm[HandLandmarkIndex.PINKY_MCP]
        val handScale = (
            dist(wrist, indexMcp) + dist(wrist, middleMcp) + dist(wrist, pinkyMcp) + dist(indexMcp, pinkyMcp)
            ) / 4.0
        if (handScale < 1.0) return outOfFrame

        return TapFrame(
            index = index,
            timestampMs = ts,
            status = FrameStatus.VALID,
            thumbIndexDistancePx = dist(thumb, indexTip),
            handScalePx = handScale,
            confidence = hand.confidence.toDouble(),
            handSide = hand.handSide
        )
    }

    private fun inImage(p: HandLandmark): Boolean =
        p.x >= -EDGE_MARGIN && p.x <= 1f + EDGE_MARGIN && p.y >= -EDGE_MARGIN && p.y <= 1f + EDGE_MARGIN
}
