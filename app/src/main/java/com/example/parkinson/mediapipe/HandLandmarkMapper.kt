package com.example.parkinson.mediapipe

import androidx.compose.ui.geometry.Offset
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max
import kotlin.math.min

object HandLandmarkMapper {

    /**
     * Converts MediaPipe landmarks to the UPRIGHT (display-oriented) image frame.
     *
     * MediaPipe Tasks returns landmarks normalized to the image it was given (the unrotated
     * sensor-oriented CameraX bitmap). ImageProcessingOptions.setRotationDegrees only rotates the
     * region MediaPipe crops internally; results are projected back onto the unrotated input.
     * Everything downstream (overlay, tapping distances) uses the upright frame and the upright
     * width/height, so the same rotation CameraX reports must be applied here.
     *
     * @param rotationDegrees ImageInfo.rotationDegrees: clockwise rotation that makes the frame upright
     */
    fun toHandLandmarks(source: List<NormalizedLandmark>, rotationDegrees: Int): List<HandLandmark> =
        toUpright(
            source.mapIndexed { index, landmark ->
                HandLandmark(index = index, x = landmark.x(), y = landmark.y(), z = landmark.z())
            },
            rotationDegrees
        )

    /**
     * Rotates normalized sensor-frame landmarks clockwise by [rotationDegrees] (0/90/180/270).
     * A pure rotation: it never mirrors, so chirality and handedness are unchanged, and pixel
     * distances are preserved when used with the upright width/height.
     */
    fun toUpright(landmarks: List<HandLandmark>, rotationDegrees: Int): List<HandLandmark> =
        when (((rotationDegrees % 360) + 360) % 360) {
            0 -> landmarks
            90 -> landmarks.map { it.copy(x = 1f - it.y, y = it.x) }
            180 -> landmarks.map { it.copy(x = 1f - it.x, y = 1f - it.y) }
            270 -> landmarks.map { it.copy(x = it.y, y = 1f - it.x) }
            else -> throw IllegalArgumentException("rotationDegrees must be a multiple of 90: $rotationDegrees")
        }
    // Handedness (left/right) is mapped by HandednessMapper, not here: display mirroring below
    // only moves overlay points and must never change which hand is reported.

    /**
     * Converts a normalized landmark to a pixel position inside the overlay view.
     *
     * @param mirror true for the front camera (the Preview is mirrored, the analysis frame is not)
     * @param fillCenter true when PreviewView uses FILL_CENTER (the default), false for FIT_CENTER
     */
    fun mapToView(
        landmark: HandLandmark,
        imageWidth: Int,
        imageHeight: Int,
        viewWidth: Float,
        viewHeight: Float,
        mirror: Boolean,
        fillCenter: Boolean = true
    ): Offset {
        if (imageWidth <= 0 || imageHeight <= 0) return Offset.Zero

        val scaleX = viewWidth / imageWidth
        val scaleY = viewHeight / imageHeight
        val scale = if (fillCenter) max(scaleX, scaleY) else min(scaleX, scaleY)

        val offsetX = (viewWidth - imageWidth * scale) / 2f
        val offsetY = (viewHeight - imageHeight * scale) / 2f

        var x = landmark.x * imageWidth * scale + offsetX
        val y = landmark.y * imageHeight * scale + offsetY
        if (mirror) x = viewWidth - x

        return Offset(x, y)
    }
}
