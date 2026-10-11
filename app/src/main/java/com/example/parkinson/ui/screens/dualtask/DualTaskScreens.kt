package com.example.parkinson.ui.screens.dualtask

import android.Manifest
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.parkinson.R
import com.example.parkinson.dualtask.DualTaskController
import com.example.parkinson.dualtask.DualTaskStage
import com.example.parkinson.imu.ImuGaitState
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.StepProgressIndicator
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.ui.screens.imu.ImuGaitScreen
import com.example.parkinson.ui.screens.invalid.InvalidResultKind
import com.example.parkinson.ui.screens.stability.InfoRowCard
import com.example.parkinson.ui.screens.stability.ListCard
import com.example.parkinson.voice.VoiceCues

/**
 * Instructions, safety and the microphone choice of the motor-cognitive test. [onContinue] gets whether the
 * microphone may count the answers (chosen and permitted).
 */
@Composable
fun DualTaskIntroScreen(onContinue: (useMicrophone: Boolean) -> Unit) {
    val context = LocalContext.current
    var wantsMicrophone by rememberSaveable { mutableStateOf(true) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onContinue(granted)
    }
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StepProgressIndicator(currentStep = 1, totalSteps = 2)
            Text(
                text = stringResource(R.string.test_dual_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(stringResource(R.string.dt_intro_instruction), style = MaterialTheme.typography.titleMedium)
            InfoRowCard(stringResource(R.string.duration_title), stringResource(R.string.dt_duration_value))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(R.string.gait_safety_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        stringResource(R.string.dt_safety_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            ListCard(
                title = stringResource(R.string.gait_intro_steps_title),
                items = listOf(R.string.dt_step_1, R.string.dt_step_2, R.string.dt_step_3, R.string.dt_step_4)
                    .map { stringResource(it) },
                numbered = true
            )
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = MaterialTheme.shapes.medium
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.dt_mic_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.dt_mic_text), style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(checked = wantsMicrophone, onCheckedChange = { wantsMicrophone = it })
                }
            }
            Text(stringResource(R.string.disclaimer_text), style = MaterialTheme.typography.bodySmall)
        }
        PrimaryButton(
            text = stringResource(R.string.btn_continue),
            onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                when {
                    !wantsMicrophone -> onContinue(false)
                    granted -> onContinue(true)
                    else -> permission.launch(Manifest.permission.RECORD_AUDIO)
                }
            },
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

/** The two walks, the pause between them and the entry of the last number. */
@Composable
fun DualTaskTestScreen(
    controller: DualTaskController,
    onCompleted: (assessmentId: String) -> Unit,
    onInvalid: (InvalidResultKind) -> Unit,
) {
    val stage by controller.stage.collectAsState()
    LaunchedEffect(stage) {
        (stage as? DualTaskStage.Done)?.let { onCompleted(it.result.assessmentId) }
    }
    when (val s = stage) {
        DualTaskStage.SingleWalk -> ImuGaitScreen(
            session = controller.single,
            onCompleted = { (controller.single.state.value as? ImuGaitState.Done)?.let { controller.singleWalkDone(it.result) } },
            onInvalid = onInvalid,
            title = stringResource(R.string.dt_walk_1_title),
            header = { StageCard(stringResource(R.string.dt_walk_1_hint)) },
        )

        is DualTaskStage.Interlude -> Interlude(s.startNumber, onStart = controller::startDualWalk)

        is DualTaskStage.DualWalk -> ImuGaitScreen(
            session = controller.dual,
            onCompleted = { (controller.dual.state.value as? ImuGaitState.Done)?.let { controller.dualWalkDone(it.result) } },
            onInvalid = onInvalid,
            title = stringResource(R.string.dt_walk_2_title),
            cue = { VoiceCues.dualWalk(s.startNumber, it) },
            header = { StartNumberCard(s.startNumber) },
        )

        is DualTaskStage.Answer -> AnswerEntry(s.startNumber, onSubmit = controller::submit)

        DualTaskStage.Saving, is DualTaskStage.Done -> Centered(stringResource(R.string.imu_processing))

        DualTaskStage.SaveFailed -> Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically)
        ) {
            Text(stringResource(R.string.dt_save_failed), style = MaterialTheme.typography.titleMedium)
            PrimaryButton(text = stringResource(R.string.btn_repeat_assessment), onClick = controller::reset)
        }
    }
}

@Composable
private fun Interlude(startNumber: Int, onStart: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.dt_walk_2_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        StageCard(stringResource(R.string.dt_interlude_text))
        StartNumberCard(startNumber)
        StageCard(stringResource(R.string.dt_interlude_example, startNumber.toPersianDigits(), (startNumber - 3).toPersianDigits(), (startNumber - 6).toPersianDigits()))
        PrimaryButton(text = stringResource(R.string.dt_btn_start_walk_2), onClick = onStart)
    }
}

@Composable
private fun AnswerEntry(startNumber: Int, onSubmit: (Int?) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val number = remember(text) { text.map { c -> when (c) { in '۰'..'۹' -> '0' + (c - '۰'); in '٠'..'٩' -> '0' + (c - '٠'); else -> c } }.joinToString("").toIntOrNull() }
    val valid = number != null && number <= startNumber && number >= 0
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.dt_answer_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        StageCard(stringResource(R.string.dt_answer_text, startNumber.toPersianDigits()))
        OutlinedTextField(
            value = text,
            onValueChange = { v -> text = v.filter { it.isDigit() }.take(3) },
            label = { Text(stringResource(R.string.dt_answer_label)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true,
            isError = text.isNotEmpty() && !valid,
            modifier = Modifier.fillMaxWidth()
        )
        if (text.isNotEmpty() && !valid) {
            Text(stringResource(R.string.dt_answer_invalid, startNumber.toPersianDigits()), color = MaterialTheme.colorScheme.error)
        }
        PrimaryButton(text = stringResource(R.string.dt_btn_save), onClick = { onSubmit(number) }, enabled = valid)
        OutlinedButton(onClick = { onSubmit(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.dt_btn_dont_remember))
        }
    }
}

@Composable
private fun StartNumberCard(startNumber: Int) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.dt_start_number), style = MaterialTheme.typography.titleMedium)
            Text(
                startNumber.toPersianDigits(),
                fontSize = 56.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center
            )
            Text(stringResource(R.string.dt_minus_three), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun StageCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
        shape = MaterialTheme.shapes.medium
    ) {
        Text(text, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun Centered(text: String) {
    Column(
        modifier = Modifier.fillMaxSize().padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}
