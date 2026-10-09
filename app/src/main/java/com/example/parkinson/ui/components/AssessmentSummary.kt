package com.example.parkinson.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.parkinson.R
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.gait.GaitResult
import com.example.parkinson.speech.SpeechResult
import com.example.parkinson.sts.SitToStandResult
import com.example.parkinson.openclose.HandOpenCloseResult
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.tapping.result.FingerTappingAssessment
import com.example.parkinson.tremor.RestingTremorResult
import com.example.parkinson.ui.format.PersianFormat

/** One line with the key measurements of a stored result, for Home and History. */
@Composable
fun keyMetricsLine(result: AssessmentResult): String = when (result) {
    is FingerTappingAssessment -> stringResource(
        R.string.history_item_taps,
        PersianFormat.integer(result.tapCount),
        PersianFormat.decimal(result.tapRateHz)
    )

    is HandStabilityResult -> stringResource(
        R.string.history_item_stability,
        PersianFormat.decimal(result.gyroDynamicRms),
        PersianFormat.decimal(result.accDynamicRms, 2)
    )

    is PronationSupinationResult -> stringResource(
        R.string.history_item_pronation,
        PersianFormat.integer(result.cycleCount),
        PersianFormat.decimal(result.cyclesPerSecond)
    )

    is HandOpenCloseResult -> stringResource(
        R.string.history_item_open_close,
        PersianFormat.integer(result.metrics.completedCycles),
        result.metrics.cycleRatePerSecond?.let { PersianFormat.decimal(it, 2) } ?: "—"
    )

    is RestingTremorResult -> stringResource(
        R.string.history_item_tremor,
        result.metrics.dominantFrequencyHz?.let { PersianFormat.decimal(it, 2) } ?: "—",
        result.metrics.amplitudeRmsPercent?.let { PersianFormat.decimal(it, 2) } ?: "—"
    )

    is SpeechResult -> stringResource(
        R.string.history_item_speech,
        stringResource(com.example.parkinson.ui.screens.speech.titleRes(result.task)),
        PersianFormat.decimal(result.durationMs / 1000.0, 1)
    )

    is SitToStandResult -> stringResource(
        R.string.history_item_sts,
        PersianFormat.integer(result.repetitions.size),
        PersianFormat.decimal(result.totalTimeMs / 1000.0, 1)
    )

    is GaitResult -> stringResource(
        R.string.history_item_gait,
        PersianFormat.integer(result.metrics.stepCount),
        result.metrics.cadenceStepsPerMinute?.let { PersianFormat.decimal(it, 1) } ?: "—"
    )

    else -> ""
}

/** "شاخص عملکرد: … از ۱۰۰" or "بدون امتیاز" when no index was computed. */
@Composable
fun indexLine(result: AssessmentResult): String = result.performanceIndex?.let {
    stringResource(R.string.history_item_index, PersianFormat.integer(it))
} ?: stringResource(R.string.history_item_no_score)
