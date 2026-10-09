package com.example.parkinson.ui.screens.result

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.openclose.HandOpenCloseResult
import com.example.parkinson.openclose.OpenCloseQualityIssue
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable

/**
 * Result of one Hand Opening/Closing recording: measurements, technical quality and warnings.
 * No performance score and no clinical interpretation; the disclaimer is always shown.
 */
@Composable
fun HandOpenCloseResultScreen(
    result: Loadable<HandOpenCloseResult?>,
    onRepeat: (SelectedHand) -> Unit,
    onNextTest: () -> Unit,
    onHome: () -> Unit,
) {
    when (result) {
        Loadable.Loading -> CenteredMessage(stringResource(R.string.result_loading), showProgress = true)
        is Loadable.Loaded -> {
            val value = result.value
            if (value == null) {
                CenteredMessage(stringResource(R.string.result_not_found))
            } else {
                OpenCloseContent(value, onRepeat, onNextTest, onHome)
            }
        }
    }
}

@Composable
private fun OpenCloseContent(
    r: HandOpenCloseResult,
    onRepeat: (SelectedHand) -> Unit,
    onNextTest: () -> Unit,
    onHome: () -> Unit,
) {
    val m = r.metrics
    val f = m.frames
    fun d(v: Double, digits: Int = 1) = PersianFormat.decimal(v, digits)
    val unavailable = stringResource(R.string.oc_result_unavailable)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.oc_result_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = "${PersianFormat.dateTime(r.timestampEpochMs)}  •  ${handLabel(r.hand)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        QualityChip(r.qualityStatus)

        OpenCloseMetricCard(
            title = stringResource(R.string.oc_result_cycles),
            value = PersianFormat.integer(m.completedCycles),
            emphasized = m.completedCycles > 0
        )
        OpenCloseMetricCard(
            title = stringResource(R.string.oc_result_rate),
            value = m.cycleRatePerSecond?.let { stringResource(R.string.oc_result_rate_value, d(it, 2)) } ?: unavailable
        )
        OpenCloseMetricCard(
            title = stringResource(R.string.oc_result_interval),
            value = if (m.meanIntervalMs != null && m.intervalCvPercent != null) {
                stringResource(
                    R.string.oc_result_interval_value,
                    PersianFormat.integer(Math.round(m.meanIntervalMs).toInt()),
                    d(m.intervalCvPercent)
                )
            } else unavailable
        )
        OpenCloseMetricCard(
            title = stringResource(R.string.oc_result_amplitude),
            value = if (m.meanAmplitude != null && m.amplitudeCvPercent != null) {
                stringResource(R.string.oc_result_amplitude_value, d(m.meanAmplitude, 2), d(m.amplitudeCvPercent))
            } else unavailable,
            description = stringResource(R.string.oc_result_amplitude_desc)
        )
        OpenCloseMetricCard(
            title = stringResource(R.string.oc_result_duration),
            value = stringResource(
                R.string.oc_result_duration_value,
                d(m.measurementDurationMs / 1000.0),
                d(m.usableDurationMs / 1000.0)
            )
        )
        OpenCloseMetricCard(
            title = stringResource(R.string.oc_result_tracking),
            value = stringResource(
                R.string.oc_result_tracking_value,
                PersianFormat.integer(Math.round(f.trackingRate * 100).toInt()),
                PersianFormat.integer(f.longestDropoutMs)
            )
        )
        OpenCloseMetricCard(
            title = stringResource(R.string.oc_result_partial),
            value = stringResource(
                R.string.oc_result_partial_value,
                PersianFormat.integer(m.partialOpenings + m.partialClosings)
            )
        )
        OpenCloseMetricCard(
            title = stringResource(R.string.oc_result_quality_score),
            value = stringResource(R.string.oc_result_score_value, PersianFormat.integer(r.qualityScore))
        )

        val warnings = warningsFor(r)
        if (warnings.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.oc_result_warnings_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    warnings.forEach { res ->
                        Text(
                            text = stringResource(res),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
            shape = MaterialTheme.shapes.medium
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = stringResource(R.string.oc_result_no_score),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(R.string.oc_result_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        PrimaryButton(text = stringResource(R.string.btn_repeat_assessment), onClick = { onRepeat(r.hand) })
        if (r.qualityStatus == QualityStatus.VALID || r.qualityStatus == QualityStatus.LOW_QUALITY) {
            OutlinedButton(onClick = onNextTest, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.btn_next_test))
            }
        }
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.btn_back_home))
        }
    }
}

/** One patient-facing warning per kind of problem, most important first; empty for a VALID recording. */
private fun warningsFor(r: HandOpenCloseResult): List<Int> {
    if (r.qualityStatus == QualityStatus.VALID) return emptyList()
    val out = LinkedHashSet<Int>()
    fun add(@StringRes res: Int) { out += res }
    r.qualityIssues.forEach { issue ->
        when (issue) {
            OpenCloseQualityIssue.NO_CYCLES_DETECTED, OpenCloseQualityIssue.TOO_FEW_CYCLES -> add(R.string.oc_warn_few_cycles)
            OpenCloseQualityIssue.PARTIAL_MOVEMENTS_FREQUENT -> add(R.string.oc_warn_partial)
            OpenCloseQualityIssue.OUT_OF_FRAME_FREQUENT -> add(R.string.oc_warn_out_of_frame)
            OpenCloseQualityIssue.INSUFFICIENT_TRACKING, OpenCloseQualityIssue.REDUCED_TRACKING,
            OpenCloseQualityIssue.DROPOUTS_PRESENT, OpenCloseQualityIssue.EXCESSIVE_DROPOUT,
            OpenCloseQualityIssue.NO_HAND_DETECTED -> add(R.string.oc_warn_tracking)
            OpenCloseQualityIssue.LOW_FPS, OpenCloseQualityIssue.INSUFFICIENT_FPS -> add(R.string.oc_warn_fps)
            OpenCloseQualityIssue.NOISY_SIGNAL -> add(R.string.oc_warn_noise)
            OpenCloseQualityIssue.HAND_SIDE_UNCERTAIN -> add(R.string.oc_warn_hand_side)
            OpenCloseQualityIssue.LIGHTING_AFFECTED_TRACKING -> add(R.string.ft_note_lighting)
            else -> add(R.string.oc_warn_general)
        }
    }
    return out.toList()
}

@Composable
private fun OpenCloseMetricCard(
    title: String,
    value: String,
    description: String? = null,
    emphasized: Boolean = false,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (emphasized) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
