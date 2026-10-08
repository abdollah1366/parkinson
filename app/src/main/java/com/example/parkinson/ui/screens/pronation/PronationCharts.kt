package com.example.parkinson.ui.screens.pronation

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.ui.format.PersianFormat
import kotlin.math.abs
import kotlin.math.max

/**
 * Angular velocity vs time (deg/s). Time runs left to right like a measurement trace. Plain
 * Compose Canvas: no chart library. The whole chart is one accessibility node ([description]).
 */
@Composable
fun VelocityChart(trace: List<Float>, traceHz: Double, description: String, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val axis = MaterialTheme.colorScheme.outline
    val grid = MaterialTheme.colorScheme.outlineVariant
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(160.dp)
            .clearAndSetSemantics { contentDescription = description }
    ) {
        if (trace.size < 2 || traceHz <= 0) return@Canvas
        val peak = max(1f, trace.maxOf { abs(it) })
        val mid = size.height / 2f
        val scaleY = (size.height / 2f - 4.dp.toPx()) / peak
        val stepX = size.width / (trace.size - 1)
        // One vertical grid line per second.
        val seconds = ((trace.size - 1) / traceHz).toInt()
        for (s in 1..seconds) {
            val x = (s * traceHz).toFloat() * stepX
            drawLine(grid, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.dp.toPx())
        }
        drawLine(axis, Offset(0f, mid), Offset(size.width, mid), strokeWidth = 1.dp.toPx())
        val path = Path()
        trace.forEachIndexed { i, v ->
            val x = i * stepX
            val y = mid - v * scaleY
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, line, style = Stroke(width = 2.dp.toPx()))
    }
}

/**
 * Early / middle / late segment scores as labelled bars (values are printed, so color is never the
 * only cue). Missing segments are shown as "—".
 */
@Composable
fun TrendBars(scores: List<Int?>, labels: List<String>, description: String, modifier: Modifier = Modifier) {
    val bar = MaterialTheme.colorScheme.secondary
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clearAndSetSemantics { contentDescription = description },
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.Bottom
    ) {
        scores.zip(labels).forEach { (score, label) ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = score?.let { PersianFormat.integer(it) } ?: "—",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Box(
                    modifier = Modifier
                        .padding(vertical = 4.dp)
                        .width(40.dp)
                        .height(BAR_HEIGHT)
                ) {
                    Canvas(modifier = Modifier.matchParentSize()) {
                        drawRect(track, size = size)
                        val h = size.height * ((score ?: 0).coerceIn(0, 100) / 100f)
                        drawRect(bar, topLeft = Offset(0f, size.height - h), size = Size(size.width, h))
                    }
                }
                Text(text = label, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

private val BAR_HEIGHT = 96.dp
