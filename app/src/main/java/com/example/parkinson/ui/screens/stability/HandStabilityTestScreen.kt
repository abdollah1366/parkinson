package com.example.parkinson.ui.screens.stability

import androidx.activity.compose.LocalActivity
import androidx.annotation.StringRes
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.parkinson.R
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.stability.HandStabilitySession
import com.example.parkinson.stability.StabilityError
import com.example.parkinson.stability.StabilityInvalidReason
import com.example.parkinson.stability.StabilityQualityIssue
import com.example.parkinson.stability.StabilityState
import com.example.parkinson.stability.isActive
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.format.PersianFormat

/** Patient-facing explanation (message + tip) for a session that produced no result. */
data class StabilityProblem(@param:StringRes val message: Int, @param:StringRes val tip: Int)

fun stabilityProblem(state: StabilityState): StabilityProblem? = when (state) {
    is StabilityState.Invalid -> when (val reason = state.reason) {
        StabilityInvalidReason.Interrupted -> StabilityProblem(R.string.hs_invalid_interrupted, R.string.hs_tip_retry)
        is StabilityInvalidReason.QualityRejected -> StabilityProblem(
            when (reason.report.primaryIssue) {
                StabilityQualityIssue.SAMPLING_RATE_TOO_LOW -> R.string.hs_invalid_low_rate
                StabilityQualityIssue.TOO_FEW_SAMPLES,
                StabilityQualityIssue.NO_ACCELEROMETER_DATA,
                StabilityQualityIssue.NO_GYROSCOPE_DATA -> R.string.hs_invalid_insufficient
                StabilityQualityIssue.EXCESSIVE_GAPS,
                StabilityQualityIssue.RECORDING_INCOMPLETE -> R.string.hs_invalid_gaps
                else -> R.string.hs_invalid_quality
            },
            R.string.hs_tip_retry
        )
    }

    is StabilityState.Error -> when (state.error) {
        StabilityError.SENSOR_UNAVAILABLE -> StabilityProblem(R.string.hs_error_sensor_unavailable, R.string.invalid_tip_camera)
        StabilityError.SENSOR_STOPPED -> StabilityProblem(R.string.hs_error_sensor_stopped, R.string.hs_tip_retry)
        StabilityError.STORAGE_FAILURE -> StabilityProblem(R.string.invalid_storage_error, R.string.invalid_tip_storage)
        StabilityError.UNEXPECTED -> StabilityProblem(R.string.invalid_unexpected_error, R.string.invalid_tip_camera)
    }

    else -> null
}

@Composable
fun HandStabilityTestScreen(
    selectedHand: SelectedHand?,
    session: HandStabilitySession,
    onCompleted: (assessmentId: String) -> Unit,
    onHome: () -> Unit,
) {
    val state by session.state.collectAsState()
    val hand = selectedHand ?: SelectedHand.RIGHT

    // Leaving the screen, backgrounding or locking ends the test without a result. A rotation
    // (configuration change) does not: the session and sensors live outside the UI.
    val activity = LocalActivity.current
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, session) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) session.abort()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            if (activity?.isChangingConfigurations != true) session.abort()
        }
    }

    // Keep the display on while the test runs.
    val view = LocalView.current
    DisposableEffect(state.isActive) {
        view.keepScreenOn = state.isActive
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(state) {
        val done = state as? StabilityState.Done ?: return@LaunchedEffect
        onCompleted(done.result.assessmentId)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.test_hs_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.selected_hand_label) + " " + handLabel(hand),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            StatusPanel(state, hand)
        }

        when {
            state == StabilityState.Idle -> PrimaryButton(
                text = stringResource(R.string.btn_start_test),
                onClick = { session.start(hand) }
            )

            state.isActive -> OutlinedButton(
                onClick = { session.abort() },
                enabled = state !is StabilityState.Processing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(stringResource(R.string.hs_btn_cancel), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            stabilityProblem(state) != null -> {
                PrimaryButton(text = stringResource(R.string.btn_try_again), onClick = { session.start(hand) })
                TextButton(
                    onClick = onHome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.btn_back_home), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(state: StabilityState, hand: SelectedHand) {
    val announce = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (state) {
        StabilityState.Idle -> Text(
            text = stringResource(R.string.hs_test_idle, handLabel(hand)),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        is StabilityState.Preparation -> {
            Text(
                text = stringResource(R.string.hs_test_preparation, PersianFormat.integer(state.secondsLeft)),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = announce
            )
            Text(stringResource(R.string.hs_test_preparation_hint), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }

        is StabilityState.Recording -> {
            Text(
                text = stringResource(R.string.hs_test_recording, PersianFormat.integer(state.secondsLeft)),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = announce
            )
            LinearProgressIndicator(
                progress = { 1f - state.secondsLeft / 15f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
            Text(stringResource(R.string.hs_test_recording_hint), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }

        StabilityState.Processing -> {
            CircularProgressIndicator()
            Text(stringResource(R.string.hs_test_processing), style = MaterialTheme.typography.titleMedium, modifier = announce)
        }

        is StabilityState.Done -> CircularProgressIndicator()

        is StabilityState.Invalid, is StabilityState.Error -> {
            val problem = stabilityProblem(state) ?: return
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(announce),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.invalid_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(stringResource(problem.message), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(problem.tip), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
