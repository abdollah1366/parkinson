package com.example.parkinson.ui.screens.result

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
import com.example.parkinson.sts.SitToStandComparison
import com.example.parkinson.sts.SitToStandIssue
import com.example.parkinson.sts.SitToStandResult
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable

/**
 * Result of one Five Times Sit-to-Stand attempt. Measured values, derived values and the comparison with the
 * previous attempt are shown in separate sections. No score, severity or diagnosis. The comparison describes
 * the difference between two sessions only; it is not evidence of a treatment effect.
 */
@Composable
fun SitToStandResultScreen(
    result: Loadable<SitToStandComparison?>,
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
                SitToStandContent(value, onRepeat, onNextTest, onHome)
            }
        }
    }
}

@Composable
private fun SitToStandContent(
    comparison: SitToStandComparison,
    onRepeat: () -> Unit,
    onNextTest: () -> Unit,
    onHome: () -> Unit,
) {
    val r = comparison.current
    val previous = comparison.previous
    fun d(v: Double, digits: Int = 1) = PersianFormat.decimal(v, digits)
    fun seconds(ms: Long) = d(ms / 1000.0, 1)
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
            text = stringResource(R.string.sts_result_title),
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

        SectionTitle(stringResource(R.string.sts_section_measured))
        StsMetricCard(
            title = stringResource(R.string.sts_result_total),
            value = stringResource(R.string.sts_result_seconds, seconds(r.totalTimeMs)),
            description = stringResource(R.string.sts_result_total_desc)
        )
        StsMetricCard(
            title = stringResource(R.string.sts_result_reps),
            value = stringResource(
                R.string.sts_result_reps_value,
                PersianFormat.integer(r.repetitions.size),
                PersianFormat.integer(r.targetRepetitions)
            )
        )
        r.repetitions.forEach { rep ->
            StsMetricCard(
                title = stringResource(R.string.sts_result_rep_title, PersianFormat.integer(rep.index)),
                value = stringResource(R.string.sts_result_seconds, seconds(rep.durationMs)),
                description = stringResource(
                    R.string.sts_result_rep_phases,
                    seconds(rep.standingUpMs),
                    seconds(rep.sittingDownMs),
                    d(rep.peakHipRiseShinLengths, 2),
                    d(rep.peakKneeExtensionDeg, 0)
                )
            )
        }

        SectionTitle(stringResource(R.string.sts_section_derived))
        StsMetricCard(
            title = stringResource(R.string.sts_result_mean_rep),
            value = r.meanRepetitionMs?.let { stringResource(R.string.sts_result_seconds, d(it / 1000.0, 2)) } ?: unavailable,
            description = stringResource(R.string.sts_result_derived_desc)
        )
        StsMetricCard(
            title = stringResource(R.string.sts_result_variability),
            value = r.repetitionCvPercent?.let { stringResource(R.string.sts_result_percent, d(it, 1)) } ?: unavailable,
            description = stringResource(R.string.sts_result_variability_desc)
        )
        StsMetricCard(
            title = stringResource(R.string.sts_result_pause),
            value = r.meanInterRepetitionMs?.let { stringResource(R.string.sts_result_seconds, d(it / 1000.0, 2)) } ?: unavailable,
            description = stringResource(R.string.sts_result_pause_desc)
        )

        SectionTitle(stringResource(R.string.sts_section_tracking))
        StsMetricCard(
            title = stringResource(R.string.sts_result_valid),
            value = stringResource(R.string.rt_result_valid_value, d(r.validFramePercent, 0)),
            description = stringResource(R.string.sts_result_invalid_desc, PersianFormat.integer(r.invalidFrames))
        )
        StsMetricCard(
            title = stringResource(R.string.sts_result_partial),
            value = PersianFormat.integer(r.partialAttempts),
            description = stringResource(R.string.sts_result_partial_desc)
        )
        if (r.invalidFramesByIssue.isNotEmpty()) {
            val reasons = r.invalidFramesByIssue.entries.map { (issue, count) ->
                issueLabel(issue) + ": " + PersianFormat.integer(count)
            }
            StsMetricCard(
                title = stringResource(R.string.sts_result_reasons),
                value = reasons.joinToString("  •  ")
            )
        }

        if (previous != null) {
            SectionTitle(stringResource(R.string.sts_section_comparison))
            StsMetricCard(
                title = stringResource(R.string.sts_compare_total),
                value = stringResource(
                    R.string.sts_compare_delta,
                    signedSeconds(r.totalTimeMs - previous.totalTimeMs)
                ),
                description = stringResource(R.string.sts_compare_desc)
            )
        }

        if (r.qualityStatus == QualityStatus.LOW_QUALITY) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.sts_warn_low_quality),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
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

@Composable
private fun issueLabel(issue: SitToStandIssue): String = stringResource(
    when (issue) {
        SitToStandIssue.NO_POSE -> R.string.sts_issue_no_pose
        SitToStandIssue.ERROR -> R.string.sts_issue_error
        SitToStandIssue.LOW_VISIBILITY -> R.string.sts_issue_low_visibility
        SitToStandIssue.OUT_OF_FRAME -> R.string.sts_issue_out_of_frame
        SitToStandIssue.TOO_SMALL -> R.string.sts_issue_too_small
    }
)

/** Signed seconds for the comparison, computed from milliseconds. */
private fun signedSeconds(deltaMs: Long): String {
    val sign = if (deltaMs > 0) "+" else if (deltaMs < 0) "−" else ""
    return sign + PersianFormat.decimal(kotlin.math.abs(deltaMs) / 1000.0, 1)
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 8.dp).semantics { heading() }
    )
}

@Composable
private fun StsMetricCard(
    title: String,
    value: String,
    description: String? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
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
