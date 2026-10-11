package com.example.parkinson.ui.screens.imu

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.parkinson.R
import com.example.parkinson.imu.CalibrationIssue
import com.example.parkinson.imu.ImuGaitSession
import com.example.parkinson.imu.ImuGaitState
import com.example.parkinson.imu.ImuSitToStandSession
import com.example.parkinson.imu.ImuSitToStandState
import com.example.parkinson.sensors.SensorAvailability
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.ui.screens.invalid.InvalidResultKind

/** Persian wording of the phone-placement warning shared by both IMU assessments. */
@Composable
private fun PlacementCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.imu_placement_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun InfoCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun SensorStatus(availability: SensorAvailability?) {
    val text = when {
        availability == null -> stringResource(R.string.imu_sensor_not_checked)
        availability.ready -> stringResource(R.string.imu_sensor_ready)
        else -> stringResource(R.string.imu_sensor_missing)
    }
    Text(text, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
}

/** Stops a running session when the screen is left (or the app is backgrounded), and releases the sensors. */
@Composable
private fun LifecycleGuard(onStop: () -> Unit, onLeave: () -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val owner = (context as? LifecycleOwner) ?: lifecycleOwner
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_STOP) onStop() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            if ((context as? Activity)?.isChangingConfigurations == true) onStop() else onLeave()
        }
    }
}

@Composable
fun ImuSitToStandScreen(
    session: ImuSitToStandSession,
    onCompleted: (assessmentId: String) -> Unit,
    onInvalid: (InvalidResultKind) -> Unit,
) {
    val scroll = rememberScrollState()
    val state by session.state.collectAsState()
    val issue by session.calibrationIssue.collectAsState()
    val availability by session.availability.collectAsState()

    LifecycleGuard(onStop = { session.abort() }, onLeave = { session.reset() })
    LaunchedEffect(state) {
        when (val s = state) {
            is ImuSitToStandState.Done -> onCompleted(s.result.assessmentId)
            is ImuSitToStandState.Invalid -> onInvalid(InvalidResultKind.fromImuSitToStand(s.reason))
            is ImuSitToStandState.Error -> onInvalid(InvalidResultKind.fromImuError(s.error))
            else -> Unit
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.test_sts_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        PlacementCard(stringResource(R.string.imu_placement_sts))
        SensorStatus(availability)

        when (val s = state) {
            ImuSitToStandState.Idle -> {
                issue?.let { InfoCard(stringResource(calibrationIssueText(it))) }
                InfoCard(stringResource(R.string.imu_sts_idle_hint))
                PrimaryButton(text = stringResource(R.string.imu_btn_calibrate), onClick = { session.start() })
            }

            is ImuSitToStandState.Calibrating -> {
                InfoCard(stringResource(R.string.imu_sts_calibrating))
                LinearProgressIndicator(
                    progress = { (s.elapsedMs / 2_500f).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = { session.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imu_btn_cancel))
                }
            }

            is ImuSitToStandState.Countdown -> {
                InfoCard(stringResource(R.string.imu_countdown, s.secondsLeft.toPersianDigits()))
                OutlinedButton(onClick = { session.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imu_btn_cancel))
                }
            }

            is ImuSitToStandState.Active -> {
                InfoCard(stringResource(R.string.imu_sts_live, s.repetitions.toPersianDigits(), s.target.toPersianDigits()))
                InfoCard(stringResource(R.string.imu_elapsed, (s.elapsedMs / 1000).toInt().toPersianDigits()))
                OutlinedButton(onClick = { session.pause() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imu_btn_pause))
                }
                OutlinedButton(onClick = { session.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imu_btn_cancel))
                }
            }

            is ImuSitToStandState.Paused -> {
                InfoCard(stringResource(R.string.imu_paused, s.repetitions.toPersianDigits(), s.target.toPersianDigits()))
                PrimaryButton(text = stringResource(R.string.imu_btn_resume), onClick = { session.resume() })
                OutlinedButton(onClick = { session.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imu_btn_cancel))
                }
            }

            ImuSitToStandState.Processing -> InfoCard(stringResource(R.string.imu_processing))
            is ImuSitToStandState.Done, is ImuSitToStandState.Invalid, is ImuSitToStandState.Error -> Unit
        }
    }
}

@Composable
fun ImuGaitScreen(
    session: ImuGaitSession,
    onCompleted: (assessmentId: String) -> Unit,
    onInvalid: (InvalidResultKind) -> Unit,
) {
    val scroll = rememberScrollState()
    val state by session.state.collectAsState()
    val issue by session.calibrationIssue.collectAsState()
    val availability by session.availability.collectAsState()

    LifecycleGuard(onStop = { session.abort() }, onLeave = { session.reset() })
    LaunchedEffect(state) {
        when (val s = state) {
            is ImuGaitState.Done -> onCompleted(s.result.assessmentId)
            is ImuGaitState.Invalid -> onInvalid(InvalidResultKind.fromImuGait(s.reason))
            is ImuGaitState.Error -> onInvalid(InvalidResultKind.fromImuError(s.error))
            else -> Unit
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(scroll).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = stringResource(R.string.test_gait_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
        PlacementCard(stringResource(R.string.imu_placement_gait))
        SensorStatus(availability)

        when (val s = state) {
            ImuGaitState.Idle -> {
                issue?.let { InfoCard(stringResource(calibrationIssueText(it))) }
                InfoCard(stringResource(R.string.imu_gait_idle_hint))
                PrimaryButton(text = stringResource(R.string.imu_btn_calibrate), onClick = { session.start() })
            }

            is ImuGaitState.Calibrating -> {
                InfoCard(stringResource(R.string.imu_gait_calibrating))
                LinearProgressIndicator(progress = { (s.elapsedMs / 3_000f).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { session.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imu_btn_cancel))
                }
            }

            is ImuGaitState.Countdown -> {
                InfoCard(stringResource(R.string.imu_countdown, s.secondsLeft.toPersianDigits()))
                OutlinedButton(onClick = { session.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imu_btn_cancel))
                }
            }

            is ImuGaitState.Walking -> {
                InfoCard(stringResource(R.string.imu_gait_live, s.steps.toPersianDigits()))
                LinearProgressIndicator(
                    progress = { (s.walkedMs.toFloat() / s.plannedMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
                InfoCard(stringResource(R.string.imu_elapsed, (s.walkedMs / 1000).toInt().toPersianDigits()))
                OutlinedButton(onClick = { session.pause() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imu_btn_pause))
                }
                OutlinedButton(onClick = { session.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imu_btn_cancel))
                }
            }

            is ImuGaitState.Paused -> {
                InfoCard(stringResource(R.string.imu_paused_gait, s.steps.toPersianDigits()))
                PrimaryButton(text = stringResource(R.string.imu_btn_resume), onClick = { session.resume() })
                OutlinedButton(onClick = { session.reset() }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(R.string.imu_btn_cancel))
                }
            }

            ImuGaitState.Processing -> InfoCard(stringResource(R.string.imu_processing))
            is ImuGaitState.Done, is ImuGaitState.Invalid, is ImuGaitState.Error -> Unit
        }
    }
}

private fun calibrationIssueText(issue: CalibrationIssue): Int = when (issue) {
    CalibrationIssue.INSUFFICIENT_DATA -> R.string.imu_calib_insufficient
    CalibrationIssue.NOT_STILL -> R.string.imu_calib_not_still
    CalibrationIssue.IMPLAUSIBLE_GRAVITY -> R.string.imu_calib_gravity
}
