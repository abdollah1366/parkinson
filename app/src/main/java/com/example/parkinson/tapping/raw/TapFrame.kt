package com.example.parkinson.tapping.raw

import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.HandSide
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.mediapipe.HandTrackingResult
import kotlin.math.hypot

/** Why a frame can or cannot be used for the tapping signal (landmark availability only). */
enum class FrameStatus {
    VALID,
    NO_HAND,
    MULTIPLE_HANDS,

    /** Thumb or index tip outside the image, or landmarks not finite / degenerate. */
    OUT_OF_FRAME,
    ERROR
}

/**
 * RAW DATA layer: one MediaPipe result reduced to what the tapping analysis needs.
 * Distances are in pixels of the upright analysis image; nothing is normalized yet.
 *
 * Handedness is NOT part of the validity: a frame with landmarks is VALID whatever the per-frame
 * left/right label says; [sideStatus] is evaluated over the whole recording.
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
    /** MediaPipe handedness score (left/right certainty), 0 without a hand. */
    val confidence: Double = 0.0,
    val handSide: HandSide? = null,
    val sideStatus: HandSideStatus? = null,
    /** Mean luma of the camera frame (0..255), null when unknown. */
    val meanLuma: Float? = null,
    /** Analysis sequence number, null when unknown. */
    val sequence: Long? = null,
    /** Camera frames dropped before this one. */
    val cameraFramesSkipped: Int = 0
) {
    val isValid: Boolean get() = status == FrameStatus.VALID
}

object TapFrameExtractor {

    /** Normalized coordinates may be slightly outside 0..1 for a point at the image edge. */
    private const val EDGE_MARGIN = 0.02f

    fun extract(index: Int, result: HandTrackingResult): TapFrame {
        val ts = result.timestampMs
        val info = result.frameInfo
        val base = TapFrame(
            index, ts, FrameStatus.NO_HAND,
            meanLuma = info?.meanLuma, sequence = info?.sequence, cameraFramesSkipped = info?.cameraFramesSkipped ?: 0
        )
        return when (result) {
            is HandTrackingResult.HandDetected -> fromHand(base, result)
            is HandTrackingResult.NoHandDetected,
            is HandTrackingResult.TrackingLost -> base
            is HandTrackingResult.MultipleHandsDetected -> base.copy(status = FrameStatus.MULTIPLE_HANDS)
            is HandTrackingResult.Error -> base.copy(status = FrameStatus.ERROR)
        }
    }

    private fun fromHand(base: TapFrame, hand: HandTrackingResult.HandDetected): TapFrame {
        val lm = hand.landmarks
        val w = hand.imageWidth.toDouble()
        val h = hand.imageHeight.toDouble()
        val withHand = base.copy(confidence = hand.confidence.toDouble(), handSide = hand.handSide, sideStatus = hand.sideStatus)
        val outOfFrame = withHand.copy(status = FrameStatus.OUT_OF_FRAME)
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

        return withHand.copy(
            status = FrameStatus.VALID,
            thumbIndexDistancePx = dist(thumb, indexTip),
            handScalePx = handScale
        )
    }

    private fun inImage(p: HandLandmark): Boolean =
        p.x >= -EDGE_MARGIN && p.x <= 1f + EDGE_MARGIN && p.y >= -EDGE_MARGIN && p.y <= 1f + EDGE_MARGIN
}
