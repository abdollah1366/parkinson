package com.example.parkinson.ui.screens.result

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.norms.NormComparison
import com.example.parkinson.norms.NormEntry
import com.example.parkinson.norms.NormMetric
import com.example.parkinson.norms.NormPerson
import com.example.parkinson.norms.NormSex
import com.example.parkinson.norms.NormVerdict
import com.example.parkinson.ui.format.PersianFormat

/**
 * The result's measures next to published reference values (healthy people of the registered age and sex, and people
 * with Parkinson's disease), with the source of every value. Shows nothing when no measure has a reference.
 */
@Composable
fun ReferenceNormsCard(comparisons: List<NormComparison>, person: NormPerson) {
    if (comparisons.isEmpty()) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.norms_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(personLine(person), style = MaterialTheme.typography.bodySmall)
            comparisons.forEach { c ->
                HorizontalDivider()
                ComparisonBlock(c, person)
            }
            HorizontalDivider()
            Text(stringResource(R.string.norms_disclaimer), style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun ComparisonBlock(c: NormComparison, person: NormPerson) {
    val unit = (c.healthy ?: c.parkinson ?: c.cutoff)?.unit.orEmpty()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            stringResource(R.string.norms_value_line, metricName(c.metric), PersianFormat.decimal(c.value, digits(unit)), unitName(unit)),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.SemiBold,
        )
        val healthyLine = c.healthy?.let { groupLine(it, R.string.norms_group_healthy) }
        val pdLine = c.parkinson?.let { groupLine(it, R.string.norms_group_parkinson) }
        // A person with a registered diagnosis sees the Parkinson's reference first.
        val lines = if (person.parkinsonDiagnosed == true) listOfNotNull(pdLine, healthyLine) else listOfNotNull(healthyLine, pdLine)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        c.cutoff?.let { e ->
            Text(
                stringResource(R.string.norms_cutoff_line, PersianFormat.decimal(e.cutoff!!, digits(unit)), unitName(unit), cutoffMeaning(c.metric)),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (person.parkinsonDiagnosed == true) {
            c.zParkinson?.let { z ->
                Text(
                    stringResource(if (z <= 0.0) R.string.norms_vs_pd_better else R.string.norms_vs_pd_worse),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        Text(
            verdictText(c.verdict),
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = if (c.verdict in WARNINGS) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
        )
        val sources = listOfNotNull(c.healthy, c.parkinson, c.cutoff).distinctBy { it.doi }
        sources.forEach { e ->
            Text(stringResource(R.string.norms_source_line, e.source, e.doiUrl), style = MaterialTheme.typography.bodySmall)
        }
    }
}

private val WARNINGS = setOf(
    NormVerdict.OUTSIDE_HEALTHY_RANGE,
    NormVerdict.CLOSER_TO_PARKINSON,
    NormVerdict.PAST_CUTOFF,
    NormVerdict.PATHOLOGICAL_BAND,
)

@Composable
private fun personLine(person: NormPerson): String {
    if (person.age == null || person.sex == null) return stringResource(R.string.norms_person_unknown)
    val sex = stringResource(if (person.sex == NormSex.FEMALE) R.string.norms_sex_female else R.string.norms_sex_male)
    val base = stringResource(R.string.norms_person_line, PersianFormat.integer(person.age), sex)
    return if (person.parkinsonDiagnosed == true) base + " " + stringResource(R.string.norms_person_diagnosed) else base
}

@Composable
private fun groupLine(e: NormEntry, groupRes: Int): String {
    val who = buildList {
        add(stringResource(groupRes))
        when (e.sex) {
            NormSex.MALE -> add(stringResource(R.string.norms_who_men))
            NormSex.FEMALE -> add(stringResource(R.string.norms_who_women))
            NormSex.ANY -> Unit
        }
        if (!e.anyAge) {
            add(
                if (e.ageMax >= 120) stringResource(R.string.norms_age_from, PersianFormat.integer(e.ageMin))
                else stringResource(R.string.norms_age_range, PersianFormat.integer(e.ageMin), PersianFormat.integer(e.ageMax))
            )
        }
    }.joinToString("، ")
    val unit = unitName(e.unit)
    val d = digits(e.unit)
    return when {
        e.low != null && e.high != null ->
            stringResource(R.string.norms_band_line, who, PersianFormat.decimal(e.low, 0), PersianFormat.decimal(e.high, 0), unit)
        e.mean != null && e.sd != null ->
            stringResource(R.string.norms_mean_sd_line, who, PersianFormat.decimal(e.mean, d), PersianFormat.decimal(e.sd, d), unit)
        e.mean != null -> stringResource(R.string.norms_mean_line, who, PersianFormat.decimal(e.mean, d), unit)
        else -> who
    }
}

private fun digits(unit: String): Int = when (unit) {
    "Hz", "s" -> 2
    "steps/min", "deg/s", "%", "deg" -> 1
    else -> 1
}

@Composable
private fun unitName(unit: String): String = when (unit) {
    "steps/min" -> stringResource(R.string.norms_unit_steps_min)
    "%" -> stringResource(R.string.norms_unit_percent)
    "deg/s" -> stringResource(R.string.norms_unit_deg_s)
    "s" -> stringResource(R.string.norms_unit_seconds)
    "Hz" -> stringResource(R.string.norms_unit_hz)
    "deg" -> stringResource(R.string.norms_unit_deg)
    else -> unit
}

@Composable
private fun metricName(metric: String): String = when (metric) {
    NormMetric.GAIT_CADENCE -> stringResource(R.string.norms_metric_cadence)
    NormMetric.GAIT_STRIDE_CV -> stringResource(R.string.norms_metric_stride_cv)
    NormMetric.GAIT_TURN_VELOCITY -> stringResource(R.string.norms_metric_turn_velocity)
    NormMetric.STS_FIVE_TIMES -> stringResource(R.string.norms_metric_sts)
    NormMetric.FT_TAP_RATE -> stringResource(R.string.norms_metric_tap_rate)
    NormMetric.FT_INTERVAL_CV -> stringResource(R.string.norms_metric_tap_cv)
    NormMetric.PS_CYCLE_RATE -> stringResource(R.string.norms_metric_ps_rate)
    NormMetric.TREMOR_FREQUENCY -> stringResource(R.string.norms_metric_tremor_frequency)
    else -> metric
}

@Composable
private fun cutoffMeaning(metric: String): String = when (metric) {
    NormMetric.STS_FIVE_TIMES -> stringResource(R.string.norms_cutoff_sts)
    else -> stringResource(R.string.norms_cutoff_pd_healthy)
}

@Composable
private fun verdictText(v: NormVerdict): String = stringResource(
    when (v) {
        NormVerdict.WITHIN_HEALTHY -> R.string.norms_verdict_within
        NormVerdict.OUTSIDE_HEALTHY_RANGE -> R.string.norms_verdict_outside
        NormVerdict.WORSE_THAN_AVERAGE -> R.string.norms_verdict_worse_than_average
        NormVerdict.CLOSER_TO_PARKINSON -> R.string.norms_verdict_closer_pd
        NormVerdict.PAST_CUTOFF -> R.string.norms_verdict_past_cutoff
        NormVerdict.WITHIN_CUTOFF -> R.string.norms_verdict_within_cutoff
        NormVerdict.PHYSIOLOGICAL_BAND -> R.string.norms_verdict_band_physiological
        NormVerdict.PATHOLOGICAL_BAND -> R.string.norms_verdict_band_pathological
        NormVerdict.OTHER_BAND -> R.string.norms_verdict_band_other
        NormVerdict.NO_REFERENCE -> R.string.norms_verdict_none
    }
)
