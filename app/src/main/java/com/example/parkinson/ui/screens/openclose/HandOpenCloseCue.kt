package com.example.parkinson.ui.screens.openclose

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.parkinson.R

/**
 * Looping demonstration of one open/close movement: the fingers fan out to a straight hand and curl
 * into a fist, repeatedly. It is a Compose drawing only (no camera, no MediaPipe, no state written
 * by the measurement): the animated value is read in the draw phase, so the screen is not recomposed
 * on every frame. If the system animations are turned off, the hand is drawn static and open.
 */
@Composable
fun HandOpenCloseCue(modifier: Modifier = Modifier, showText: Boolean = true) {
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    val transition = rememberInfiniteTransition(label = "hand-open-close-cue")
    val cycle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = CYCLE_MS, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "openness"
    )

    val primary = MaterialTheme.colorScheme.primary
    val container = MaterialTheme.colorScheme.primaryContainer
    val description = stringResource(R.string.oc_cue_description)

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Canvas(
            modifier = Modifier
                .size(CUE_SIZE)
                .semantics { contentDescription = description }
        ) {
            // Draw phase only: this read does not trigger recomposition.
            val openness = if (reduceMotion) 1f else cycle
            drawHandCue(openness, primary, container)
        }
        if (showText) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.oc_cue_text),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
            }
        }
    }
}

private const val CYCLE_MS = 1_600

private val CUE_SIZE = 120.dp

/** Palm (rounded rectangle), four fingers whose length follows [openness] (0 = fist, 1 = straight), and a thumb. */
private fun DrawScope.drawHandCue(openness: Float, fingerColor: Color, palmColor: Color) {
    val w = size.width
    val h = size.height
    val palmWidth = w * 0.52f
    val palmHeight = h * 0.36f
    val palmLeft = (w - palmWidth) / 2
    val palmTop = h * 0.56f
    val corner = CornerRadius(palmWidth * 0.3f, palmWidth * 0.3f)

    drawRoundRect(color = palmColor, topLeft = Offset(palmLeft, palmTop), size = Size(palmWidth, palmHeight), cornerRadius = corner)

    val fingerWidth = palmWidth / 5.2f
    val fingerMaxLength = h * 0.42f
    // Fist: fingers reach only into the palm area; straight: full length.
    val length = fingerMaxLength * (0.35f + 0.65f * openness)
    for (i in 0 until 4) {
        val x = palmLeft + fingerWidth * (0.35f + i * 1.15f)
        drawRoundRect(
            color = fingerColor,
            topLeft = Offset(x, palmTop - length),
            size = Size(fingerWidth, length + fingerWidth * 0.4f),
            cornerRadius = CornerRadius(fingerWidth / 2, fingerWidth / 2)
        )
    }

    // Thumb: sticks out to the side and folds over the palm when closed.
    val thumbLength = h * 0.24f * (0.4f + 0.6f * openness)
    drawRoundRect(
        color = fingerColor,
        topLeft = Offset(palmLeft - fingerWidth * 0.3f, palmTop + palmHeight * 0.25f - thumbLength * 0.4f),
        size = Size(thumbLength * 0.45f, fingerWidth * 1.05f),
        cornerRadius = CornerRadius(fingerWidth / 2, fingerWidth / 2)
    )
}
