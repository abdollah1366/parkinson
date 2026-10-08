package com.example.parkinson.ui.screens.result

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.pronation.InterpretationNote
import com.example.parkinson.pronation.MotorPerformanceBand
import com.example.parkinson.pronation.PerformanceTrendState
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.pronation.ReliabilityLevel
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.screens.pronation.TrendBars
import com.example.parkinson.ui.screens.pronation.VelocityChart
import com.example.parkinson.viewmodel.Loadable

@StringRes
fun bandLabel(band: MotorPerformanceBand): Int = when (band) {
    MotorPerformanceBand.GOOD -> R.string.ps_band_good
    MotorPerformanceBand.ACCEPTABLE -> R.string.ps_band_acceptable
    MotorPerformanceBand.REDUCED -> R.string.ps_band_reduced
    MotorPerformanceBand.SIGNIFICANTLY_REDUCED -> R.string.ps_band_significantly_reduced
    MotorPerformanceBand.VERY_LOW -> R.string.ps_band_very_low
}

@StringRes
fun noteText(note: InterpretationNote): Int = when (note) {
    InterpretationNote.SPEED_AND_RHYTHM_GOOD -> R.string.ps_note_speed_rhythm_good
    InterpretationNote.SPEED_OR_RHYTHM_LOWER -> R.string.ps_note_speed_rhythm_lower
    InterpretationNote.AMPLITUDE_LOWER -> R.string.ps_note_amplitude_lower
    InterpretationNote.PAUSES_OBSERVED -> R.string.ps_note_pauses
    InterpretationNote.TREND_DECLINING -> R.string.ps_note_trend_declining
    InterpretationNote.TREND_IMPROVING -> R.string.ps_note_trend_improving
    InterpretationNote.QUALITY_GOOD -> R.string.ps_note_quality_good
    InterpretationNote.QUALITY_LIMITED -> R.string.ps_note_quality_limited
    InterpretationNote.GYROSCOPE_ONLY -> R.string.ps_note_gyroscope_only
    InterpretationNote.REPEAT_RECOMMENDED -> R.string.ps_note_repeat
}

@StringRes
fun trendLabel(state: PerformanceTrendState): Int = when (state) {
    PerformanceTrendState.STABLE -> R.string.ps_trend_stable
    PerformanceTrendState.IMPROVING -> R.string.ps_trend_improving
    PerformanceTrendState.DECLINING -> R.string.ps_trend_declining
    PerformanceTrendState.INSUFFICIENT_DATA -> R.string.ps_trend_insufficient
}

@Composable
fun PronationSupinationResultScreen(
    result: Loadable<PronationSupinationResult?>,
    onRepeat: (SelectedHand) -> Unit,
    onBackToTests: () -> Unit,
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
                PronationContent(value, onRepeat, onBackToTests, onHome)
            }
        }
    }
}

@Composable
private fun PronationContent(
    r: PronationSupinationResult,
    onRepeat: (SelectedHand) -> Unit,
    onBackToTests: () -> Unit,
    onHome: () -> Unit,
) {
    var showDetails by rememberSaveable { mutableStateOf(false) }

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

        IndexCard(r)
        SummaryCard(r)
        InterpretationCard(r)

        if (r.velocityTrace.size >= 2) {
            SectionCard(stringResource(R.string.ps_chart_velocity)) {
                VelocityChart(
                    trace = r.velocityTrace,
                    traceHz = r.traceHz,
                    description = stringResource(
                        R.string.ps_chart_velocity_cd,
                        PersianFormat.integer(Math.round(r.durationMs / 1000.0).toInt()),
                        PersianFormat.integer(Math.round(r.maxAngularVelocity ?: 0.0).toInt())
                    )
                )
                Text(
                    stringResource(R.string.ps_chart_velocity_axis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        SectionCard(stringResource(R.string.ps_chart_trend)) {
            val labels = listOf(R.string.ps_segment_early, R.string.ps_segment_middle, R.string.ps_segment_late).map { stringResource(it) }
            val scores = listOf(r.earlyScore, r.middleScore, r.lateScore)
            val trend = stringResource(trendLabel(r.performanceTrend))
            fun s(v: Int?) = v?.let { PersianFormat.integer(it) } ?: "—"
            TrendBars(
                scores = scores,
                labels = labels,
                description = stringResource(R.string.ps_chart_trend_cd, s(r.earlyScore), s(r.middleScore), s(r.lateScore), trend)
            )
            Text(trend, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        }

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
                text = stringResource(if (showDetails) R.string.ps_details_hide else R.string.ps_details_show),
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (showDetails) PronationDetails(r)

        Spacer(modifier = Modifier.height(4.dp))
        PrimaryButton(text = stringResource(R.string.ps_btn_retry), onClick = { onRepeat(r.hand) })
        OutlinedButton(
            onClick = onBackToTests,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            shape = MaterialTheme.shapes.medium
        ) {
            Text(stringResource(R.string.ps_btn_catalog), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
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

/** "شاخص عملکرد حرکتی" with its band, reliability and the internal-reference note. */
@Composable
private fun IndexCard(r: PronationSupinationResult) {
    val score = r.performanceScore
    val band = r.interpretationBand
    val bandText = band?.let { stringResource(bandLabel(it)) }
    val accessible = if (score != null && bandText != null) {
        stringResource(R.string.ps_result_index_cd, PersianFormat.integer(score), bandText)
    } else stringResource(R.string.ps_result_index_unavailable)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (score != null) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
        ),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(
                modifier = Modifier.clearAndSetSemantics { contentDescription = accessible },
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = stringResource(R.string.ps_result_index),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = score?.let { stringResource(R.string.ps_result_index_value, PersianFormat.integer(it)) }
                        ?: stringResource(R.string.ps_result_index_unavailable),
                    style = if (score != null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                if (bandText != null) {
                    Text(bandText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (r.reliability == ReliabilityLevel.LIMITED) {
                Text(
                    text = "⚠ " + stringResource(R.string.ps_reliability_limited),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error
                )
            }
            Text(
                text = stringResource(R.string.ps_reference_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun SummaryCard(r: PronationSupinationResult) {
    val na = stringResource(R.string.ps_value_unavailable)
    @Composable
    fun score(v: Int?) = v?.let { stringResource(R.string.ps_value_score, PersianFormat.integer(it)) } ?: na
    SectionCard(stringResource(R.string.ps_summary_title)) {
        SummaryRow(stringResource(R.string.ps_label_hand), handLabel(r.hand))
        SummaryRow(
            stringResource(R.string.ps_label_duration),
            stringResource(R.string.ps_value_seconds, PersianFormat.decimal(r.durationMs / 1000.0, 1))
        )
        SummaryRow(stringResource(R.string.ps_label_cycles), PersianFormat.integer(r.cycleCount))
        SummaryRow(stringResource(R.string.ps_label_speed), stringResource(R.string.ps_value_speed, PersianFormat.decimal(r.cyclesPerSecond, 1)))
        SummaryRow(stringResource(R.string.ps_label_regularity), score(r.regularityScore))
        SummaryRow(stringResource(R.string.ps_label_amplitude), score(r.amplitudeScore))
        SummaryRow(stringResource(R.string.ps_label_consistency), score(r.consistencyScore))
        SummaryRow(stringResource(R.string.ps_label_pauses), stringResource(R.string.ps_value_pauses, PersianFormat.integer(r.pauseCount)))
        SummaryRow(stringResource(R.string.ps_label_quality), stringResource(R.string.ps_value_percent, PersianFormat.integer(r.qualityPercentage)))
    }
}

@Composable
private fun InterpretationCard(r: PronationSupinationResult) {
    if (r.interpretationNotes.isEmpty()) return
    SectionCard(stringResource(R.string.ps_interpretation_title)) {
        r.interpretationNotes.forEach { note ->
            Text(stringResource(noteText(note)), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            content()
        }
    }
}

/** Label and value read together by TalkBack. */
@Composable
private fun SummaryRow(label: String, value: String) {
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 40.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(label, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

/** Raw measurements for a clinician or researcher ("جزئیات فنی"), collapsed by default. */
@Composable
private fun PronationDetails(r: PronationSupinationResult) {
    fun d(v: Double?, digits: Int = 1) = v?.let { PersianFormat.decimal(it, digits) } ?: "—"
    fun pct(v: Double?) = v?.let { "${PersianFormat.decimal(it, 1)}٪" } ?: "—"
    fun i(v: Int?) = v?.let { PersianFormat.integer(it) } ?: "—"
    fun join(vararg parts: String) = parts.joinToString(" / ")
    val t = r.measureTrends
    DetailsTable(
        listOf(
            stringResource(R.string.ps_detail_cycles) to join(i(r.cycleCount), i(r.validCycleCount)),
            stringResource(R.string.ps_detail_rate) to join(d(r.cyclesPerSecond, 2), d(r.cyclesPerMinute, 0)),
            stringResource(R.string.ps_detail_cycle_duration) to join(d(r.meanCycleDurationMs, 0), d(r.medianCycleDurationMs, 0)),
            stringResource(R.string.ps_detail_cycle_cv) to pct(r.cycleDurationVariability),
            stringResource(R.string.ps_detail_amplitude) to join(d(r.meanAmplitude), d(r.medianAmplitude)),
            stringResource(R.string.ps_detail_amplitude_cv) to pct(r.amplitudeVariability),
            stringResource(R.string.ps_detail_velocity_mean) to join(d(r.meanAngularVelocity), d(r.angularVelocityRms)),
            stringResource(R.string.ps_detail_velocity_peak) to join(d(r.peakAngularVelocity), d(r.maxAngularVelocity)),
            stringResource(R.string.ps_detail_velocity_cv) to pct(r.velocityVariability),
            stringResource(R.string.ps_detail_pauses) to
                join(i(r.pauseCount), PersianFormat.integer(r.totalPauseDurationMs), PersianFormat.integer(r.longestPauseMs)),
            stringResource(R.string.ps_detail_trend) to stringResource(trendLabel(r.performanceTrend)),
            stringResource(R.string.ps_detail_segment_scores) to join(i(r.earlyScore), i(r.middleScore), i(r.lateScore)),
            stringResource(R.string.ps_detail_segment_amplitude) to
                join(d(t?.amplitudeDeg?.early, 0), d(t?.amplitudeDeg?.middle, 0), d(t?.amplitudeDeg?.late, 0)),
            stringResource(R.string.ps_detail_segment_velocity) to
                join(d(t?.peakVelocityDegS?.early, 0), d(t?.peakVelocityDegS?.middle, 0), d(t?.peakVelocityDegS?.late, 0)),
            stringResource(R.string.ps_detail_components) to
                join(i(r.speedScore), i(r.regularityScore), i(r.amplitudeScore), i(r.consistencyScore), i(r.trendScore)),
            stringResource(R.string.ps_detail_frequency) to d(r.dominantFrequencyHz, 2),
            stringResource(R.string.ps_detail_axis) to pct(r.rotationAxisSharePercent),
            stringResource(R.string.ps_detail_noise) to d(r.noiseLevelDegS, 2),
            stringResource(R.string.ps_detail_acceleration) to d(r.accelerationRms, 2),
            stringResource(R.string.ps_detail_sampling) to join(d(r.samplingRate), d(r.accSamplingRate)),
            stringResource(R.string.ps_detail_valid_samples) to pct(r.validSamplePercentage),
            stringResource(R.string.ps_detail_dropouts) to
                join(i(r.dropoutCount), i(r.estimatedMissingSamples), PersianFormat.integer(r.longestGapMs)),
            stringResource(R.string.ps_detail_completeness) to pct(r.completenessPercent),
            stringResource(R.string.ps_detail_issues) to r.qualityIssues.joinToString { it.name }.ifEmpty { "—" },
            stringResource(R.string.ps_detail_reference) to r.referenceName,
            stringResource(R.string.ps_detail_algorithm) to r.algorithmVersion,
            stringResource(R.string.ps_detail_scoring) to r.scoringVersion
        )
    )
}
