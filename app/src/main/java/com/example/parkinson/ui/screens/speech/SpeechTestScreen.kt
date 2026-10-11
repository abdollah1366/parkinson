package com.example.parkinson.ui.screens.speech

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.parkinson.R
import com.example.parkinson.speech.InputCheckResult
import com.example.parkinson.speech.SpeechError
import com.example.parkinson.speech.SpeechSession
import com.example.parkinson.speech.SpeechState
import com.example.parkinson.speech.SpeechTask
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.ui.screens.camera.openAppSettings
import com.example.parkinson.ui.screens.invalid.InvalidResultKind

/**
 * One guided speech task: microphone permission, a short level check, the countdown, the recording with its progress
 * and elapsed time, then processing. Audio stays in memory. Leaving the screen or backgrounding the app stops the
 * recording and produces no result.
 */
@Composable
fun SpeechTestScreen(
    task: SpeechTask,
    consent: Boolean,
    session: SpeechSession,
    input: InputCheckResult?,
    inputChecking: Boolean,
    inputError: SpeechError?,
    onCheckInput: () -> Unit,
    onCompleted: (assessmentId: String) -> Unit,
    onInvalid: (InvalidResultKind) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scroll = rememberScrollState()
    val sessionState by session.state.collectAsState()

    var permissionGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        )
    }
    var permanentlyDenied by remember { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (!granted) {
            val activity = context as? Activity
            val rationale = activity?.let { ActivityCompat.shouldShowRequestPermissionRationale(it, Manifest.permission.RECORD_AUDIO) } ?: true
            permanentlyDenied = !rationale
        }
    }

    // Leaving the screen discards a running recording; rotation or backgrounding ends it as interrupted.
    val activityLifecycleOwner = (context as? LifecycleOwner) ?: lifecycleOwner
    DisposableEffect(activityLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) session.abort()
        }
        activityLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { activityLifecycleOwner.lifecycle.removeObserver(observer) }
    }
    DisposableEffect(Unit) {
        onDispose {
            if ((context as? Activity)?.isChangingConfigurations == true) session.abort() else session.reset()
        }
    }

    LaunchedEffect(sessionState) {
        when (val s = sessionState) {
            is SpeechState.Done -> onCompleted(s.result.assessmentId)
            is SpeechState.Invalid, is SpeechState.Error -> InvalidResultKind.fromSpeech(s)?.let(onInvalid)
            else -> Unit
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scroll)
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(titleRes(task)),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )

        if (!permissionGranted) {
            InfoCard(stringResource(R.string.speech_test_permission_title), stringResource(R.string.speech_test_permission_text))
            if (permanentlyDenied) {
                InfoCard(null, stringResource(R.string.speech_permission_permanent))
                PrimaryButton(text = stringResource(R.string.speech_btn_settings), onClick = { openAppSettings(context) })
            } else {
                PrimaryButton(
                    text = stringResource(R.string.speech_btn_grant),
                    onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
            return@Column
        }

        // The instruction and, for reading and spontaneous speech, the text or the prompt.
        InfoCard(null, stringResource(instructionRes(task)))
        when (task) {
            SpeechTask.READING -> InfoCard(stringResource(R.string.speech_text_passage_title), stringResource(R.string.speech_reading_passage))
            SpeechTask.SPONTANEOUS -> InfoCard(null, stringResource(R.string.speech_spontaneous_prompt))
            else -> Unit
        }

        val idle = sessionState == SpeechState.Idle
        val checkedReady = input == InputCheckResult.READY
        InfoCard(stringResource(R.string.speech_check_title), stringResource(R.string.speech_check_hint))
        if (inputChecking) {
            Text(stringResource(R.string.speech_check_running), style = MaterialTheme.typography.bodyMedium)
        }
        input?.let {
            Text(
                text = stringResource(checkResultRes(it)),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (it == InputCheckResult.READY) FontWeight.Bold else FontWeight.Normal,
                color = if (it == InputCheckResult.READY) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface,
            )
        }
        inputError?.let {
            Text(stringResource(R.string.speech_check_error), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        OutlinedButton(
            onClick = onCheckInput,
            enabled = idle && !inputChecking,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.speech_btn_check))
        }

        when (val s = sessionState) {
            SpeechState.Idle -> {
                Text(
                    text = stringResource(if (checkedReady) R.string.speech_ready_go else R.string.speech_ready_wait),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (checkedReady) FontWeight.Bold else FontWeight.Normal,
                )
                PrimaryButton(
                    text = stringResource(R.string.speech_btn_start),
                    onClick = { session.start(task, consentAccepted = consent) },
                    enabled = checkedReady && consent,
                )
            }

            is SpeechState.Countdown -> InfoCard(null, stringResource(R.string.speech_countdown, s.secondsLeft.toPersianDigits()))

            is SpeechState.Recording -> {
                val seconds = (s.elapsedMs / 1000).toInt()
                val planned = (s.plannedMs / 1000).toInt()
                InfoCard(null, stringResource(R.string.speech_recording, seconds.toPersianDigits(), planned.toPersianDigits()))
                LinearProgressIndicator(
                    progress = { (s.elapsedMs.toFloat() / s.plannedMs).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            SpeechState.Processing -> InfoCard(null, stringResource(R.string.speech_processing))
            is SpeechState.Done, is SpeechState.Invalid, is SpeechState.Error -> Unit
        }

        if (sessionState is SpeechState.Countdown || sessionState is SpeechState.Recording) {
            OutlinedButton(onClick = { session.abort() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.speech_btn_stop))
            }
            OutlinedButton(onClick = { session.cancel() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.speech_btn_cancel))
            }
        }
    }
}

@Composable
private fun InfoCard(title: String?, text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            title?.let {
                Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
}

private fun instructionRes(task: SpeechTask): Int = when (task) {
    SpeechTask.SUSTAINED_VOWEL -> R.string.speech_instruction_vowel
    SpeechTask.REPEATED_SYLLABLE -> R.string.speech_instruction_syllable
    SpeechTask.READING -> R.string.speech_instruction_reading
    SpeechTask.SPONTANEOUS -> R.string.speech_instruction_spontaneous
}

private fun checkResultRes(result: InputCheckResult): Int = when (result) {
    InputCheckResult.READY -> R.string.speech_check_ready
    InputCheckResult.TOO_QUIET -> R.string.speech_check_quiet
    InputCheckResult.CLIPPING -> R.string.speech_check_clipping
    InputCheckResult.NOISY -> R.string.speech_check_noisy
    InputCheckResult.NO_SIGNAL -> R.string.speech_check_no_signal
}
