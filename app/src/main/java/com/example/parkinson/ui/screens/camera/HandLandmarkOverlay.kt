package com.example.parkinson.ui.screens.camera

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.HandLandmarkMapper
import com.example.parkinson.mediapipe.HandSide
import com.example.parkinson.mediapipe.HandTrackingResult

private val LineColor = Color(0xCCFFFFFF)
private val PointColor = Color(0xFF4DB6AC)
private val TipColor = Color(0xFFFFB300) // thumb tip + index tip (used for tapping)

/**
 * Draws the hand skeleton over the camera Preview.
 * Place it in a Box on top of CameraPreview, with the same size.
 *
 * @param mirror true when the front camera is used.
 */
@Composable
fun HandLandmarkOverlay(
    result: HandTrackingResult,
    mirror: Boolean,
    modifier: Modifier = Modifier,
    fillCenter: Boolean = true
) {
    val hand = result as? HandTrackingResult.HandDetected ?: return

    Canvas(modifier = modifier.fillMaxSize()) {
        val points = hand.landmarks.map { landmark ->
            HandLandmarkMapper.mapToView(
                landmark = landmark,
                imageWidth = hand.imageWidth,
                imageHeight = hand.imageHeight,
                viewWidth = size.width,
                viewHeight = size.height,
                mirror = mirror,
                fillCenter = fillCenter
            )
        }

        HandLandmarkIndex.CONNECTIONS.forEach { (from, to) ->
            if (from < points.size && to < points.size) {
                drawLine(
                    color = LineColor,
                    start = points[from],
                    end = points[to],
                    strokeWidth = 3.dp.toPx()
                )
            }
        }

        points.forEachIndexed { index, point ->
            val isTip = index == HandLandmarkIndex.THUMB_TIP ||
                index == HandLandmarkIndex.INDEX_FINGER_TIP
            drawCircle(
                color = if (isTip) TipColor else PointColor,
                radius = if (isTip) 7.dp.toPx() else 4.dp.toPx(),
                center = point
            )
        }
    }
}

/** Persian status text for the camera screen. */
fun HandTrackingResult.toStatusMessage(): String = when (this) {
    is HandTrackingResult.NoHandDetected ->
        "دستی دیده نمی‌شود. دست خود را مقابل دوربین قرار دهید."

    is HandTrackingResult.HandDetected ->
        "دست شناسایی شد ✓"

    is HandTrackingResult.WrongHandDetected ->
        "لطفاً دست ${expected.persianName()} را مقابل دوربین قرار دهید."

    is HandTrackingResult.TrackingLost ->
        "ردیابی دست قطع شد. دست را دوباره مقابل دوربین بگیرید."

    is HandTrackingResult.LowConfidence ->
        "دست واضح نیست. نور را بیشتر کنید و دست را ثابت نگه دارید."

    is HandTrackingResult.MultipleHandsDetected ->
        "بیش از یک دست دیده می‌شود. فقط یک دست مقابل دوربین باشد."

    is HandTrackingResult.Error ->
        "خطا در تشخیص دست: $message"
}

private fun HandSide.persianName(): String = if (this == HandSide.RIGHT) "راست" else "چپ"
