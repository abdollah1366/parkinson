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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.pronation.MetricTrend
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.pronation.TrendDirection
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.components.qualityLabel
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable
import kotlin.math.abs

@Composable
fun PronationSupinationResultScreen(
    result: Loadable<PronationSupinationResult?>,
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
                PronationContent(value, onRepeat, onNextTest, onHome)
            }
        }
    }
}

@Composable
private fun PronationContent(
    r: PronationSupinationResult,
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
            text = stringResource(R.string.ps_result_title),
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

        MetricCard(
            title = stringResource(R.string.ps_result_cycles),
            value = stringResource(R.string.ps_result_cycles_value, PersianFormat.integer(r.cycleCount)),
            description = stringResource(R.string.ps_result_cycles_desc)
        )
        MetricCard(
            title = stringResource(R.string.ps_result_speed),
            value = stringResource(R.string.ps_result_speed_value, d(r.cycleRateHz, 2)),
            description = r.angularVelocityPeak?.let { stringResource(R.string.ps_result_speed_desc, d(it, 0)) }
        )
        MetricCard(
            title = stringResource(R.string.ps_result_rhythm),
            value = r.medianCycleDurationMs?.let { stringResource(R.string.ps_result_rhythm_value, d(it / 1000.0, 2)) }
                ?: stringResource(R.string.ps_trend_unavailable),
            description = r.cycleVariability?.let { stringResource(R.string.ps_result_rhythm_desc, d(it, 0)) }
                ?: stringResource(R.string.ps_result_rhythm_unavailable)
        )
        MetricCard(
            title = stringResource(R.string.ps_result_amplitude),
            value = r.movementAmplitude?.let { stringResource(R.string.ps_result_amplitude_value, d(it, 0)) }
                ?: stringResource(R.string.ps_trend_unavailable),
            description = r.amplitudeVariability?.let { stringResource(R.string.ps_result_amplitude_desc, d(it, 0)) }
        )
        MetricCard(
            title = stringResource(R.string.ps_result_consistency),
            value = r.movementConsistency?.let { stringResource(R.string.ps_result_consistency_value, d(it, 0)) }
                ?: stringResource(R.string.ps_trend_unavailable),
            description = stringResource(
                R.string.ps_result_consistency_desc,
                PersianFormat.integer(r.pauseCount),
                d(r.pauseDurationMs / 1000.0, 1)
            )
        )
        MetricCard(
            title = stringResource(R.string.ps_result_trend),
            value = listOf(
                stringResource(R.string.ps_trend_amplitude, trendText(r.trend?.amplitudeDeg)),
                stringResource(R.string.ps_trend_velocity, trendText(r.trend?.peakVelocityDegS)),
                stringResource(R.string.ps_trend_duration, trendText(r.trend?.cycleDurationMs))
            ).joinToString("\n"),
            description = stringResource(R.string.ps_result_trend_desc)
        )
        MetricCard(
            title = stringResource(R.string.ps_result_quality),
            value = stringResource(
                R.string.ps_result_quality_value,
                qualityLabel(r.qualityStatus),
                PersianFormat.integer(r.qualityScore),
                PersianFormat.integer(Math.round(r.effectiveSamplingRate).toInt())
            )
        )
        IndexCard(r)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            shape = MaterialTheme.shapes.large
        ) {
            Text(
                text = stringResource(R.string.result_disclaimer),
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
        if (showDetails) PronationDetails(r)

        Spacer(modifier = Modifier.height(4.dp))
        PrimaryButton(text = stringResource(R.string.ps_btn_retry), onClick = { onRepeat(r.hand) })
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

/** "شاخص عملکرد حرکتی" with its research / non-diagnostic label. */
@Composable
private fun IndexCard(r: PronationSupinationResult) {
    val score = r.performanceScore
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (score != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.ps_result_index),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = stringResource(R.string.ps_result_index_badge),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
                modifier = Modifier
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.tertiaryContainer)
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            )
            Text(
                text = score?.let { stringResource(R.string.ps_result_index_value, PersianFormat.integer(it.total)) }
                    ?: stringResource(R.string.ps_result_index_unavailable),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = stringResource(R.string.ps_result_index_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun trendText(t: MetricTrend?): String {
    val change = t?.changePercent
    return when (t?.direction) {
        null -> stringResource(R.string.ps_trend_unavailable)
        TrendDirection.STABLE -> stringResource(R.string.ps_trend_stable)
        TrendDirection.INCREASED -> stringResource(R.string.ps_trend_increased, PersianFormat.decimal(abs(change ?: 0.0), 0))
        TrendDirection.DECREASED -> stringResource(R.string.ps_trend_decreased, PersianFormat.decimal(abs(change ?: 0.0), 0))
    }
}

/** Raw measurements for a clinician. Units are explicit. */
@Composable
private fun PronationDetails(r: PronationSupinationResult) {
    fun d(v: Double?, digits: Int = 1) = v?.let { PersianFormat.decimal(it, digits) } ?: "—"
    fun trio(t: MetricTrend?, digits: Int) = "${d(t?.early, digits)} / ${d(t?.middle, digits)} / ${d(t?.late, digits)}"
    val s = r.performanceScore
    DetailsTable(
        listOf(
            "مدت ثبت هم‌زمان" to "${d(r.durationMs / 1000.0, 1)} ثانیه",
            "چرخه‌ها (کل / معتبر)" to "${PersianFormat.integer(r.cycleCount)} / ${PersianFormat.integer(r.validCycleCount)}",
            "تعداد چرخه در ثانیه" to "${d(r.cycleRateHz, 2)} هرتز",
            "مدت چرخه: میانگین / میانه" to "${d(r.meanCycleDurationMs, 0)} / ${d(r.medianCycleDurationMs, 0)} میلی‌ثانیه",
            "تغییرپذیری زمان چرخه (CV)" to (r.cycleVariability?.let { "${d(it, 1)}٪" } ?: "—"),
            "سرعت زاویه‌ای: میانگین / RMS" to "${d(r.angularVelocityMean)} / ${d(r.angularVelocityRms)} °/s",
            "سرعت زاویه‌ای اوج (میانه)" to "${d(r.angularVelocityPeak)} °/s",
            "تغییرپذیری سرعت اوج (CV)" to (r.angularVelocityVariability?.let { "${d(it, 1)}٪" } ?: "—"),
            "دامنه حرکت (میانه)" to "${d(r.movementAmplitude)} درجه",
            "تغییرپذیری دامنه (CV)" to (r.amplitudeVariability?.let { "${d(it, 1)}٪" } ?: "—"),
            "پایداری حرکت" to (r.movementConsistency?.let { "${d(it, 0)}٪" } ?: "—"),
            "توقف‌ها" to "${PersianFormat.integer(r.pauseCount)} بار، ${PersianFormat.integer(r.pauseDurationMs)} میلی‌ثانیه",
            "بسامد غالب حرکت" to (r.dominantFrequencyHz?.let { "${d(it, 2)} هرتز" } ?: "—"),
            "شتاب خطی (RMS)" to "${d(r.accelerationRms, 2)} m/s²",
            "سهم محور اصلی چرخش" to "${d(r.rotationAxisSharePercent, 0)}٪",
            "روند دامنه (آغاز / میانه / پایان)" to trio(r.trend?.amplitudeDeg, 0),
            "روند سرعت اوج (آغاز / میانه / پایان)" to trio(r.trend?.peakVelocityDegS, 0),
            "روند مدت چرخه (آغاز / میانه / پایان)" to trio(r.trend?.cycleDurationMs, 0),
            "نرخ نمونه‌برداری واقعی (ژیروسکوپ / شتاب‌سنج)" to "${d(r.effectiveSamplingRate)} / ${d(r.accSamplingRateHz)} هرتز",
            "نمونه‌های معتبر" to "${d(r.validSamplePercentage, 1)}٪",
            "قطعی داده" to "${PersianFormat.integer(r.dropoutCount)} بار",
            "کامل بودن ثبت" to "${d(r.completenessPercent, 1)}٪",
            "مسائل کیفیت" to (r.qualityIssues.joinToString { it.name }.ifEmpty { "—" }),
            "مؤلفه‌های شاخص" to (s?.let {
                "سرعت ${PersianFormat.integer(it.rate)}، ریتم ${it.rhythm?.let(PersianFormat::integer) ?: "—"}، " +
                    "دامنه ${PersianFormat.integer(it.amplitude)}، سرعت زاویه‌ای ${PersianFormat.integer(it.velocity)}، " +
                    "پایداری ${it.consistency?.let(PersianFormat::integer) ?: "—"}، کیفیت ${PersianFormat.integer(it.dataQuality)}"
            } ?: "—"),
            "نسخه الگوریتم" to r.algorithmVersion,
            "نسخه امتیازدهی" to r.scoringVersion
        )
    )
}
