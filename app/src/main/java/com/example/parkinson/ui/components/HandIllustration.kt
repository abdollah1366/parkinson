package com.example.parkinson.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

@Composable
fun HandIllustration(
    modifier: Modifier = Modifier,
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    secondaryColor: Color = MaterialTheme.colorScheme.secondary,
) {
    Box(
        modifier = modifier
            .size(100.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(60.dp)) {
            val width = size.width
            val height = size.height

            // 1. Draw Phone outline (stable surface)
            val phoneWidth = width * 0.35f
            val phoneHeight = height * 0.65f
            drawRoundRect(
                color = secondaryColor,
                topLeft = Offset(width * 0.05f, height * 0.2f),
                size = Size(phoneWidth, phoneHeight),
                cornerRadius = CornerRadius(6.dp.toPx(), 6.dp.toPx()),
                style = Stroke(width = 3.dp.toPx())
            )

            // Phone Camera dot
            drawCircle(
                color = secondaryColor,
                radius = 2.dp.toPx(),
                center = Offset(width * 0.225f, height * 0.28f)
            )

            // 2. Draw Hand Tapping gesture (Thumb & Index Finger)
            // Wrist & Palm base
            val handPath = Path().apply {
                moveTo(width * 0.9f, height * 0.85f)
                lineTo(width * 0.7f, height * 0.65f)
                // Index Finger extending towards thumb
                cubicTo(
                    width * 0.6f, height * 0.5f,
                    width * 0.55f, height * 0.35f,
                    width * 0.48f, height * 0.38f
                )
            }

            drawPath(
                path = handPath,
                color = primaryColor,
                style = Stroke(width = 4.dp.toPx())
            )

            // Thumb extending up
            val thumbPath = Path().apply {
                moveTo(width * 0.75f, height * 0.72f)
                cubicTo(
                    width * 0.62f, height * 0.65f,
                    width * 0.52f, height * 0.52f,
                    width * 0.48f, height * 0.46f
                )
            }

            drawPath(
                path = thumbPath,
                color = primaryColor,
                style = Stroke(width = 4.dp.toPx())
            )

            // Tapping action dot/pulse between finger tips
            drawCircle(
                color = primaryColor,
                radius = 4.dp.toPx(),
                center = Offset(width * 0.48f, height * 0.42f)
            )
        }
    }
}
