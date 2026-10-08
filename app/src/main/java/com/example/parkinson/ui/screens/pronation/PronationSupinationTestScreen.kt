package com.example.parkinson.ui.screens.pronation

import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.parkinson.R
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.pronation.PronationError
import com.example.parkinson.pronation.PronationQualityIssue
import com.example.parkinson.pronation.PronationState
import com.example.parkinson.pronation.PronationSupinationSession
import com.example.parkinson.pronation.isActive
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.format.PersianFormat

/** Patient-facing explanation (message + reason) for a session that produced no result. */
data class PronationProblem(@param:StringRes val message: Int, @param:StringRes val detail: Int)

fun pronationProblem(state: PronationState): PronationProblem? = when (state) {
    PronationState.Interrupted -> PronationProblem(R.string.ps_invalid_interrupted, R.string.ps_tip_retry)

    is PronationState.Invalid -> PronationProblem(
        R.string.ps_invalid_quality,
        when (state.report.primaryIssue) {
            PronationQualityIssue.NO_MOVEMENT_DETECTED,
            PronationQualityIssue.TOO_FEW_VALID_CYCLES -> R.string.ps_reason_no_movement
            PronationQualityIssue.SAMPLING_RATE_TOO_LOW -> R.string.ps_reason_low_rate
            PronationQualityIssue.EXCESSIVE_GAPS,
            PronationQualityIssue.RECORDING_INCOMPLETE -> R.string.ps_reason_gaps
            PronationQualityIssue.TOO_FEW_SAMPLES,
            PronationQualityIssue.USABLE_DURATION_TOO_SHORT,
            PronationQualityIssue.NO_GYROSCOPE_DATA -> R.string.ps_reason_insufficient
            else -> R.string.ps_tip_retry
        }
    )

    is PronationState.Error -> when (state.error) {
        PronationError.SENSOR_UNAVAILABLE -> PronationProblem(R.string.ps_sensor_missing_gyroscope, R.string.hs_error_sensor_unavailable)
        PronationError.SENSOR_STOPPED -> PronationProblem(R.string.hs_error_sensor_stopped, R.string.ps_tip_retry)
        PronationError.SAMPLING_RATE_TOO_LOW -> PronationProblem(R.string.ps_error_rate, R.string.ps_tip_retry)
        PronationError.TIMESTAMPS_INVALID -> PronationProblem(R.string.ps_error_timestamps, R.string.ps_tip_retry)
        PronationError.STORAGE_FAILURE -> PronationProblem(R.string.invalid_storage_error, R.string.invalid_tip_storage)
        PronationError.UNEXPECTED -> PronationProblem(R.string.invalid_unexpected_error, R.string.ps_tip_retry)
    }

    else -> null
}

@Composable
fun PronationSupinationTestScreen(
    selectedHand: SelectedHand?,
    session: PronationSupinationSession,
    onCompleted: (assessmentId: String) -> Unit,
    /** Leave the test (back to the test list). */
    onExit: () -> Unit,
) {
    val state by session.state.collectAsState()
    val hand = selectedHand ?: SelectedHand.RIGHT
    val activity = LocalActivity.current
    var confirmStop by rememberSaveable { mutableStateOf(false) }

    // The hand (and phone) rotates during this test: keep the current screen orientation so the
    // UI does not flip around while the patient moves.
    DisposableEffect(activity) {
        val previous = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LOCKED
        onDispose { if (previous != null) activity.requestedOrientation = previous }
    }

    // Home, screen lock or backgrounding ends the test as INTERRUPTED, never as a result. A
    // configuration change does not: the session and sensors live in the ViewModel.
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

    // Back during the test asks first; the recording continues while the dialog is shown.
    BackHandler(enabled = state.isActive) { confirmStop = true }
    if (confirmStop && state.isActive) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text(stringResource(R.string.ps_stop_title)) },
            text = { Text(stringResource(R.string.ps_stop_desc)) },
            confirmButton = {
                TextButton(onClick = { confirmStop = false }) { Text(stringResource(R.string.ps_stop_continue)) }
            },
            dismissButton = {
                TextButton(onClick = {
                    confirmStop = false
                    session.abort()
                    onExit()
                }) { Text(stringResource(R.string.ps_stop_exit)) }
            }
        )
    }

    val view = LocalView.current
    DisposableEffect(state.isActive) {
        view.keepScreenOn = state.isActive
        onDispose { view.keepScreenOn = false }
    }

    LaunchedEffect(state) {
        val done = state as? PronationState.Done ?: return@LaunchedEffect
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
                text = stringResource(R.string.test_ps_title),
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
            StatusPanel(state, hand, session.recordingMs / 1000f)
        }

        when {
            state == PronationState.Idle -> PrimaryButton(
                text = stringResource(R.string.btn_start_test),
                onClick = { session.start(hand) }
            )

            state.isActive -> OutlinedButton(
                onClick = { confirmStop = true },
                enabled = state !is PronationState.Processing,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(stringResource(R.string.hs_btn_cancel), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }

            pronationProblem(state) != null -> {
                // Retry is the prominent action after any failed run.
                PrimaryButton(text = stringResource(R.string.ps_btn_retry), onClick = { session.start(hand) })
                TextButton(
                    onClick = onExit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                ) {
                    Text(stringResource(R.string.ps_btn_catalog), style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun StatusPanel(state: PronationState, hand: SelectedHand, recordingSeconds: Float) {
    val polite = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
    when (state) {
        PronationState.Idle -> Text(
            text = stringResource(R.string.ps_test_idle, handLabel(hand)),
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center
        )

        is PronationState.Preparing -> {
            Text(
                text = stringResource(R.string.ps_test_preparing, PersianFormat.integer(state.secondsLeft)),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = polite
            )
            Text(stringResource(R.string.ps_test_preparing_hint), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }

        is PronationState.Countdown -> {
            val description = stringResource(R.string.ps_test_countdown_cd, PersianFormat.integer(state.count))
            Text(
                text = PersianFormat.integer(state.count),
                fontSize = COUNTDOWN_SIZE,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics {
                    contentDescription = description
                    liveRegion = LiveRegionMode.Assertive
                }
            )
            Text(stringResource(R.string.ps_test_countdown_hint), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }

        is PronationState.Recording -> {
            if (state.showStartCue) {
                Text(
                    text = stringResource(R.string.ps_test_start),
                    fontSize = START_CUE_SIZE,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Assertive }
                )
            }
            Text(
                text = stringResource(R.string.ps_test_recording, PersianFormat.integer(state.secondsLeft)),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.secondary,
                textAlign = TextAlign.Center,
                modifier = if (state.showStartCue) Modifier else polite
            )
            LinearProgressIndicator(
                progress = { 1f - state.secondsLeft / recordingSeconds },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
            )
            Text(stringResource(R.string.ps_test_recording_hint), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }

        PronationState.Processing -> {
            CircularProgressIndicator()
            Text(stringResource(R.string.ps_test_processing), style = MaterialTheme.typography.titleMedium, modifier = polite)
        }

        is PronationState.Done -> CircularProgressIndicator()

        is PronationState.Invalid, is PronationState.Error, PronationState.Interrupted -> {
            val problem = pronationProblem(state) ?: return
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(polite),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        stringResource(R.string.ps_invalid_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Text(stringResource(problem.message), style = MaterialTheme.typography.bodyLarge)
                    Text(stringResource(problem.detail), style = MaterialTheme.typography.bodyLarge)
                    Text(
                        stringResource(R.string.ps_invalid_repeat),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

private val COUNTDOWN_SIZE = 96.sp
private val START_CUE_SIZE = 56.sp
