package com.example.parkinson.ui.screens.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.stability.FrequencyStatus
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.components.qualityLabel
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable

@Composable
fun HandStabilityResultScreen(
    result: Loadable<HandStabilityResult?>,
    onRepeat: (SelectedHand) -> Unit,
    onNextTest: () -> Unit,
    onHome: () -> Unit,
) {
    when (result) {
        Loadable.Loading -> CenteredMessage(stringResource(R.string.result_loading), showProgress = true)
        is Loadable.Loaded -> {
            val value = result.value
            if (value == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(stringResource(R.string.result_not_found), style = MaterialTheme.typography.titleLarge)
                    Spacer(modifier = Modifier.height(24.dp))
                    PrimaryButton(text = stringResource(R.string.btn_back_home), onClick = onHome)
                }
            } else {
                StabilityContent(value, onRepeat, onNextTest, onHome)
            }
        }
    }
}

@Composable
private fun StabilityContent(
    r: HandStabilityResult,
    onRepeat: (SelectedHand) -> Unit,
    onNextTest: () -> Unit,
    onHome: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    fun d(v: Double, digits: Int = 1) = PersianFormat.decimal(v, digits)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.hs_result_title),
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

        val index = r.stabilityIndex
        MetricCard(
            title = stringResource(R.string.hs_result_overall),
            value = index?.let { stringResource(R.string.hs_result_overall_value, PersianFormat.integer(it.total)) }
                ?: stringResource(R.string.hs_result_overall_unavailable),
            description = stringResource(R.string.hs_result_overall_desc),
            emphasized = index != null
        )
        MetricCard(
            title = stringResource(R.string.hs_result_movement),
            value = stringResource(R.string.hs_result_movement_value, d(r.accDynamicRms, 2)),
            description = stringResource(R.string.hs_result_movement_desc, d(r.rotationRangeDeg))
        )
        MetricCard(
            title = stringResource(R.string.hs_result_oscillation),
            value = stringResource(R.string.hs_result_oscillation_value, d(r.gyroDynamicRms)),
            description = r.dominantFrequencyHz?.takeIf { r.frequencyStatus == FrequencyStatus.REPORTED }?.let {
                stringResource(R.string.hs_result_oscillation_freq, d(it))
            } ?: stringResource(R.string.hs_result_oscillation_no_freq)
        )
        MetricCard(
            title = stringResource(R.string.hs_result_quality),
            value = stringResource(
                R.string.hs_result_quality_value,
                qualityLabel(r.qualityStatus),
                PersianFormat.integer(r.qualityScore),
                PersianFormat.integer(Math.round(minOf(r.accSamplingRateHz, r.gyroSamplingRateHz)).toInt())
            )
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = MaterialTheme.shapes.large
        ) {
            Text(
                text = stringResource(R.string.hs_result_disclaimer),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(16.dp)
            )
        }

        TextButton(
            onClick = { showDetails = !showDetails },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(
                text = stringResource(if (showDetails) R.string.result_details_hide else R.string.result_details_show),
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (showDetails) StabilityDetails(r)

        Spacer(modifier = Modifier.height(4.dp))
        PrimaryButton(text = stringResource(R.string.btn_repeat_assessment), onClick = { onRepeat(r.hand) })
        OutlinedButton(
            onClick = onNextTest,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(stringResource(R.string.btn_next_test), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        TextButton(
            onClick = onHome,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.btn_back_home), style = MaterialTheme.typography.titleMedium)
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

/** Raw measurements for a clinician. Units are explicit. */
@Composable
private fun StabilityDetails(r: HandStabilityResult) {
    fun d(v: Double?, digits: Int = 2) = v?.let { PersianFormat.decimal(it, digits) } ?: "—"
    val i = r.stabilityIndex
    DetailsTable(
        listOf(
            "مدت ثبت هم‌زمان" to "${d(r.recordingDurationMs / 1000.0, 1)} ثانیه",
            "نمونه‌ها (شتاب‌سنج / ژیروسکوپ)" to "${PersianFormat.integer(r.accSampleCount)} / ${PersianFormat.integer(r.gyroSampleCount)}",
            "نرخ نمونه‌برداری واقعی" to "${d(r.accSamplingRateHz, 1)} / ${d(r.gyroSamplingRateHz, 1)} هرتز",
            "میانه فاصله نمونه‌ها" to "${d(r.accMedianIntervalMs, 1)} / ${d(r.gyroMedianIntervalMs, 1)} میلی‌ثانیه",
            "نمونه‌های معتبر" to "${d(r.validSamplePercent, 1)}٪",
            "قطعی داده" to "${PersianFormat.integer(r.dropoutCount)} بار، ${PersianFormat.integer(r.dropoutDurationMs)} میلی‌ثانیه",
            "طولانی‌ترین فاصله" to "${PersianFormat.integer(r.longestGapMs)} میلی‌ثانیه",
            "کامل بودن ثبت" to "${d(r.completenessPercent, 1)}٪",
            "اندازه شتاب: میانگین / انحراف معیار" to "${d(r.accMagnitudeMean)} / ${d(r.accMagnitudeSd, 3)} m/s²",
            "واریانس / دامنه اندازه شتاب" to "${d(r.accMagnitudeVariance, 4)} / ${d(r.accMagnitudeRange, 3)}",
            "شتاب حرکتی (RMS) / واریانس" to "${d(r.accDynamicRms, 3)} m/s² / ${d(r.accDynamicVariance, 4)}",
            "سرعت زاویه‌ای: میانگین / RMS" to "${d(r.gyroMagnitudeMean)} / ${d(r.gyroMagnitudeRms)} °/s",
            "سرعت زاویه‌ای: انحراف معیار / بیشینه" to "${d(r.gyroMagnitudeSd)} / ${d(r.gyroMagnitudeMax)} °/s",
            "واریانس سرعت زاویه‌ای" to d(r.gyroMagnitudeVariance, 3),
            "نوسان چرخشی (RMS)" to "${d(r.gyroDynamicRms)} °/s",
            "دامنه چرخش" to "${d(r.rotationRangeDeg)} درجه",
            "تغییر جهت گوشی" to "${d(r.tiltChangeDeg, 1)} درجه",
            "بسامد غالب" to (r.dominantFrequencyHz?.let { "${d(it, 1)} هرتز" } ?: "— (${r.frequencyStatus.name})"),
            "سهم توان ۳ تا ۱۲ هرتز" to (r.oscillationBandPowerPercent?.let { "${d(it, 0)}٪" } ?: "—"),
            "مسائل کیفیت" to (r.qualityIssues.joinToString { it.name }.ifEmpty { "—" }),
            "مؤلفه‌های شاخص" to (i?.let {
                "چرخش ${PersianFormat.integer(it.rotationComponent)}، شتاب ${PersianFormat.integer(it.accelerationComponent)}"
            } ?: "—"),
            "نسخه الگوریتم" to r.algorithmVersion,
            "نسخه امتیازدهی" to r.scoringVersion
        )
    )
}
