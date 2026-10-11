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
import com.example.parkinson.speech.MetricKind
import com.example.parkinson.speech.SpeechComparison
import com.example.parkinson.speech.SpeechMetric
import com.example.parkinson.speech.SpeechQualityIssue
import com.example.parkinson.speech.SpeechResult
import com.example.parkinson.speech.SpeechValue
import com.example.parkinson.speech.UnavailableReason
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable

/**
 * Speech result report: measured values, derived values, unavailable values with their reasons, the recording's
 * quality, and a comparison with the previous session of the same task. Descriptive only: no diagnosis, no
 * normative reference and no severity.
 */
@Composable
fun SpeechResultScreen(
    result: Loadable<SpeechComparison?>,
    onRepeat: () -> Unit,
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
                SpeechContent(value, onRepeat, onNextTest, onHome)
            }
        }
    }
}

@Composable
private fun SpeechContent(
    comparison: SpeechComparison,
    onRepeat: () -> Unit,
    onNextTest: () -> Unit,
    onHome: () -> Unit,
) {
    val r = comparison.current
    val previous = comparison.previous
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
            text = stringResource(R.string.speech_result_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = stringResource(R.string.speech_result_task, stringResource(titleOf(r.task))),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = PersianFormat.dateTime(r.timestampEpochMs),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        QualityChip(r.qualityStatus)
        Text(
            text = stringResource(
                if (r.qualityStatus == QualityStatus.VALID) R.string.speech_quality_valid else R.string.speech_quality_low
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = stringResource(
                R.string.speech_result_sample_rate,
                PersianFormat.integer(r.sampleRateHz),
                PersianFormat.integer(r.requestedSampleRateHz),
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (r.qualityIssues.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.medium,
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.speech_section_quality),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                    r.qualityIssues.forEach { issue ->
                        Text(
                            text = stringResource(issueRes(issue)),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        SectionTitle(stringResource(R.string.speech_section_measured))
        measuredOf(r, MetricKind.MEASURED).forEach { (metric, value) ->
            MetricCard(stringResource(labelOf(metric)), formatValue(metric, value))
        }

        SectionTitle(stringResource(R.string.speech_section_derived))
        measuredOf(r, MetricKind.DERIVED).forEach { (metric, value) ->
            MetricCard(stringResource(labelOf(metric)), formatValue(metric, value))
        }

        SectionTitle(stringResource(R.string.speech_section_unavailable))
        Text(
            text = stringResource(R.string.speech_unavailable_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        r.metrics.entries
            .filter { it.value is SpeechValue.Unavailable && (it.value as SpeechValue.Unavailable).reason != UnavailableReason.NOT_APPLICABLE_TO_TASK }
            .forEach { (metric, value) ->
                MetricCard(
                    title = stringResource(labelOf(metric)),
                    value = unavailable,
                    description = stringResource(reasonRes((value as SpeechValue.Unavailable).reason)),
                )
            }

        if (previous != null) {
            SectionTitle(stringResource(R.string.speech_section_comparison))
            Text(
                text = stringResource(R.string.speech_compare_desc),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SpeechMetric.entries.filter { it.kind == MetricKind.MEASURED || it.kind == MetricKind.DERIVED }.forEach { metric ->
                val now = r.measured(metric)
                val before = previous.measured(metric)
                if (now != null && before != null) {
                    MetricCard(
                        title = stringResource(labelOf(metric)),
                        value = stringResource(
                            R.string.speech_previous_value,
                            formatNumber(metric, now),
                            formatNumber(metric, before),
                        ),
                    )
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
            shape = MaterialTheme.shapes.medium,
        ) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.speech_interpretation), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(stringResource(R.string.speech_result_no_score), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                if (r.qualityStatus != QualityStatus.VALID) {
                    Text(stringResource(R.string.speech_repeat_advice), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                }
                Text(stringResource(R.string.speech_disclaimer), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        PrimaryButton(text = stringResource(R.string.btn_repeat_assessment), onClick = onRepeat)
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

private fun measuredOf(r: SpeechResult, kind: MetricKind): List<Pair<SpeechMetric, Double>> =
    SpeechMetric.entries.filter { it.kind == kind }.mapNotNull { metric ->
        r.measured(metric)?.let { metric to it }
    }

/** Number text for a metric: durations in seconds, counts as integers, the rest with two decimals. */
private fun formatNumber(metric: SpeechMetric, value: Double): String = when (metric) {
    SpeechMetric.DURATION_MS, SpeechMetric.VOICED_DURATION_MS, SpeechMetric.SPEECH_SPAN_MS ->
        PersianFormat.decimal(value / 1000.0, 2)
    SpeechMetric.PAUSE_COUNT, SpeechMetric.EVENT_COUNT -> PersianFormat.integer(value.toInt())
    SpeechMetric.PAUSE_MEAN_MS -> PersianFormat.integer(value.toInt())
    else -> PersianFormat.decimal(value, 2)
}

private fun formatValue(metric: SpeechMetric, value: Double): String = formatNumber(metric, value)

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() },
    )
}

@Composable
private fun MetricCard(title: String, value: String, description: String? = null) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
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

@StringRes
private fun titleOf(task: com.example.parkinson.speech.SpeechTask): Int = when (task) {
    com.example.parkinson.speech.SpeechTask.SUSTAINED_VOWEL -> R.string.speech_task_vowel_title
    com.example.parkinson.speech.SpeechTask.REPEATED_SYLLABLE -> R.string.speech_task_syllable_title
    com.example.parkinson.speech.SpeechTask.READING -> R.string.speech_task_reading_title
    com.example.parkinson.speech.SpeechTask.SPONTANEOUS -> R.string.speech_task_spontaneous_title
}

@StringRes
private fun labelOf(metric: SpeechMetric): Int = when (metric) {
    SpeechMetric.DURATION_MS -> R.string.speech_metric_DURATION_MS
    SpeechMetric.VOICED_DURATION_MS -> R.string.speech_metric_VOICED_DURATION_MS
    SpeechMetric.VOICED_PERCENT -> R.string.speech_metric_VOICED_PERCENT
    SpeechMetric.F0_MEDIAN_HZ -> R.string.speech_metric_F0_MEDIAN_HZ
    SpeechMetric.F0_SD_SEMITONES -> R.string.speech_metric_F0_SD_SEMITONES
    SpeechMetric.INTENSITY_MEDIAN_DBFS -> R.string.speech_metric_INTENSITY_MEDIAN_DBFS
    SpeechMetric.INTENSITY_SD_DB -> R.string.speech_metric_INTENSITY_SD_DB
    SpeechMetric.HNR_DB -> R.string.speech_metric_HNR_DB
    SpeechMetric.JITTER_PERCENT -> R.string.speech_metric_JITTER_PERCENT
    SpeechMetric.SHIMMER_PERCENT -> R.string.speech_metric_SHIMMER_PERCENT
    SpeechMetric.SPEECH_SPAN_MS -> R.string.speech_metric_SPEECH_SPAN_MS
    SpeechMetric.VOICED_OF_ACTIVE_PERCENT -> R.string.speech_metric_VOICED_OF_ACTIVE_PERCENT
    SpeechMetric.PAUSE_COUNT -> R.string.speech_metric_PAUSE_COUNT
    SpeechMetric.PAUSE_MEAN_MS -> R.string.speech_metric_PAUSE_MEAN_MS
    SpeechMetric.LONG_PAUSE_RATIO_PERCENT -> R.string.speech_metric_LONG_PAUSE_RATIO_PERCENT
    SpeechMetric.EVENT_COUNT -> R.string.speech_metric_EVENT_COUNT
    SpeechMetric.EVENT_RATE_HZ -> R.string.speech_metric_EVENT_RATE_HZ
    SpeechMetric.EVENT_INTERVAL_CV_PERCENT -> R.string.speech_metric_EVENT_INTERVAL_CV_PERCENT
    SpeechMetric.SPEAKING_RATE_WPM -> R.string.speech_metric_SPEAKING_RATE_WPM
    SpeechMetric.ARTICULATION_RATE -> R.string.speech_metric_ARTICULATION_RATE
}

@StringRes
private fun reasonRes(reason: UnavailableReason): Int = when (reason) {
    UnavailableReason.NOT_ENOUGH_VOICED_FRAMES -> R.string.speech_reason_NOT_ENOUGH_VOICED_FRAMES
    UnavailableReason.NO_SPEECH_DETECTED -> R.string.speech_reason_NO_SPEECH_DETECTED
    UnavailableReason.NOT_ENOUGH_EVENTS -> R.string.speech_reason_NOT_ENOUGH_EVENTS
    UnavailableReason.LOW_QUALITY_SIGNAL -> R.string.speech_reason_LOW_QUALITY_SIGNAL
    UnavailableReason.NO_TRANSCRIPTION_SUPPORT -> R.string.speech_reason_NO_TRANSCRIPTION_SUPPORT
    UnavailableReason.CYCLE_MEASUREMENT_NOT_RELIABLE -> R.string.speech_reason_CYCLE_MEASUREMENT_NOT_RELIABLE
    // Not shown: such metrics are filtered out before display (see SpeechContent).
    UnavailableReason.NOT_APPLICABLE_TO_TASK -> R.string.speech_reason_NO_SPEECH_DETECTED
}

@StringRes
private fun issueRes(issue: SpeechQualityIssue): Int = when (issue) {
    SpeechQualityIssue.NO_SAMPLES -> R.string.speech_issue_NO_SAMPLES
    SpeechQualityIssue.INVALID_SAMPLE_RATE -> R.string.speech_issue_INVALID_SAMPLE_RATE
    SpeechQualityIssue.SAMPLE_RATE_BELOW_MINIMUM -> R.string.speech_issue_SAMPLE_RATE_BELOW_MINIMUM
    SpeechQualityIssue.CLIPPING -> R.string.speech_issue_CLIPPING
    SpeechQualityIssue.TRUNCATED -> R.string.speech_issue_TRUNCATED
    SpeechQualityIssue.SILENCE -> R.string.speech_issue_SILENCE
    SpeechQualityIssue.UNUSABLE_SNR -> R.string.speech_issue_UNUSABLE_SNR
    SpeechQualityIssue.CLIPPING_WARNING -> R.string.speech_issue_CLIPPING_WARNING
    SpeechQualityIssue.LOW_SNR -> R.string.speech_issue_LOW_SNR
}
