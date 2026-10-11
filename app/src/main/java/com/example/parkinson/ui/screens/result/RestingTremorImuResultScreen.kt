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
import com.example.parkinson.norms.NormComparator
import com.example.parkinson.norms.NormPerson
import com.example.parkinson.norms.forTremorFrequency
import com.example.parkinson.tremor.ImuTremorQualityIssue
import com.example.parkinson.tremor.RestingTremorImuResult
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable

/**
 * Result of one phone-motion recording of the resting-hand test. Shows the acquisition source, the data quality
 * and only the features that were computed. Gyroscope features are shown only when the gyroscope was analysed.
 * An insufficient recording is shown as such, with its reasons, and never as a score. No diagnosis is given.
 */
@Composable
fun RestingTremorImuResultScreen(
    result: Loadable<RestingTremorImuResult?>,
    onRepeat: (SelectedHand) -> Unit,
    onHome: () -> Unit,    norms: NormComparator? = null,
    person: NormPerson = NormPerson.UNKNOWN,
) {
    when (result) {
        Loadable.Loading -> CenteredMessage(stringResource(R.string.result_loading), showProgress = true)
        is Loadable.Loaded -> {
            val value = result.value
            if (value == null) {
                CenteredMessage(stringResource(R.string.result_not_found))
            } else {
                ImuTremorContent(value, onRepeat, onHome, norms, person)
            }
        }
    }
}

@Composable
private fun ImuTremorContent(
    r: RestingTremorImuResult,
    onRepeat: (SelectedHand) -> Unit,
    onHome: () -> Unit,    norms: NormComparator?,
    person: NormPerson,
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
            text = stringResource(R.string.rt_imu_result_title),
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

        InfoCard(
            text = stringResource(R.string.rt_imu_result_intro),
            container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
            content = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        InfoCard(
            text = stringResource(
                if (r.gyroscopeUsed) R.string.rt_imu_result_modality else R.string.rt_imu_result_modality_accel_only
            ),
            container = MaterialTheme.colorScheme.surfaceVariant,
            content = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!r.gyroscopePresent) {
            InfoCard(
                text = stringResource(R.string.rt_imu_gyro_missing),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        } else if (!r.gyroscopeUsed) {
            InfoCard(
                text = stringResource(R.string.rt_imu_gyro_unusable),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
            )
        }

        if (m == null) {
            // Insufficient or invalid data: no features, the reasons instead.
            InfoCard(
                title = stringResource(R.string.rt_imu_insufficient_title),
                text = stringResource(R.string.rt_imu_insufficient_body),
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                extra = r.qualityIssues.map { stringResource(issueText(it)) },
            )
        } else {
            MetricCard(
                title = stringResource(R.string.rt_imu_result_duration),
                value = stringResource(
                    R.string.rt_imu_result_duration_value,
                    d(m.spanMs / 1000.0),
                    d(r.plannedDurationMs / 1000.0),
                ),
            )
            MetricCard(
                title = stringResource(R.string.rt_imu_result_samples),
                value = stringResource(
                    R.string.rt_imu_result_samples_value,
                    PersianFormat.integer(m.accelSamples),
                    d(m.accelRateHz, 1),
                ),
            )
            MetricCard(
                title = stringResource(R.string.rt_imu_result_dropouts),
                value = stringResource(
                    R.string.rt_imu_result_dropouts_value,
                    PersianFormat.integer(m.accelDropouts),
                    PersianFormat.integer(m.accelLongestGapMs.toInt()),
                ),
            )
            MetricCard(
                title = stringResource(R.string.rt_imu_result_accel_rms),
                value = stringResource(R.string.rt_imu_result_accel_rms_value, d(m.accelRmsMs2, 3)),
            )
            MetricCard(
                title = stringResource(R.string.rt_imu_result_gyro_rms),
                value = m.gyroRmsRadS?.let { stringResource(R.string.rt_imu_result_gyro_rms_value, d(it, 3)) } ?: unavailable,
            )
            MetricCard(
                title = stringResource(R.string.rt_imu_result_frequency),
                value = m.dominantFrequencyHz?.let { stringResource(R.string.rt_imu_result_frequency_value, d(it, 2)) }
                    ?: stringResource(R.string.rt_imu_result_frequency_none),
            )
        }

        if (r.qualityStatus == QualityStatus.LOW_QUALITY && m != null) {
            InfoCard(
                title = stringResource(R.string.rt_warn_title),
                text = "",
                container = MaterialTheme.colorScheme.errorContainer,
                content = MaterialTheme.colorScheme.onErrorContainer,
                extra = r.qualityIssues.map { stringResource(issueText(it)) },
            )
        }

        InfoCard(
            text = stringResource(R.string.rt_imu_no_score),
            container = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
            content = MaterialTheme.colorScheme.onSecondaryContainer,
            extra = listOf(stringResource(R.string.rt_result_disclaimer)),
        )

        norms?.let { ReferenceNormsCard(it.forTremorFrequency(m?.dominantFrequencyHz, person), person) }

        Spacer(modifier = Modifier.height(8.dp))
        PrimaryButton(text = stringResource(R.string.btn_repeat_assessment), onClick = { onRepeat(r.hand) })
        OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.btn_back_home))
        }
    }
}

/** Patient-facing text for one phone-motion quality finding. */
@StringRes
private fun issueText(issue: ImuTremorQualityIssue): Int = when (issue) {
    ImuTremorQualityIssue.TOO_FEW_SAMPLES -> R.string.rt_imu_issue_few_samples
    ImuTremorQualityIssue.RECORDING_TOO_SHORT -> R.string.rt_imu_issue_short
    ImuTremorQualityIssue.LOW_SAMPLING_RATE -> R.string.rt_imu_issue_rate
    ImuTremorQualityIssue.LONG_GAP -> R.string.rt_imu_issue_long_gap
    ImuTremorQualityIssue.FREQUENT_GAPS -> R.string.rt_imu_issue_gaps
    ImuTremorQualityIssue.SAMPLES_DROPPED -> R.string.rt_imu_issue_dropped
    ImuTremorQualityIssue.GYROSCOPE_UNUSABLE -> R.string.rt_imu_issue_gyro
}

@Composable
private fun InfoCard(
    text: String,
    container: androidx.compose.ui.graphics.Color,
    content: androidx.compose.ui.graphics.Color,
    title: String? = null,
    extra: List<String> = emptyList(),
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = container),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = content
                )
            }
            if (text.isNotEmpty()) {
                Text(text = text, style = MaterialTheme.typography.bodyMedium, color = content)
            }
            extra.forEach { line ->
                Text(text = line, style = MaterialTheme.typography.bodyMedium, color = content)
            }
        }
    }
}

@Composable
private fun MetricCard(title: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.medium
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}
