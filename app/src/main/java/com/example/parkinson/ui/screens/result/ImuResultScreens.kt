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
import com.example.parkinson.imu.ImuComparison
import com.example.parkinson.imu.ImuGaitResult
import com.example.parkinson.imu.ImuSitToStandResult
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable

/** Sit-to-stand result from the phone sensors: transfers, timing, data quality and the earlier attempt. */
@Composable
fun ImuSitToStandResultScreen(
    result: Loadable<ImuComparison<ImuSitToStandResult>?>,
    onRepeat: () -> Unit,
    onHome: () -> Unit,
) {
    when (result) {
        Loadable.Loading -> CenteredMessage(stringResource(R.string.result_loading), showProgress = true)
        is Loadable.Loaded -> {
            val value = result.value
            if (value == null) CenteredMessage(stringResource(R.string.result_not_found)) else {
                val r = value.current
                val previous = value.previous
                Column(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.imu_sts_result_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(PersianFormat.dateTime(r.timestampEpochMs), style = MaterialTheme.typography.titleMedium)
                    QualityChip(r.qualityStatus)
                    ResultCard(stringResource(R.string.imu_result_total), stringResource(R.string.imu_seconds, PersianFormat.decimal(r.totalTimeMs / 1000.0, 2)))
                    ResultCard(
                        stringResource(R.string.imu_result_transfers),
                        stringResource(R.string.imu_result_transfers_value, PersianFormat.integer(r.repetitions.size), PersianFormat.integer(r.targetRepetitions)),
                    )
                    r.repetitions.forEach { rep ->
                        ResultCard(
                            stringResource(R.string.imu_result_transfer_title, PersianFormat.integer(rep.index)),
                            stringResource(
                                R.string.imu_result_transfer_phases,
                                PersianFormat.decimal(rep.durationMs / 1000.0, 2),
                                PersianFormat.decimal(rep.standingUpMs / 1000.0, 2),
                                PersianFormat.decimal(rep.sittingDownMs / 1000.0, 2),
                            ),
                        )
                    }
                    ResultCard(stringResource(R.string.imu_result_variability), r.repetitionCvPercent?.let { stringResource(R.string.imu_percent, PersianFormat.decimal(it, 1)) } ?: stringResource(R.string.imu_not_computed))
                    ResultCard(stringResource(R.string.imu_result_pause), r.meanPauseMs?.let { stringResource(R.string.imu_seconds, PersianFormat.decimal(it / 1000.0, 2)) } ?: stringResource(R.string.imu_not_computed))
                    ResultCard(stringResource(R.string.imu_result_coverage), stringResource(R.string.imu_percent, PersianFormat.decimal(r.coveragePercent, 0)))
                    ResultCard(stringResource(R.string.imu_result_rates), stringResource(R.string.imu_rates_value, PersianFormat.decimal(r.data.accelerometerRateHz, 1), PersianFormat.decimal(r.data.gyroscopeRateHz, 1)))
                    ResultCard(stringResource(R.string.imu_result_rejected), PersianFormat.integer(r.rejections.size))
                    if (previous != null) {
                        ResultCard(
                            stringResource(R.string.imu_compare_title),
                            stringResource(R.string.imu_compare_value, PersianFormat.decimal((r.totalTimeMs - previous.totalTimeMs) / 1000.0, 2)),
                        )
                        Text(stringResource(R.string.imu_compare_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    NoScoreCard()
                    Spacer(modifier = Modifier.height(8.dp))
                    PrimaryButton(text = stringResource(R.string.btn_repeat_assessment), onClick = onRepeat)
                    OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_back_home)) }
                }
            }
        }
    }
}

/** Walking result from the phone sensors: steps, cadence, variability, walking time and data quality. */
@Composable
fun ImuGaitResultScreen(
    result: Loadable<ImuComparison<ImuGaitResult>?>,
    onRepeat: () -> Unit,
    onNextTest: () -> Unit,
    onHome: () -> Unit,
) {
    when (result) {
        Loadable.Loading -> CenteredMessage(stringResource(R.string.result_loading), showProgress = true)
        is Loadable.Loaded -> {
            val value = result.value
            if (value == null) CenteredMessage(stringResource(R.string.result_not_found)) else {
                val r = value.current
                val previous = value.previous
                val notComputed = stringResource(R.string.imu_not_computed)
                Column(
                    modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        text = stringResource(R.string.imu_gait_result_title),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(PersianFormat.dateTime(r.timestampEpochMs), style = MaterialTheme.typography.titleMedium)
                    QualityChip(r.qualityStatus)
                    ResultCard(stringResource(R.string.imu_gait_steps), PersianFormat.integer(r.steps))
                    ResultCard(stringResource(R.string.imu_gait_cadence), r.cadenceStepsPerMinute?.let { stringResource(R.string.imu_gait_cadence_value, PersianFormat.decimal(it, 1)) } ?: notComputed)
                    ResultCard(stringResource(R.string.imu_gait_variability), r.stepIntervalCvPercent?.let { stringResource(R.string.imu_percent, PersianFormat.decimal(it, 1)) } ?: notComputed)
                    ResultCard(stringResource(R.string.imu_gait_walking_time), stringResource(R.string.imu_seconds, PersianFormat.decimal(r.validWalkingMs / 1000.0, 1)))
                    ResultCard(stringResource(R.string.imu_gait_bouts), PersianFormat.integer(r.bouts))
                    ResultCard(stringResource(R.string.imu_gait_turning), stringResource(R.string.imu_seconds, PersianFormat.decimal(r.turningMs / 1000.0, 1)))
                    ResultCard(stringResource(R.string.imu_result_coverage), stringResource(R.string.imu_percent, PersianFormat.decimal(r.coveragePercent, 0)))
                    ResultCard(stringResource(R.string.imu_gait_not_measured_title), stringResource(R.string.imu_gait_not_measured))
                    if (previous != null && previous.cadenceStepsPerMinute != null && r.cadenceStepsPerMinute != null) {
                        ResultCard(
                            stringResource(R.string.imu_compare_title),
                            stringResource(R.string.imu_compare_cadence, PersianFormat.decimal(r.cadenceStepsPerMinute - previous.cadenceStepsPerMinute, 1)),
                        )
                        Text(stringResource(R.string.imu_compare_desc), style = MaterialTheme.typography.bodySmall)
                    }
                    NoScoreCard()
                    Spacer(modifier = Modifier.height(8.dp))
                    PrimaryButton(text = stringResource(R.string.btn_repeat_assessment), onClick = onRepeat)
                    if (r.qualityStatus == QualityStatus.VALID || r.qualityStatus == QualityStatus.LOW_QUALITY) {
                        OutlinedButton(onClick = onNextTest, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_next_test)) }
                    }
                    OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_back_home)) }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(title: String, value: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun NoScoreCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.rt_result_no_score), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.rt_result_disclaimer), style = MaterialTheme.typography.bodyMedium)
        }
    }
}
