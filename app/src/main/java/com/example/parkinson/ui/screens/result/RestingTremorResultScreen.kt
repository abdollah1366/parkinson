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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tremor.RestingTremorQualityIssue
import com.example.parkinson.tremor.RestingTremorResult
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable

/**
 * Result of one Resting Hand Tremor recording: measurements with units, data quality and warnings.
 * No tremor score, no severity and no diagnosis; the disclaimer is always shown.
 */
@Composable
fun RestingTremorResultScreen(
    result: Loadable<RestingTremorResult?>,
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
                RestingTremorContent(value, onRepeat, onNextTest, onHome)
            }
        }
    }
}

@Composable
private fun RestingTremorContent(
    r: RestingTremorResult,
    onRepeat: (SelectedHand) -> Unit,
    onNextTest: () -> Unit,
    onHome: () -> Unit,
) {
    val m = r.metrics
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
            text = stringResource(R.string.rt_result_title),
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

        TremorMetricCard(
            title = stringResource(R.string.rt_result_duration),
            value = stringResource(
                R.string.rt_result_duration_value,
                d(m.actualDurationMs / 1000.0),
                d(m.plannedDurationMs / 1000.0)
            )
        )
        TremorMetricCard(
            title = stringResource(R.string.rt_result_valid),
            value = stringResource(R.string.rt_result_valid_value, d(m.validFramePercent, 0))
        )
        TremorMetricCard(
            title = stringResource(R.string.rt_result_interruptions),
            value = stringResource(
                R.string.rt_result_interruptions_value,
                PersianFormat.integer(m.interruptionCount),
                PersianFormat.integer(m.longestInterruptionMs.toInt())
            )
        )
        TremorMetricCard(
            title = stringResource(R.string.rt_result_rate),
            value = stringResource(R.string.rt_result_rate_value, d(m.frameRateHz, 1))
        )
        TremorMetricCard(
            title = stringResource(R.string.rt_result_amplitude),
            value = m.amplitudeRmsPercent?.let { stringResource(R.string.rt_result_amplitude_value, d(it, 2)) } ?: unavailable,
            description = stringResource(R.string.rt_result_amplitude_desc),
            emphasized = m.amplitudeRmsPercent != null
        )
        TremorMetricCard(
            title = stringResource(R.string.rt_result_frequency),
            value = m.dominantFrequencyHz?.let { stringResource(R.string.rt_result_frequency_value, d(it, 2)) } ?: unavailable,
            description = stringResource(R.string.rt_result_frequency_desc)
        )
        TremorMetricCard(
            title = stringResource(R.string.rt_result_periodicity),
            value = m.periodicityPercent?.let { stringResource(R.string.rt_result_periodicity_value, d(it, 0)) } ?: unavailable
        )
        TremorMetricCard(
            title = stringResource(R.string.rt_result_drift),
            value = stringResource(R.string.rt_result_drift_value, d(m.slowDriftPercent, 1))
        )
        TremorMetricCard(
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
                        text = stringResource(R.string.rt_warn_title),
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
                    text = stringResource(R.string.rt_result_no_score),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = stringResource(R.string.rt_result_disclaimer),
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
private fun warningsFor(r: RestingTremorResult): List<Int> {
    if (r.qualityStatus == QualityStatus.VALID) return emptyList()
    val out = LinkedHashSet<Int>()
    fun add(@StringRes res: Int) { out += res }
    r.qualityIssues.forEach { issue ->
        when (issue) {
            RestingTremorQualityIssue.GROSS_MOVEMENT -> add(R.string.rt_warn_gross)
            RestingTremorQualityIssue.LONG_INTERRUPTION, RestingTremorQualityIssue.FREQUENT_INTERRUPTIONS ->
                add(R.string.rt_warn_interruptions)
            RestingTremorQualityIssue.LOW_VALID_FRAMES, RestingTremorQualityIssue.REDUCED_VALID_FRAMES ->
                add(R.string.rt_warn_valid)
            RestingTremorQualityIssue.LOW_FRAME_RATE -> add(R.string.rt_warn_fps)
            RestingTremorQualityIssue.IRREGULAR_SAMPLING -> add(R.string.rt_warn_sampling)
            RestingTremorQualityIssue.RECORDING_TOO_SHORT -> add(R.string.rt_warn_duration)
            else -> add(R.string.rt_warn_general)
        }
    }
    return out.toList()
}

@Composable
private fun TremorMetricCard(
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
