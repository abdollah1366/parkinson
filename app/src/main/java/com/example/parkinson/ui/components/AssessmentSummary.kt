package com.example.parkinson.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.example.parkinson.R
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.tapping.result.FingerTappingAssessment
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

    else -> ""
}

/** "شاخص عملکرد: … از ۱۰۰" or "بدون امتیاز" when no index was computed. */
@Composable
fun indexLine(result: AssessmentResult): String = result.performanceIndex?.let {
    stringResource(R.string.history_item_index, PersianFormat.integer(it))
} ?: stringResource(R.string.history_item_no_score)
