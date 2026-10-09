package com.example.parkinson.gait

import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * MediaPipe Pose Landmarker indices (33 points). Only the joints used by the gait pipeline are named.
 */
object PoseLandmarkIndex {
    const val NOSE = 0
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24
    const val LEFT_KNEE = 25
    const val RIGHT_KNEE = 26
    const val LEFT_ANKLE = 27
    const val RIGHT_ANKLE = 28
    const val LEFT_HEEL = 29
    const val RIGHT_HEEL = 30
    const val LEFT_FOOT_INDEX = 31
    const val RIGHT_FOOT_INDEX = 32
    const val COUNT = 33
}

/**
 * One body landmark. [x] and [y] are normalized to the upright image (0..1), [visibility] is MediaPipe's
 * own visibility score (0..1). Not a physical unit.
 */
data class PosePoint(val x: Double, val y: Double, val visibility: Double)

enum class PoseFrameStatus {
    /** Core joints (shoulders, hips, ankles) are visible and inside the image. */
    VALID,
    /** No person detected in the frame. */
    NO_POSE,
    /** A core joint is missing or below the visibility limit (for example the feet are out of view). */
    INCOMPLETE,
    /** The body is too small in the image for a stable measurement (the subject is too far away). */
    TOO_SMALL,
    /** A detection error was reported by MediaPipe. */
    ERROR
}

/**
 * Image-plane features of one frame. Lengths are divided by the torso length (mid-hip to mid-shoulder,
 * in pixels), so they are dimensionless and do not depend on the subject's distance from the camera.
 * Nothing here is a physical distance, an anatomical angle or a calibrated joint measurement.
 */
data class PoseFeatures(
    /** Torso length in pixels of the upright image (the reference for the dimensionless features). */
    val torsoLengthPx: Double,
    /** Angle of the mid-hip -> mid-shoulder line from the image vertical, degrees. Image-plane, uncalibrated. */
    val trunkInclinationDeg: Double,
    /** Left/right wrist horizontal offset from the shoulder on the same side, in torso lengths. Null when the wrist is not visible. */
    val leftWristOffset: Double?,
    val rightWristOffset: Double?,
    /** Left/right ankle height relative to the mid-hip, in torso lengths, positive = lower in the image. */
    val leftAnkleHeight: Double,
    val rightAnkleHeight: Double,
    /** Shoulder-line tilt from horizontal, degrees (image-plane). Positive = right shoulder lower in the image. */
    val shoulderTiltDeg: Double,
)

data class PoseFrame(
    val timestampMs: Long,
    val status: PoseFrameStatus,
    val features: PoseFeatures?,
)

/**
 * Turns the 33 landmarks of one frame into [PoseFrame]. Gates:
 * - A core joint (shoulders, hips, ankles) needs visibility >= [minVisibility] and a position inside the
 *   image (2 % margin). Otherwise the frame is INCOMPLETE and gives no features.
 * - The torso must be at least [minTorsoPx] pixels long, otherwise the frame is TOO_SMALL.
 * - Wrists are optional: missing wrists only blank the arm-swing features of that side.
 *
 * ENGINEERING limits (not clinically validated): [minVisibility] 0.5 follows MediaPipe's own default
 * presence threshold; [minTorsoPx] 60 px is about a sixth of a 360 px upright frame.
 */
class CameraPoseFeatureExtractor(
    private val minVisibility: Double = 0.5,
    private val minTorsoPx: Double = 60.0,
    private val margin: Double = 0.02,
) {

    fun extract(
        timestampMs: Long,
        points: List<PosePoint?>,
        imageWidth: Int,
        imageHeight: Int,
    ): PoseFrame {
        if (points.size < PoseLandmarkIndex.COUNT || imageWidth <= 0 || imageHeight <= 0) {
            return PoseFrame(timestampMs, PoseFrameStatus.ERROR, null)
        }
        val core = intArrayOf(
            PoseLandmarkIndex.LEFT_SHOULDER, PoseLandmarkIndex.RIGHT_SHOULDER,
            PoseLandmarkIndex.LEFT_HIP, PoseLandmarkIndex.RIGHT_HIP,
            PoseLandmarkIndex.LEFT_ANKLE, PoseLandmarkIndex.RIGHT_ANKLE,
        )
        if (points.all { it == null }) {
            return PoseFrame(timestampMs, PoseFrameStatus.NO_POSE, null)
        }
        if (core.any { !usable(points[it]) }) {
            return PoseFrame(timestampMs, PoseFrameStatus.INCOMPLETE, null)
        }

        val w = imageWidth.toDouble()
        val h = imageHeight.toDouble()
        fun px(i: Int) = points[i]!!.x * w to points[i]!!.y * h

        val (lsx, lsy) = px(PoseLandmarkIndex.LEFT_SHOULDER)
        val (rsx, rsy) = px(PoseLandmarkIndex.RIGHT_SHOULDER)
        val (lhx, lhy) = px(PoseLandmarkIndex.LEFT_HIP)
        val (rhx, rhy) = px(PoseLandmarkIndex.RIGHT_HIP)
        val shoulderX = (lsx + rsx) / 2
        val shoulderY = (lsy + rsy) / 2
        val hipX = (lhx + rhx) / 2
        val hipY = (lhy + rhy) / 2

        val torso = hypot(shoulderX - hipX, shoulderY - hipY)
        if (torso < minTorsoPx) return PoseFrame(timestampMs, PoseFrameStatus.TOO_SMALL, null)

        // Image vertical points up (negative y), so "up" is (0, -1).
        val trunkDeg = Math.toDegrees(atan2(shoulderX - hipX, hipY - shoulderY))
        val shoulderTiltDeg = Math.toDegrees(atan2(rsy - lsy, rsx - lsx))

        val leftWrist = wristOffset(points, PoseLandmarkIndex.LEFT_WRIST, shoulderX, torso, w, h)
        val rightWrist = wristOffset(points, PoseLandmarkIndex.RIGHT_WRIST, shoulderX, torso, w, h)
        val leftAnkleY = px(PoseLandmarkIndex.LEFT_ANKLE).second
        val rightAnkleY = px(PoseLandmarkIndex.RIGHT_ANKLE).second

        val features = PoseFeatures(
            torsoLengthPx = torso,
            trunkInclinationDeg = trunkDeg,
            leftWristOffset = leftWrist,
            rightWristOffset = rightWrist,
            leftAnkleHeight = (leftAnkleY - hipY) / torso,
            rightAnkleHeight = (rightAnkleY - hipY) / torso,
            shoulderTiltDeg = shoulderTiltDeg,
        )
        return PoseFrame(timestampMs, PoseFrameStatus.VALID, features)
    }

    /** Wrist x offset from the mid-shoulder, in torso lengths. Null when the wrist is not visible. */
    private fun wristOffset(
        points: List<PosePoint?>,
        index: Int,
        shoulderX: Double,
        torso: Double,
        w: Double,
        h: Double,
    ): Double? {
        val p = points[index]?.takeIf { usable(it) } ?: return null
        return (p.x * w - shoulderX) / torso
    }

    private fun usable(p: PosePoint?): Boolean =
        p != null && p.visibility >= minVisibility &&
            p.x >= -margin && p.x <= 1 + margin && p.y >= -margin && p.y <= 1 + margin
}

/** Series-level data quality: how much of the recording had a valid pose, and how long the gaps were. */
data class PoseSeriesQuality(
    val frameCount: Int,
    val validFrames: Int,
    /** Valid frames / all frames, percent. */
    val validFramePercent: Double,
    /** Longest run of consecutive non-valid frames (ms, from the timestamps of the first and last frame of the run). */
    val longestGapMs: Long,
    /** Frame rate from the median frame interval (Hz); 0 when fewer than two frames. */
    val frameRateHz: Double,
    /** Statuses that occurred, for the explanation shown to the user. */
    val statusCounts: Map<PoseFrameStatus, Int>,
) {
    companion object {
        fun of(frames: List<PoseFrame>): PoseSeriesQuality {
            val valid = frames.count { it.status == PoseFrameStatus.VALID }
            var longest = 0L
            var runStart: Long? = null
            var lastNonValid: Long? = null
            for (f in frames) {
                if (f.status == PoseFrameStatus.VALID) {
                    runStart?.let { s -> longest = maxOf(longest, (lastNonValid ?: s) - s) }
                    runStart = null
                } else {
                    if (runStart == null) runStart = f.timestampMs
                    lastNonValid = f.timestampMs
                }
            }
            runStart?.let { s -> longest = maxOf(longest, (lastNonValid ?: s) - s) }
            val intervals = (1 until frames.size).map { (frames[it].timestampMs - frames[it - 1].timestampMs).toDouble() }
                .filter { it > 0 }.sorted()
            val median = if (intervals.isEmpty()) 0.0 else intervals[intervals.size / 2]
            return PoseSeriesQuality(
                frameCount = frames.size,
                validFrames = valid,
                validFramePercent = if (frames.isEmpty()) 0.0 else 100.0 * valid / frames.size,
                longestGapMs = longest,
                frameRateHz = if (median > 0) 1000.0 / median else 0.0,
                statusCounts = frames.groupingBy { it.status }.eachCount(),
            )
        }
    }
}
