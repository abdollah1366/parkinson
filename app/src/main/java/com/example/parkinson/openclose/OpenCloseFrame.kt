package com.example.parkinson.openclose

import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.tapping.raw.FrameStatus
import kotlin.math.hypot

/**
 * One MediaPipe result reduced to the hand-opening feature. [opening] is NaN unless [status] is
 * VALID. [timestampMs] is the time the frame was handed to MediaPipe, on the same monotonic clock
 * as the session window (see HandLandmarkerManager.nextTimestamp).
 */
data class OpenCloseFrame(
    val index: Int,
    val timestampMs: Long,
    val status: FrameStatus,
    /**
     * Mean finger extension of index..pinky, 0..about 1: straight-line MCP-to-tip distance divided by
     * the finger's own bone-chain length. 1 = straight finger, lower = curled. Independent of finger
     * and palm length (see [OpenCloseFrameExtractor]). Unitless.
     */
    val opening: Double = Double.NaN,
    val meanLuma: Float? = null,
    val sideStatus: HandSideStatus? = null,
    val sequence: Long? = null,
    val cameraFramesSkipped: Int = 0
) {
    val isValid: Boolean get() = status == FrameStatus.VALID
}

/**
 * RAW DATA layer for the hand-opening test. Every landmark used must be present, finite and inside
 * the image (with a small margin), otherwise the frame is OUT_OF_FRAME: a partly visible hand never
 * produces a feature value.
 *
 * Feature (finger extension): for each of the index, middle, ring and pinky fingers,
 *
 *     extension = |MCP -> TIP| / (|MCP -> PIP| + |PIP -> DIP| + |DIP -> TIP|)
 *
 * and the mean over the four fingers. A straight finger gives 1 whatever its length; a curled finger
 * gives less. Why not the earlier fingertip-to-wrist / palm-size feature: its open value is roughly
 * (palm length + finger length) / palm size, so it depends on finger proportions. People with short
 * fingers never reached the fixed "fully open" reference and every attempt was rejected. The chain
 * ratio removes that dependence, because each finger is compared with its own length. The thumb is not
 * used. Distances are in pixels of the upright image, so the aspect ratio is respected. Foreshortening
 * (fingers pointing at the camera) still lowers the ratio; this is a known limitation.
 */
object OpenCloseFrameExtractor {

    /** Normalized coordinates may be slightly outside 0..1 for a point at the image edge. */
    private const val EDGE_MARGIN = 0.02f

    /** Per finger: MCP, PIP, DIP, TIP (index, middle, ring, pinky). */
    private val FINGER_CHAINS = arrayOf(
        intArrayOf(5, 6, 7, 8),
        intArrayOf(9, 10, 11, 12),
        intArrayOf(13, 14, 15, 16),
        intArrayOf(17, 18, 19, 20)
    )

    /** A chain shorter than this (px) cannot give a stable ratio: the frame is not usable. */
    private const val MIN_CHAIN_PX = 4.0

    fun extract(index: Int, result: HandTrackingResult): OpenCloseFrame {
        val info = result.frameInfo
        val base = OpenCloseFrame(
            index = index,
            timestampMs = result.timestampMs,
            status = FrameStatus.NO_HAND,
            meanLuma = info?.meanLuma,
            sequence = info?.sequence,
            cameraFramesSkipped = info?.cameraFramesSkipped ?: 0
        )
        return when (result) {
            is HandTrackingResult.HandDetected -> fromHand(base, result)
            is HandTrackingResult.NoHandDetected,
            is HandTrackingResult.TrackingLost -> base
            is HandTrackingResult.MultipleHandsDetected -> base.copy(status = FrameStatus.MULTIPLE_HANDS)
            is HandTrackingResult.Error -> base.copy(status = FrameStatus.ERROR)
        }
    }

    private fun fromHand(base: OpenCloseFrame, hand: HandTrackingResult.HandDetected): OpenCloseFrame {
        val withHand = base.copy(sideStatus = hand.sideStatus)
        val outOfFrame = withHand.copy(status = FrameStatus.OUT_OF_FRAME)
        val lm = hand.landmarks
        val w = hand.imageWidth.toDouble()
        val h = hand.imageHeight.toDouble()
        if (lm.size < HandLandmarkIndex.COUNT || w <= 0.0 || h <= 0.0) return outOfFrame
        if (lm.any { !it.x.isFinite() || !it.y.isFinite() }) return outOfFrame

        val required = FINGER_CHAINS.flatMap { it.toList() } + HandLandmarkIndex.WRIST
        if (required.any { !inImage(lm[it]) }) return outOfFrame

        fun dist(a: HandLandmark, b: HandLandmark) = hypot((a.x - b.x) * w, (a.y - b.y) * h)

        var sum = 0.0
        for (chain in FINGER_CHAINS) {
            val mcp = lm[chain[0]]
            val pip = lm[chain[1]]
            val dip = lm[chain[2]]
            val tip = lm[chain[3]]
            val bones = dist(mcp, pip) + dist(pip, dip) + dist(dip, tip)
            if (!bones.isFinite() || bones < MIN_CHAIN_PX) return outOfFrame
            sum += dist(mcp, tip) / bones
        }
        val opening = sum / FINGER_CHAINS.size
        if (!opening.isFinite()) return outOfFrame
        return withHand.copy(status = FrameStatus.VALID, opening = opening)
    }

    private fun inImage(p: HandLandmark): Boolean =
        p.x >= -EDGE_MARGIN && p.x <= 1f + EDGE_MARGIN && p.y >= -EDGE_MARGIN && p.y <= 1f + EDGE_MARGIN
}
