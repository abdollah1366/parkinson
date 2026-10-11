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
import com.example.parkinson.gait.GaitQualityIssue
import com.example.parkinson.gait.GaitResult
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable

/**
 * Result of one walking recording: measurements with units, data quality and warnings. No gait score, no
 * severity and no diagnosis. Plantar pressure is shown as not measured; the disclaimer is always shown.
 */
@Composable
fun GaitResultScreen(
    result: Loadable<GaitResult?>,
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
                GaitContent(value, onRepeat, onNextTest, onHome)
            }
        }
    }
}

@Composable
private fun GaitContent(
    r: GaitResult,
    onRepeat: () -> Unit,
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
            text = stringResource(R.string.gait_result_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = PersianFormat.dateTime(r.timestampEpochMs),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        QualityChip(r.qualityStatus)

        GaitMetricCard(
            title = stringResource(R.string.gait_result_duration),
            value = stringResource(
                R.string.rt_result_duration_value,
                d(m.actualDurationMs / 1000.0),
                d(m.plannedDurationMs / 1000.0)
            )
        )
        GaitMetricCard(
            title = stringResource(R.string.gait_result_valid),
            value = stringResource(R.string.rt_result_valid_value, d(m.validFramePercent, 0))
        )
        GaitMetricCard(
            title = stringResource(R.string.gait_result_steps),
            value = if (r.qualityIssues.contains(GaitQualityIssue.TOO_FEW_STEPS)) unavailable
            else PersianFormat.integer(m.stepCount),
            description = stringResource(R.string.gait_result_steps_desc)
        )
        GaitMetricCard(
            title = stringResource(R.string.gait_result_cadence),
            value = m.cadenceStepsPerMinute?.let { stringResource(R.string.gait_result_cadence_value, d(it, 1)) } ?: unavailable,
            emphasized = m.cadenceStepsPerMinute != null
        )
        GaitMetricCard(
            title = stringResource(R.string.gait_result_variability),
            value = m.stepIntervalCvPercent?.let { stringResource(R.string.gait_result_variability_value, d(it, 1)) } ?: unavailable,
            description = stringResource(R.string.gait_result_variability_desc)
        )
        GaitMetricCard(
            title = stringResource(R.string.gait_result_lean),
            value = m.trunkLeanMeanDeg?.let { stringResource(R.string.gait_result_lean_value, d(it, 1)) } ?: unavailable,
            description = stringResource(R.string.gait_result_lean_desc)
        )
        GaitMetricCard(
            title = stringResource(R.string.gait_result_arm_left),
            value = m.leftArmSwingRangeTorso?.let { stringResource(R.string.gait_result_arm_value, d(it, 2)) } ?: unavailable
        )
        GaitMetricCard(
            title = stringResource(R.string.gait_result_arm_right),
            value = m.rightArmSwingRangeTorso?.let { stringResource(R.string.gait_result_arm_value, d(it, 2)) } ?: unavailable,
            description = stringResource(R.string.gait_result_arm_desc)
        )
        GaitMetricCard(
            title = stringResource(R.string.gait_result_pressure),
            value = stringResource(R.string.gait_result_pressure_value),
            description = stringResource(R.string.gait_result_pressure_desc)
        )
        GaitMetricCard(
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

/** One patient-facing warning per kind of problem, most important first; empty for a VALID recording. */
private fun warningsFor(r: GaitResult): List<Int> {
    if (r.qualityStatus == QualityStatus.VALID) return emptyList()
    val out = LinkedHashSet<Int>()
    fun add(@StringRes res: Int) { out += res }
    r.qualityIssues.forEach { issue ->
        when (issue) {
            GaitQualityIssue.RECORDING_TOO_SHORT -> add(R.string.gait_warn_duration)
            GaitQualityIssue.LOW_VALID_FRAMES, GaitQualityIssue.REDUCED_VALID_FRAMES -> add(R.string.gait_warn_valid)
            GaitQualityIssue.LONG_GAP, GaitQualityIssue.FREQUENT_GAPS -> add(R.string.gait_warn_gaps)
            GaitQualityIssue.LOW_FRAME_RATE -> add(R.string.gait_warn_fps)
            GaitQualityIssue.TOO_FEW_STEPS -> add(R.string.gait_warn_steps)
            else -> add(R.string.rt_warn_general)
        }
    }
    return out.toList()
}

@Composable
private fun GaitMetricCard(
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
