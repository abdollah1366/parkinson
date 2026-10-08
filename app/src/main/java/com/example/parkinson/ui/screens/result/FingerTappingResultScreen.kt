package com.example.parkinson.ui.screens.result

import androidx.annotation.StringRes
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
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.diagnostics.TapDiagnostics
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.tapping.scoring.TappingNote
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
    val score = a.performanceScore
    fun scoreText(v: Int?) = v?.let { "${PersianFormat.integer(it)}/۱۰۰" }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.ft_result_title),
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

        TappingIndexCard(a)

        if (a.cameraQuality != null && a.cameraQuality != CameraQuality.GOOD) {
            Text(
                text = "⚠ " + stringResource(R.string.ft_note_lighting),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

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
            title = stringResource(R.string.ft_result_regularity),
            value = scoreText(score?.rhythm) ?: notMeasurable,
            description = a.intervalCvPercent?.let { stringResource(R.string.result_rhythm_value, PersianFormat.decimal(it)) }
        )
        MetricCard(
            title = stringResource(R.string.result_amplitude),
            value = scoreText(score?.amplitude) ?: notMeasurable,
            description = a.meanAmplitude?.let {
                stringResource(R.string.result_amplitude_value, PersianFormat.integer(Math.round(it * 100).toInt()))
            }
        )
        MetricCard(
            title = stringResource(R.string.result_consistency),
            value = scoreText(score?.consistency) ?: notMeasurable,
            description = stringResource(R.string.result_consistency_desc)
        )
        MetricCard(
            title = stringResource(R.string.ft_result_data_quality),
            value = stringResource(R.string.ps_value_percent, PersianFormat.integer(a.qualityScore)),
            description = a.trackingRatePercent?.let {
                stringResource(R.string.ft_result_tracking, PersianFormat.integer(Math.round(it).toInt()))
            }
        )

        if (a.interpretationNotes.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.large,
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.ps_interpretation_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                    a.interpretationNotes.forEach { Text(stringResource(tappingNoteText(it)), style = MaterialTheme.typography.bodyLarge) }
                }
            }
        }

        if (score != null) {
            val segments = listOf(score.earlyScore, score.middleScore, score.lateScore)
            MetricCard(
                title = stringResource(R.string.ft_result_trend),
                value = stringResource(trendLabel(score.trendState)),
                description = stringResource(
                    R.string.ft_result_trend_segments,
                    segments[0]?.let { PersianFormat.integer(it) } ?: "—",
                    segments[1]?.let { PersianFormat.integer(it) } ?: "—",
                    segments[2]?.let { PersianFormat.integer(it) } ?: "—"
                )
            )
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
                text = stringResource(if (showDetails) R.string.result_details_hide else R.string.result_details_show),
                style = MaterialTheme.typography.titleMedium
            )
        }
        if (showDetails) TechnicalDetails(a)
        if (TapDiagnostics.enabled) DebugQualityPanel(a)

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

@StringRes
fun tappingNoteText(note: TappingNote): Int = when (note) {
    TappingNote.SPEED_AND_RHYTHM_GOOD -> R.string.ft_note_speed_rhythm_good
    TappingNote.SPEED_OR_RHYTHM_LOWER -> R.string.ft_note_speed_rhythm_lower
    TappingNote.AMPLITUDE_LOWER -> R.string.ft_note_amplitude_lower
    TappingNote.TREND_STABLE -> R.string.ft_note_trend_stable
    TappingNote.TREND_DECLINING -> R.string.ft_note_trend_declining
    TappingNote.TREND_IMPROVING -> R.string.ft_note_trend_improving
    TappingNote.QUALITY_GOOD -> R.string.ft_note_quality_good
    TappingNote.QUALITY_LIMITED -> R.string.ft_note_quality_limited
    TappingNote.LIGHTING_WARNING -> R.string.ft_note_lighting
    TappingNote.REPEAT_RECOMMENDED -> R.string.ps_note_repeat
}

/** "شاخص عملکرد حرکتی" with band, reliability and the internal-reference note; one TalkBack sentence. */
@Composable
private fun TappingIndexCard(a: FingerTappingAssessment) {
    val score = a.performanceScore
    val bandText = a.interpretationBand?.let { stringResource(bandLabel(it)) }
    val accessible = if (score != null && bandText != null) {
        stringResource(R.string.ps_result_index_cd, PersianFormat.integer(score.total), bandText)
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
                    text = score?.let { stringResource(R.string.ps_result_index_value, PersianFormat.integer(it.total)) }
                        ?: stringResource(R.string.ps_result_index_unavailable),
                    style = if (score != null) MaterialTheme.typography.displaySmall else MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                if (bandText != null) {
                    Text(bandText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            if (score?.reliability == ReliabilityLevel.LIMITED) {
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

/** Raw measurements for a clinician or researcher. Collapsed by default. */
@Composable
private fun TechnicalDetails(a: FingerTappingAssessment) {
    fun d(v: Double?, digits: Int = 1) = v?.let { PersianFormat.decimal(it, digits) } ?: "—"
    fun pct(v: Double?) = v?.let { PersianFormat.decimal(it * 100, 0) + "٪" } ?: "—"
    fun i(v: Int?) = v?.let { PersianFormat.integer(it) } ?: "—"
    val s = a.performanceScore
    val rows = listOf(
        stringResource(R.string.ft_detail_duration) to "${d(a.recordingDurationMs / 1000.0)} / ${d(a.usableDurationMs?.div(1000.0))}",
        stringResource(R.string.ft_detail_taps_per_10s) to d(a.tapsPer10Seconds),
        stringResource(R.string.ft_detail_interval) to "${d(a.meanIntervalMs, 0)} / ${d(a.medianIntervalMs, 0)} / ${d(a.intervalSdMs, 0)}",
        stringResource(R.string.ft_detail_interval_cv) to "${d(a.intervalCvPercent)}٪ / ${d(a.tapToTapVariabilityPercent)}٪",
        stringResource(R.string.ft_detail_pauses) to PersianFormat.integer(a.pauseCount),
        stringResource(R.string.ft_detail_amplitude) to "${pct(a.medianAmplitude)} / ${pct(a.minAmplitude)} / ${pct(a.maxAmplitude)}",
        stringResource(R.string.ft_detail_amplitude_cv) to "${d(a.amplitudeCvPercent)}٪",
        stringResource(R.string.ft_detail_amplitude_thirds) to "${pct(a.amplitudeEarly)} / ${pct(a.amplitudeMiddle)} / ${pct(a.amplitudeLate)}",
        stringResource(R.string.ft_detail_rate_thirds) to "${d(a.rateEarlyHz)} / ${d(a.rateLateHz)}",
        stringResource(R.string.ft_detail_velocity) to "${d(a.meanClosingVelocity, 2)} / ${d(a.closingVelocityCvPercent)}٪",
        stringResource(R.string.ft_detail_tap_duration) to d(a.meanTapDurationMs, 0),
        stringResource(R.string.ft_detail_fps) to d(a.fps),
        stringResource(R.string.ft_detail_tracking) to "${d(a.trackingRatePercent, 0)}٪ / ${d(a.validFramePercent, 0)}٪",
        stringResource(R.string.ft_detail_dropouts) to
            "${PersianFormat.integer(a.dropoutCount)} / ${PersianFormat.integer(a.dropoutDurationMs)} / ${PersianFormat.integer(a.longestDropoutMs)}",
        stringResource(R.string.ft_detail_layers) to "${a.cameraQuality ?: "—"} / ${a.handTrackingQuality ?: "—"} / ${a.qualityStatus}",
        stringResource(R.string.ft_detail_issues) to a.qualityIssues.joinToString { it.name }.ifEmpty { "—" },
        stringResource(R.string.ft_detail_components) to
            "${i(s?.rate)} / ${i(s?.rhythm)} / ${i(s?.amplitude)} / ${i(s?.consistency)} / ${i(s?.trend)}",
        stringResource(R.string.ps_detail_reference) to (s?.referenceName?.ifEmpty { null } ?: "—"),
        stringResource(R.string.ps_detail_algorithm) to a.algorithmVersion,
        stringResource(R.string.ps_detail_scoring) to a.scoringVersion
    )
    DetailsTable(rows)
}

/** Developer diagnostics (debuggable builds only), for physical-device calibration. */
@Composable
private fun DebugQualityPanel(a: FingerTappingAssessment) {
    DetailsTable(
        listOf(
            "DEBUG" to "developer panel",
            "Camera" to "${a.cameraQuality ?: "—"} (luma ${a.meanLuma?.let { PersianFormat.decimal(it.toDouble(), 0) } ?: "—"})",
            "Hand tracking" to "${a.handTrackingQuality ?: "—"} ${a.trackingRatePercent?.let { PersianFormat.decimal(it, 0) } ?: "—"}%",
            "Frames analyzed" to (a.framesAnalyzed?.toString() ?: "—"),
            "Valid landmark frames" to (a.validLandmarkFrames?.toString() ?: "—"),
            "Dropped (camera / pipeline)" to "${a.cameraFramesSkipped ?: "—"} / ${a.pipelineFramesDropped ?: "—"}",
            "Tap events" to a.tapCount.toString(),
            "Tap rate" to "${PersianFormat.decimal(a.tapRateHz, 2)} /s",
            "Data quality" to "${a.qualityStatus} (${a.qualityScore})",
            "Score" to (a.performanceScore?.total?.toString() ?: "—")
        )
    )
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

