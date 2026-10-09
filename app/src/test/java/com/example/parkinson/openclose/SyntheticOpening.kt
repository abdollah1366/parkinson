package com.example.parkinson.openclose

import com.example.parkinson.mediapipe.FrameInfo
import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.HandSide
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.raw.FrameStatus
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * SYNTHETIC test input only: a smooth open/close waveform used to check the ALGORITHM. These are
 * not measurements of any person and must never be presented as results.
 *
 * Extension waveform: closed + (open - closed) * (1 + cos(2 pi t / period)) / 2, so the hand is fully
 * open at t = 0, period, 2 period, ... and closed half a period later.
 */
object SyntheticOpening {
    /** Finger extension of a fist and of a straight hand (see [SyntheticHandResult]). */
    const val CLOSED = 0.45
    const val OPEN = 1.0
    const val PERIOD_MS = 1_000L

    fun opening(t: Long, periodMs: Long = PERIOD_MS, closed: Double = CLOSED, open: Double = OPEN): Double =
        closed + (open - closed) * (1 + cos(2 * PI * t / periodMs)) / 2

    /** Valid frames every [stepMs] from [fromMs] (inclusive) to [untilMs] (inclusive). */
    fun frames(
        fromMs: Long,
        untilMs: Long,
        stepMs: Long = 33L,
        valueAt: (Long) -> Double = { opening(it) }
    ): List<OpenCloseFrame> {
        val out = ArrayList<OpenCloseFrame>()
        var t = fromMs
        while (t <= untilMs) {
            out += validFrame(out.size, t, valueAt(t))
            t += stepMs
        }
        return out
    }

    /** A valid frame whose left/right label matches the selected hand (otherwise the side is uncertain). */
    fun validFrame(index: Int, timestampMs: Long, opening: Double): OpenCloseFrame =
        OpenCloseFrame(index, timestampMs, FrameStatus.VALID, opening = opening, sideStatus = HandSideStatus.MATCHES)

    /** A recording window that starts and ends at the given times. */
    fun recording(frames: List<OpenCloseFrame>, startMs: Long, endMs: Long) =
        OpenCloseRecording(frames, startMs, endMs, plannedDurationMs = endMs - startMs, hand = SelectedHand.RIGHT)
}

/**
 * Builds a 100 x 100 px MediaPipe-like result with a hand whose finger extension is the requested
 * value. Each finger is a chain MCP -> PIP -> DIP -> TIP with bone lengths 0.45 : 0.28 : 0.22 of the
 * finger length; every joint bends by the same angle. [lengthScale] changes the finger length relative
 * to the palm, which is what differs between people: the extension must not change with it.
 */
object SyntheticHandResult {

    /** Finger length (px) at scale 1.0. The palm (wrist to middle MCP) is 30 px. */
    private const val FINGER_PX = 30.0
    private val BONES = doubleArrayOf(0.45, 0.28, 0.22)

    /** MCP positions of index, middle, ring and pinky (normalized). */
    private val MCP = arrayOf(
        doubleArrayOf(0.42, 0.62),
        doubleArrayOf(0.50, 0.60),
        doubleArrayOf(0.56, 0.62),
        doubleArrayOf(0.61, 0.66)
    )
    private val FINGER_LANDMARKS = arrayOf(
        intArrayOf(5, 6, 7, 8),
        intArrayOf(9, 10, 11, 12),
        intArrayOf(13, 14, 15, 16),
        intArrayOf(17, 18, 19, 20)
    )

    /** Extension of a finger whose joints each bend by [bend] radians (1 = straight). */
    fun extensionForBend(bend: Double): Double {
        var x = 0.0
        var y = 0.0
        var total = 0.0
        var phi = 0.0
        for (k in BONES.indices) {
            phi += bend
            x += BONES[k] * sin(phi)
            y += BONES[k] * -cos(phi)
            total += BONES[k]
        }
        return sqrt(x * x + y * y) / total
    }

    /** Bend that gives [extension] (bisection; the extension decreases as the bend grows). */
    fun bendFor(extension: Double): Double {
        var lo = 0.0
        var hi = PI / 2
        repeat(60) {
            val mid = (lo + hi) / 2
            if (extensionForBend(mid) > extension) lo = mid else hi = mid
        }
        return (lo + hi) / 2
    }

    fun hand(ts: Long, extension: Double, lengthScale: Double = 1.0, side: HandSideStatus = HandSideStatus.MATCHES, luma: Float? = 120f): HandTrackingResult =
        HandTrackingResult.HandDetected(
            timestampMs = ts,
            landmarks = landmarks(bendFor(extension), lengthScale),
            handSide = HandSide.RIGHT,
            confidence = 0.95f,
            imageWidth = 100,
            imageHeight = 100,
            sideStatus = side,
            frameInfo = FrameInfo(ts / 33, luma, 0)
        )

    /** The 21 landmarks for a hand whose joints bend by [bend] radians and whose fingers scale by [lengthScale]. */
    fun landmarks(bend: Double, lengthScale: Double = 1.0): List<HandLandmark> {
        val lm = MutableList(HandLandmarkIndex.COUNT) { HandLandmark(it, 0.5f, 0.5f, 0f) }
        lm[HandLandmarkIndex.WRIST] = HandLandmark(0, 0.5f, 0.9f, 0f)
        lm[HandLandmarkIndex.THUMB_TIP] = HandLandmark(4, 0.4f, 0.5f, 0f)
        val scale = FINGER_PX * lengthScale / 100.0
        for (f in 0 until 4) {
            var x = MCP[f][0]
            var y = MCP[f][1]
            val ids = FINGER_LANDMARKS[f]
            lm[ids[0]] = HandLandmark(ids[0], x.toFloat(), y.toFloat(), 0f)
            var phi = 0.0
            for (k in BONES.indices) {
                phi += bend
                x += BONES[k] * scale * sin(phi)
                y += BONES[k] * scale * -cos(phi)
                lm[ids[k + 1]] = HandLandmark(ids[k + 1], x.toFloat(), y.toFloat(), 0f)
            }
        }
        return lm
    }
}
