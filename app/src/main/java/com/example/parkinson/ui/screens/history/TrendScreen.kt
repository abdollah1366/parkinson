package com.example.parkinson.ui.screens.history

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.parkinson.R
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.TrendPoint
import com.example.parkinson.assessment.Trends
import com.example.parkinson.data.StoredResult
import com.example.parkinson.medication.MedicationState
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.ui.components.assessmentTitle
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.screens.medication.medicationLabel
import com.example.parkinson.viewmodel.Loadable

private val OnColor = Color(0xFF2E7D32)
private val OffColor = Color(0xFFE65100)

/** Point colour by the medication state reported before the test. */
@Composable
private fun pointColor(state: MedicationState?): Color = when (state) {
    MedicationState.ON -> OnColor
    MedicationState.OFF -> OffColor
    else -> MaterialTheme.colorScheme.outline
}

/**
 * The performance index of one test over time for the active person, with each point coloured by the medication
 * state reported before that test (ON, OFF, or unknown), so changes are not read across ON and OFF.
 */
@Composable
fun TrendScreen(history: Loadable<List<StoredResult>>) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.trend_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        when (history) {
            Loadable.Loading -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            is Loadable.Loaded -> TrendContent(history.value)
        }
    }
}

@Composable
private fun TrendContent(history: List<StoredResult>) {
    val types = Trends.typesWithScores(history)
    if (types.isEmpty()) {
        Text(stringResource(R.string.trend_empty), style = MaterialTheme.typography.bodyLarge)
        return
    }
    var typeId by rememberSaveable { mutableStateOf(types.first().id) }
    val type = AssessmentType.fromId(typeId)?.takeIf { it in types } ?: types.first()
    val hands = Trends.hands(history, type)
    var handName by rememberSaveable(type) { mutableStateOf(hands.first()?.name) }
    val hand = hands.firstOrNull { it?.name == handName } ?: hands.first()
    val points = Trends.points(history, type, hand)

    Text(stringResource(R.string.trend_intro), style = MaterialTheme.typography.bodyMedium)
    Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (t in types) {
            FilterChip(selected = t == type, onClick = { typeId = t.id }, label = { Text(assessmentTitle(t)) })
        }
    }
    if (hands.size > 1) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (h in hands) {
                if (h != null) FilterChip(selected = h == hand, onClick = { handName = h.name }, label = { Text(handLabel(h)) })
            }
        }
    } else if (hand != null) {
        Text(handLabel(hand), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.trend_axis), style = MaterialTheme.typography.labelLarge)
            TrendChart(points)
            Legend()
            Summary(points)
        }
    }

    Text(stringResource(R.string.trend_list_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    for (p in points.asReversed()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(PersianFormat.dateTime(p.timestampEpochMs), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(
                    R.string.trend_point,
                    PersianFormat.integer(p.index),
                    p.medicationState?.let { medicationLabel(it) } ?: stringResource(R.string.trend_med_unknown)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = pointColor(p.medicationState)
            )
        }
    }
}

/** Index (0..100, upwards) against test order, oldest on the left; time runs left to right as on other charts. */
@Composable
private fun TrendChart(points: List<TrendPoint>) {
    val measurer = rememberTextMeasurer()
    val grid = MaterialTheme.colorScheme.outlineVariant
    val line = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val colors = points.map { pointColor(it.medicationState) }
    val description = stringResource(
        R.string.trend_chart_description,
        PersianFormat.integer(points.size),
        PersianFormat.integer(points.first().index),
        PersianFormat.integer(points.last().index)
    )
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column {
            Canvas(modifier = Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = description }) {
                val left = 36.dp.toPx()
                val right = size.width - 12.dp.toPx()
                val top = 8.dp.toPx()
                val bottom = size.height - 8.dp.toPx()
                fun y(index: Int) = bottom - (bottom - top) * index / 100f
                for (level in listOf(0, 50, 100)) {
                    drawLine(grid, Offset(left, y(level)), Offset(right, y(level)), strokeWidth = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
                    val text = measurer.measure(PersianFormat.integer(level), labelStyle)
                    drawText(text, topLeft = Offset(0f, y(level) - text.size.height / 2f))
                }
                val xs = points.indices.map { i ->
                    if (points.size == 1) (left + right) / 2 else left + (right - left) * i / (points.size - 1)
                }
                for (i in 1 until points.size) {
                    drawLine(line, Offset(xs[i - 1], y(points[i - 1].index)), Offset(xs[i], y(points[i].index)),
                        strokeWidth = 2.dp.toPx())
                }
                points.forEachIndexed { i, p -> drawCircle(colors[i], radius = 6.dp.toPx(), center = Offset(xs[i], y(p.index))) }
            }
            Row(modifier = Modifier.fillMaxWidth().padding(start = 36.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(PersianFormat.shortDate(points.first().timestampEpochMs), style = MaterialTheme.typography.labelSmall)
                if (points.size > 1) {
                    Text(PersianFormat.shortDate(points.last().timestampEpochMs), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun Legend() {
    Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
        LegendItem(OnColor, stringResource(R.string.med_label_on))
        LegendItem(OffColor, stringResource(R.string.med_label_off))
        LegendItem(MaterialTheme.colorScheme.outline, stringResource(R.string.trend_med_unknown))
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(12.dp).background(color, CircleShape))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}

/** First and last index, and the ON and OFF means when both were recorded. */
@Composable
private fun Summary(points: List<TrendPoint>) {
    if (points.size > 1) {
        Text(
            stringResource(
                R.string.trend_first_last,
                PersianFormat.integer(points.first().index),
                PersianFormat.integer(points.last().index)
            ),
            style = MaterialTheme.typography.bodyMedium
        )
    }
    val on = Trends.mean(points, MedicationState.ON)
    val off = Trends.mean(points, MedicationState.OFF)
    if (on != null && off != null) {
        Text(
            stringResource(R.string.trend_on_off_means, PersianFormat.decimal(on, 0), PersianFormat.decimal(off, 0)),
            style = MaterialTheme.typography.bodyMedium
        )
    }
    Text(stringResource(R.string.trend_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
