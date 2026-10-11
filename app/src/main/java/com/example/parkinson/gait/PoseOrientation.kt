package com.example.parkinson.gait

/**
 * Converts pose landmarks from the frame MediaPipe received (sensor orientation) to the upright image,
 * using the same mapping as HandLandmarkMapper.toUpright: a pure rotation, never a mirror. Landmark
 * visibility is kept unchanged. The image width and height must be swapped by the caller for 90/270.
 */
object PoseOrientation {

    fun toUpright(points: List<PosePoint?>, rotationDegrees: Int): List<PosePoint?> =
        when (((rotationDegrees % 360) + 360) % 360) {
            0 -> points
            90 -> points.map { p -> p?.let { PosePoint(1.0 - it.y, it.x, it.visibility) } }
            180 -> points.map { p -> p?.let { PosePoint(1.0 - it.x, 1.0 - it.y, it.visibility) } }
            270 -> points.map { p -> p?.let { PosePoint(it.y, 1.0 - it.x, it.visibility) } }
            else -> throw IllegalArgumentException("rotationDegrees must be a multiple of 90: $rotationDegrees")
        }
}
