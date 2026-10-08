package com.example.parkinson.ui.screens.result

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.tapping.result.FingerTappingAssessment
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.viewmodel.Loadable

@Composable
fun FingerTappingResultScreen(
    assessment: Loadable<FingerTappingAssessment?>,
    onRepeat: (SelectedHand) -> Unit,
    onNewAssessment: () -> Unit,
    onHome: () -> Unit,
    onNextTest: () -> Unit = {},
) {
    when (assessment) {
        Loadable.Loading -> CenteredMessage(stringResource(R.string.result_loading), showProgress = true)
        is Loadable.Loaded -> {
            val value = assessment.value
            if (value == null) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = stringResource(R.string.result_not_found),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onBackground
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    PrimaryButton(text = stringResource(R.string.btn_back_home), onClick = onHome)
                }
            } else {
                ResultContent(value, onRepeat, onNewAssessment, onHome, onNextTest)
            }
        }
    }
}

@Composable
private fun ResultContent(
    a: FingerTappingAssessment,
    onRepeat: (SelectedHand) -> Unit,
    onNewAssessment: () -> Unit,
    onHome: () -> Unit,
    onNextTest: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }
    val notMeasurable = stringResource(R.string.result_not_measurable)
    val seconds = PersianFormat.integer(Math.round(a.recordingDurationMs / 1000.0))

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.result_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = "${PersianFormat.dateTime(a.timestampEpochMs)}  •  ${handLabel(a.hand)}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        QualityChip(a.qualityStatus)

        MetricCard(
            title = stringResource(R.string.result_tap_count),
            value = stringResource(R.string.result_tap_count_value, PersianFormat.integer(a.tapCount), seconds)
        )
        MetricCard(
            title = stringResource(R.string.result_tap_rate),
            value = stringResource(R.string.result_tap_rate_value, PersianFormat.decimal(a.tapRateHz)),
            description = stringResource(R.string.result_tap_rate_desc)
        )
        MetricCard(
            title = stringResource(R.string.result_rhythm),
            value = a.intervalCvPercent?.let {
                stringResource(R.string.result_rhythm_value, PersianFormat.decimal(it))
            } ?: notMeasurable,
            description = stringResource(R.string.result_rhythm_desc)
        )
        MetricCard(
            title = stringResource(R.string.result_amplitude),
            value = a.meanAmplitude?.let {
                stringResource(R.string.result_amplitude_value, PersianFormat.integer(Math.round(it * 100).toInt()))
            } ?: notMeasurable,
            description = stringResource(R.string.result_amplitude_desc)
        )
        MetricCard(
            title = stringResource(R.string.result_consistency),
            value = a.movementConsistencyPercent?.let {
                stringResource(R.string.result_consistency_value, PersianFormat.integer(Math.round(it).toInt()))
            } ?: notMeasurable,
            description = stringResource(R.string.result_consistency_desc)
        )
        MetricCard(
            title = stringResource(R.string.result_trend),
            value = a.amplitudeTrendPercent?.let {
                stringResource(R.string.result_trend_value, signed(it))
            } ?: notMeasurable,
            description = stringResource(R.string.result_trend_desc)
        )
        MetricCard(
            title = stringResource(R.string.result_quality),
            value = stringResource(
                R.string.result_quality_value,
                stringResource(
                    if (a.qualityStatus == QualityStatus.VALID) R.string.result_quality_valid else R.string.result_quality_low
                ),
                PersianFormat.integer(a.qualityScore)
            )
        )
        val score = a.performanceScore
        MetricCard(
            title = stringResource(R.string.result_score),
            value = score?.let { stringResource(R.string.result_score_value, PersianFormat.integer(it.total)) }
                ?: stringResource(R.string.result_score_unavailable),
            description = stringResource(R.string.result_score_note),
            emphasized = score != null
        )

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
        if (showDetails) TechnicalDetails(a)

        Spacer(modifier = Modifier.height(4.dp))
        PrimaryButton(text = stringResource(R.string.btn_repeat_assessment), onClick = { onRepeat(a.hand) })
        OutlinedButton(
            onClick = onNewAssessment,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = stringResource(R.string.btn_new_assessment),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        OutlinedButton(
            onClick = onNextTest,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                text = stringResource(R.string.btn_next_test),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
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

@Composable
internal fun MetricCard(
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
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            if (description != null) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Raw measurements for a clinician. Labels are Persian, units explicit. */
@Composable
private fun TechnicalDetails(a: FingerTappingAssessment) {
    fun d(v: Double?, digits: Int = 1) = v?.let { PersianFormat.decimal(it, digits) } ?: "—"
    fun pct(v: Double?) = v?.let { PersianFormat.decimal(it * 100, 0) + "٪" } ?: "—"
    val s = a.performanceScore
    val rows = listOf(
        "مدت ثبت" to "${d(a.recordingDurationMs / 1000.0)} ثانیه",
        "ضربه در ۱۰ ثانیه" to d(a.tapsPer10Seconds),
        "میانگین فاصله ضربه‌ها" to "${d(a.meanIntervalMs, 0)} میلی‌ثانیه",
        "میانه فاصله ضربه‌ها" to "${d(a.medianIntervalMs, 0)} میلی‌ثانیه",
        "انحراف معیار فاصله‌ها" to "${d(a.intervalSdMs, 0)} میلی‌ثانیه",
        "ضریب تغییرات فاصله‌ها" to "${d(a.intervalCvPercent)}٪",
        "تغییرپذیری ضربه‌به‌ضربه" to "${d(a.tapToTapVariabilityPercent)}٪",
        "تعداد مکث‌ها" to PersianFormat.integer(a.pauseCount),
        "میانه دامنه" to pct(a.medianAmplitude),
        "کمترین / بیشترین دامنه" to "${pct(a.minAmplitude)} / ${pct(a.maxAmplitude)}",
        "ضریب تغییرات دامنه" to "${d(a.amplitudeCvPercent)}٪",
        "دامنه ابتدا / میانه / انتها" to "${pct(a.amplitudeEarly)} / ${pct(a.amplitudeMiddle)} / ${pct(a.amplitudeLate)}",
        "سرعت ابتدا / انتها" to "${d(a.rateEarlyHz)} / ${d(a.rateLateHz)} ضربه در ثانیه",
        "میانگین مدت هر ضربه" to "${d(a.meanTapDurationMs, 0)} میلی‌ثانیه",
        "فریم بر ثانیه" to d(a.fps),
        "فریم‌های معتبر" to "${d(a.validFramePercent)}٪",
        "قطعی ردیابی" to "${PersianFormat.integer(a.dropoutCount)} بار، ${PersianFormat.integer(a.dropoutDurationMs)} میلی‌ثانیه",
        "کامل بودن ثبت" to "${d(a.recordingCompletenessPercent)}٪",
        "مؤلفه‌های امتیاز" to (s?.let {
            "سرعت ${PersianFormat.integer(it.rate)}، ریتم ${PersianFormat.integer(it.rhythm)}، " +
                "دامنه ${PersianFormat.integer(it.amplitude)}، روند ${it.amplitudeTrend?.let(PersianFormat::integer) ?: "—"}، " +
                "پایداری ${PersianFormat.integer(it.consistency)}، کیفیت داده ${PersianFormat.integer(it.dataQuality)}"
        } ?: "—"),
        "نسخه الگوریتم" to a.algorithmVersion,
        "نسخه امتیازدهی" to a.scoringVersion
    )
    DetailsTable(rows)
}

/** Label/value table for technical details (for a clinician). */
@Composable
internal fun DetailsTable(rows: List<Pair<String, String>>) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            rows.forEachIndexed { i, (label, value) ->
                if (i > 0) HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1.2f),
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
internal fun CenteredMessage(text: String, showProgress: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (showProgress) {
                CircularProgressIndicator()
                Spacer(modifier = Modifier.height(16.dp))
            }
            Text(text = text, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

private fun signed(value: Double): String =
    (if (value > 0) "+" else "") + PersianFormat.decimal(value)

