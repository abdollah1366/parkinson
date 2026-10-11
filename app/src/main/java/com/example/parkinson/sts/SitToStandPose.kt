package com.example.parkinson.sts

import com.example.parkinson.gait.PoseLandmarkIndex
import com.example.parkinson.gait.PosePoint
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

enum class SitToStandSide { LEFT, RIGHT }

/** Why a frame gives no measurement. Null in a valid sample. */
enum class SitToStandIssue {
    /** No person found in the frame. */
    NO_POSE,

    /** The pose model reported an error for this frame. */
    ERROR,

    /** A joint needed for the measurement is missing or below the visibility limit. */
    LOW_VISIBILITY,

    /** A needed joint is outside the image. */
    OUT_OF_FRAME,

    /** The lower leg is too small in the image for a stable measurement (the person is too far away). */
    TOO_SMALL,
}

/**
 * One frame reduced to the sit-to-stand features of one side of the body. Angles are in degrees, lengths in
 * upright-image pixels. [kneeAngleDeg] is the angle hip-knee-ankle (180 = straight leg). [hipYPx] is the image
 * y of the hip (y grows downwards). [trunkLeanDeg] is the angle of the hip-to-shoulder line from the image
 * vertical. Nothing here is calibrated physical distance.
 */
data class SitToStandSample(
    val timestampMs: Long,
    val issue: SitToStandIssue?,
    val side: SitToStandSide,
    /** Mean visibility of the hip, knee and ankle (0..1); used to choose the side in calibration. */
    val visibility: Double,
    val kneeAngleDeg: Double,
    val hipYPx: Double,
    val shinPx: Double,
    val trunkLeanDeg: Double,
) {
    val isValid: Boolean get() = issue == null

    companion object {
        fun invalid(timestampMs: Long, issue: SitToStandIssue, side: SitToStandSide = SitToStandSide.RIGHT) = SitToStandSample(
            timestampMs = timestampMs,
            issue = issue,
            side = side,
            visibility = 0.0,
            kneeAngleDeg = Double.NaN,
            hipYPx = Double.NaN,
            shinPx = Double.NaN,
            trunkLeanDeg = Double.NaN,
        )
    }
}

/**
 * Sit-to-stand features from the upright pose landmarks. Only the chosen side is measured, so the other leg
 * (hidden behind the body in a side view) does not disturb the result. A joint with visibility below
 * [minVisibility] or outside the image (with a [margin]) makes the frame invalid; the frame is never filled in.
 */
class SitToStandPoseExtractor(
    private val minVisibility: Double = 0.5,
    private val margin: Double = 0.02,
    private val minShinPx: Double = 40.0,
) {

    /** The side whose hip, knee and ankle are more visible in this frame (ties: right). */
    fun chooseSide(points: List<PosePoint?>): SitToStandSide {
        val left = meanVisibility(points, SIDE_LEFT)
        val right = meanVisibility(points, SIDE_RIGHT)
        return if (left > right) SitToStandSide.LEFT else SitToStandSide.RIGHT
    }

    fun extract(
        timestampMs: Long,
        points: List<PosePoint?>?,
        imageWidth: Int,
        imageHeight: Int,
        side: SitToStandSide,
    ): SitToStandSample {
        if (points == null) return SitToStandSample.invalid(timestampMs, SitToStandIssue.NO_POSE, side)
        if (points.size < PoseLandmarkIndex.COUNT || imageWidth <= 0 || imageHeight <= 0) {
            return SitToStandSample.invalid(timestampMs, SitToStandIssue.ERROR, side)
        }
        val joints = if (side == SitToStandSide.LEFT) SIDE_LEFT else SIDE_RIGHT
        val needed = listOf(joints.shoulder, joints.hip, joints.knee, joints.ankle)
        for (index in needed) {
            val p = points[index]
            if (p == null || p.visibility < minVisibility) {
                return SitToStandSample.invalid(timestampMs, SitToStandIssue.LOW_VISIBILITY, side)
            }
        }
        for (index in needed) {
            val p = points[index]!!
            if (p.x < -margin || p.x > 1 + margin || p.y < -margin || p.y > 1 + margin) {
                return SitToStandSample.invalid(timestampMs, SitToStandIssue.OUT_OF_FRAME, side)
            }
        }

        val w = imageWidth.toDouble()
        val h = imageHeight.toDouble()
        fun px(i: Int): Pair<Double, Double> = points[i]!!.x * w to points[i]!!.y * h
        val (sx, sy) = px(joints.shoulder)
        val (hx, hy) = px(joints.hip)
        val (kx, ky) = px(joints.knee)
        val (ax, ay) = px(joints.ankle)

        val shin = hypot(ax - kx, ay - ky)
        if (shin < minShinPx) return SitToStandSample.invalid(timestampMs, SitToStandIssue.TOO_SMALL, side)

        return SitToStandSample(
            timestampMs = timestampMs,
            issue = null,
            side = side,
            visibility = (points[joints.hip]!!.visibility + points[joints.knee]!!.visibility +
                points[joints.ankle]!!.visibility) / 3.0,
            kneeAngleDeg = angleDeg(hx - kx, hy - ky, ax - kx, ay - ky),
            hipYPx = hy,
            shinPx = shin,
            // Image vertical: y grows downwards, so "up" from the hip is (0, -1).
            trunkLeanDeg = abs(Math.toDegrees(atan2(sx - hx, hy - sy))),
        )
    }

    private fun meanVisibility(points: List<PosePoint?>, joints: Joints): Double {
        if (points.size < PoseLandmarkIndex.COUNT) return 0.0
        val values = listOf(joints.hip, joints.knee, joints.ankle).map { points[it]?.visibility ?: 0.0 }
        return values.average()
    }

    /** Angle at the vertex between vectors (ax, ay) and (bx, by), in degrees. */
    private fun angleDeg(ax: Double, ay: Double, bx: Double, by: Double): Double {
        val na = sqrt(ax * ax + ay * ay)
        val nb = sqrt(bx * bx + by * by)
        if (na == 0.0 || nb == 0.0) return Double.NaN
        val cos = ((ax * bx + ay * by) / (na * nb)).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cos))
    }

    private class Joints(val shoulder: Int, val hip: Int, val knee: Int, val ankle: Int)

    private companion object {
        val SIDE_LEFT = Joints(
            PoseLandmarkIndex.LEFT_SHOULDER, PoseLandmarkIndex.LEFT_HIP,
            PoseLandmarkIndex.LEFT_KNEE, PoseLandmarkIndex.LEFT_ANKLE,
        )
        val SIDE_RIGHT = Joints(
            PoseLandmarkIndex.RIGHT_SHOULDER, PoseLandmarkIndex.RIGHT_HIP,
            PoseLandmarkIndex.RIGHT_KNEE, PoseLandmarkIndex.RIGHT_ANKLE,
        )
    }
}
