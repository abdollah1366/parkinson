package com.example.parkinson.mediapipe

import androidx.compose.ui.geometry.Offset
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark
import kotlin.math.max
import kotlin.math.min

object HandLandmarkMapper {

    fun toHandLandmarks(source: List<NormalizedLandmark>): List<HandLandmark> =
        source.mapIndexed { index, landmark ->
            HandLandmark(index = index, x = landmark.x(), y = landmark.y(), z = landmark.z())
        }
    /**
     * MediaPipe assumes a mirrored (selfie) image when it labels "Left"/"Right".
     * CameraX ImageAnalysis frames are NOT mirrored, so the label must be flipped.
     * If left/right look inverted on your phone, set flip = false.
     */
    fun toHandSide(label: String?, flip: Boolean): HandSide? {
        val raw = when (label?.lowercase()) {
            "left" -> HandSide.LEFT
            "right" -> HandSide.RIGHT
            else -> return null
        }
        return if (flip) raw.opposite() else raw
    }

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
