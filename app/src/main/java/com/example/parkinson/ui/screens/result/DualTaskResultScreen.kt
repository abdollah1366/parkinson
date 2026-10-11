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
import com.example.parkinson.dualtask.DualTaskDomain
import com.example.parkinson.dualtask.DualTaskResult
import com.example.parkinson.dualtask.DualTaskSign
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable

/** Motor-cognitive result: overall score, the three domains, the signs and both walks side by side. */
@Composable
fun DualTaskResultScreen(
    result: Loadable<DualTaskResult?>,
    onRepeat: () -> Unit,
    onNextTest: () -> Unit,
    onHome: () -> Unit,
) {
    when (result) {
        Loadable.Loading -> CenteredMessage(stringResource(R.string.result_loading), showProgress = true)
        is Loadable.Loaded -> {
            val r = result.value
            if (r == null) {
                CenteredMessage(stringResource(R.string.result_not_found))
                return
            }
            val notComputed = stringResource(R.string.imu_not_computed)
            val score = r.score
            Column(
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
                    .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    stringResource(R.string.dt_result_title),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(PersianFormat.dateTime(r.timestampEpochMs), style = MaterialTheme.typography.titleMedium)
                QualityChip(r.qualityStatus)

                if (score != null) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        shape = MaterialTheme.shapes.large,
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.dt_score_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            Text(
                                score.overall?.let { stringResource(R.string.gait_score_value, PersianFormat.integer(it)) }
                                    ?: stringResource(R.string.gait_score_unavailable),
                                style = MaterialTheme.typography.headlineMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold,
                            )
                            Text(stringResource(R.string.dt_score_note), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    score.domains.forEach { (domain, value) ->
                        val title = when (domain) {
                            DualTaskDomain.DUAL_TASK_GAIT -> R.string.dt_domain_gait
                            DualTaskDomain.ANSWERS -> R.string.dt_domain_answers
                            DualTaskDomain.CADENCE_COST -> R.string.dt_domain_cost
                        }
                        ResultCard(stringResource(title), stringResource(R.string.gait_domain_score, PersianFormat.integer(value)))
                    }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                        shape = MaterialTheme.shapes.medium,
                    ) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(stringResource(R.string.gait_signs_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                            if (score.signs.isEmpty()) Text(stringResource(R.string.gait_signs_none), style = MaterialTheme.typography.bodyMedium)
                            score.signs.forEach { sign ->
                                val text = when (sign) {
                                    DualTaskSign.SLOW_ANSWERS -> R.string.dt_sign_slow_answers
                                    DualTaskSign.LARGE_GAIT_COST -> R.string.dt_sign_gait_cost
                                    DualTaskSign.VARIABILITY_RISES -> R.string.dt_sign_variability
                                    DualTaskSign.LONG_PAUSES -> R.string.dt_sign_pauses
                                    DualTaskSign.PARKINSONIAN_PATTERN -> R.string.dt_sign_parkinsonian
                                }
                                Text("• " + stringResource(text), style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                fun cadence(v: Double?) = v?.let { PersianFormat.decimal(it, 1) }
                fun cv(v: Double?) = v?.let { PersianFormat.decimal(it, 1) }
                ResultCard(
                    stringResource(R.string.dt_cadence),
                    stringResource(R.string.dt_two_walks, cadence(r.single.cadence) ?: notComputed, cadence(r.dual.cadence) ?: notComputed),
                )
                r.cadenceCostPercent?.let {
                    ResultCard(stringResource(R.string.dt_cadence_cost), stringResource(R.string.imu_percent, PersianFormat.decimal(it, 1)))
                }
                ResultCard(
                    stringResource(R.string.dt_variability),
                    stringResource(R.string.dt_two_walks, cv(r.single.strideCvPercent) ?: notComputed, cv(r.dual.strideCvPercent) ?: notComputed),
                )
                ResultCard(
                    stringResource(R.string.dt_answers),
                    when {
                        r.answers != null -> stringResource(
                            R.string.dt_answers_value,
                            PersianFormat.integer(r.answers),
                            PersianFormat.integer(r.startNumber),
                            PersianFormat.integer(r.lastNumber ?: r.startNumber),
                        )
                        else -> notComputed
                    },
                )
                if (r.answersExact == false) NoteCard(stringResource(R.string.dt_answers_inexact))
                r.answersPerMinute?.let {
                    ResultCard(stringResource(R.string.dt_answers_rate), stringResource(R.string.dt_answers_rate_value, PersianFormat.decimal(it, 1)))
                }
                if (r.spokenUtterances != null) {
                    ResultCard(
                        stringResource(R.string.dt_mic_count),
                        stringResource(
                            R.string.dt_mic_count_value,
                            PersianFormat.integer(r.spokenUtterances),
                            PersianFormat.integer(r.longPauses ?: 0),
                        ),
                    )
                }
                ResultCard(stringResource(R.string.imu_result_coverage), stringResource(R.string.imu_percent, PersianFormat.decimal(r.coveragePercent, 0)))
                NoteCard(stringResource(R.string.dt_disclaimer))
                Spacer(modifier = Modifier.height(8.dp))
                PrimaryButton(text = stringResource(R.string.btn_repeat_assessment), onClick = onRepeat)
                OutlinedButton(onClick = onNextTest, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_next_test)) }
                OutlinedButton(onClick = onHome, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.btn_back_home)) }
            }
        }
    }
}
