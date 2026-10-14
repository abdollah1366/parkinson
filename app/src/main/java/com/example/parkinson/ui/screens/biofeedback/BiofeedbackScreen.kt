package com.example.parkinson.ui.screens.biofeedback

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.parkinson.R
import com.example.parkinson.biofeedback.BioExercise
import com.example.parkinson.biofeedback.BiofeedbackLevels
import com.example.parkinson.biofeedback.LoudVoiceEngine
import com.example.parkinson.biofeedback.MemorySequenceEngine
import com.example.parkinson.biofeedback.TapRhythmEngine
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.viewmodel.BioLive
import com.example.parkinson.viewmodel.BioResult
import com.example.parkinson.viewmodel.BioStage
import com.example.parkinson.viewmodel.BioState
import com.example.parkinson.viewmodel.BiofeedbackViewModel
import com.example.parkinson.voice.VoiceClip
import com.example.parkinson.voice.rememberVoiceGuide
import kotlin.math.min

private val Good = Color(0xFF2E7D32)
private val Warn = Color(0xFFEF6C00)

/** One biofeedback session: what to do (and the safety check for standing ones), the live feedback, the result. */
@Composable
fun BiofeedbackScreen(viewModel: BiofeedbackViewModel, state: BioState, onBack: () -> Unit) {
    val view = LocalView.current
    // The screen stays on while the exercise runs; leaving it stops the session.
    DisposableEffect(state.stage) {
        view.keepScreenOn = state.stage == BioStage.RUNNING
        onDispose { view.keepScreenOn = false }
    }
    DisposableEffect(viewModel) { onDispose { viewModel.leave() } }
    val voice = rememberVoiceGuide()
    val exercise = state.exercise

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            exercise?.let { stringResource(titleOf(it)) } ?: (state.prescription?.name ?: ""),
            style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        when {
            state.prescription == null -> Unit
            exercise == null -> Text(stringResource(R.string.bio_unknown))
            state.stage == BioStage.INTRO -> Intro(viewModel, state, exercise, onStart = { clip -> voice.play(clip) })
            state.stage == BioStage.RUNNING -> Running(viewModel, state, voice::play)
            else -> state.result?.let { Result(it, onAgain = viewModel::again, onBack = onBack) }
        }
    }
}

private fun titleOf(e: BioExercise) = when (e) {
    BioExercise.LOUD_VOICE -> R.string.bio_title_loud
    BioExercise.BALANCE -> R.string.bio_title_balance
    BioExercise.METRONOME_WALK -> R.string.bio_title_walk
    BioExercise.MEMORY_SEQUENCE -> R.string.bio_title_memory
    BioExercise.TAP_RHYTHM -> R.string.bio_title_tap
}

private fun one(v: Double): String = PersianFormat.decimal(v, if (v % 1.0 == 0.0) 0 else 1)

@Composable
private fun Intro(viewModel: BiofeedbackViewModel, state: BioState, exercise: BioExercise, onStart: (VoiceClip) -> Unit) {
    val level = state.level
    val context = LocalContext.current
    var safe by rememberSaveable { mutableStateOf(false) }
    val startClip = when (exercise) {
        BioExercise.LOUD_VOICE -> VoiceClip.BIO_NORMAL_VOICE
        BioExercise.BALANCE -> VoiceClip.BIO_BALANCE
        BioExercise.METRONOME_WALK -> VoiceClip.BIO_WALK
        BioExercise.MEMORY_SEQUENCE -> VoiceClip.BIO_WATCH
        BioExercise.TAP_RHYTHM -> null
    }
    val begin = {
        viewModel.start()
        startClip?.let(onStart)
    }
    val micLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) begin() }

    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.bio_level, level.toPersianDigits()), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        Text(
            when (exercise) {
                BioExercise.LOUD_VOICE -> stringResource(R.string.bio_intro_loud,
                    one(BiofeedbackLevels.loudTargetDb(level)), one(BiofeedbackLevels.loudHoldSec(level)))
                BioExercise.BALANCE -> stringResource(R.string.bio_intro_balance,
                    one(BiofeedbackLevels.balanceRadiusDeg(level)), one(BiofeedbackLevels.balanceDurationSec(level)))
                BioExercise.METRONOME_WALK -> stringResource(R.string.bio_intro_walk,
                    one(BiofeedbackLevels.walkBpm(level)), one(BiofeedbackLevels.walkDurationSec(level) / 60))
                BioExercise.MEMORY_SEQUENCE -> stringResource(R.string.bio_intro_memory)
                BioExercise.TAP_RHYTHM -> stringResource(R.string.bio_intro_tap,
                    one(BiofeedbackLevels.tapDurationSec(level)), one(BiofeedbackLevels.tapTargetRate(level)))
            },
            style = MaterialTheme.typography.bodyLarge
        )
        state.prescription?.instructions?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (exercise.standing) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { safe = !safe }) {
                Checkbox(checked = safe, onCheckedChange = { safe = it })
                Text(stringResource(R.string.bio_safety_check), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Bold)
            }
        }
        when (state.error) {
            "mic" -> Text(stringResource(R.string.bio_err_mic), color = MaterialTheme.colorScheme.error)
            "sensor" -> Text(stringResource(R.string.bio_err_sensor), color = MaterialTheme.colorScheme.error)
        }
        Button(
            onClick = {
                val needsMic = exercise == BioExercise.LOUD_VOICE &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
                if (needsMic) micLauncher.launch(Manifest.permission.RECORD_AUDIO) else begin()
            },
            enabled = !exercise.standing || safe,
            modifier = Modifier.fillMaxWidth().height(56.dp)
        ) { Text(stringResource(R.string.bio_start), fontSize = 20.sp) }
    }
}

@Composable
private fun Running(viewModel: BiofeedbackViewModel, state: BioState, speak: (VoiceClip) -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when (val live = state.live) {
                is BioLive.Loud -> LoudView(live, speak)
                is BioLive.Balance -> BalanceView(live)
                is BioLive.Walk -> WalkView(live)
                is BioLive.Memory -> MemoryView(live, viewModel::tapCell)
                is BioLive.Tap -> TapView(live, viewModel::tapTarget, viewModel::switchHand, speak)
                null -> Unit
            }
        }
        // Always within reach, large and red.
        Button(
            onClick = { viewModel.finish(completed = false) },
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().height(64.dp)
        ) { Text(stringResource(R.string.bio_stop), fontSize = 22.sp, fontWeight = FontWeight.Bold) }
    }
}

@Composable
private fun LoudView(live: BioLive.Loud, speak: (VoiceClip) -> Unit) {
    val s = live.snapshot
    // The loud cue when the first repetition is about to start.
    LaunchedEffect(s.phase == LoudVoiceEngine.Phase.WAITING && s.rep == 0) {
        if (s.phase == LoudVoiceEngine.Phase.WAITING && s.rep == 0) speak(VoiceClip.BIO_LOUD)
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(when (s.phase) {
                LoudVoiceEngine.Phase.CALIBRATING -> R.string.bio_loud_calibrating
                LoudVoiceEngine.Phase.WAITING -> R.string.bio_loud_waiting
                LoudVoiceEngine.Phase.HOLDING -> R.string.bio_loud_holding
                else -> R.string.bio_loud_resting
            }),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
        Text(stringResource(R.string.bio_loud_rep, min(s.rep + 1, live.reps).toPersianDigits(), live.reps.toPersianDigits(), s.successes.toPersianDigits()))
        if (s.phase == LoudVoiceEngine.Phase.HOLDING) {
            Text(stringResource(R.string.bio_loud_held, one(Math.round(s.heldSec * 10) / 10.0), one(live.holdSec)), fontWeight = FontWeight.Bold)
        }
        // A column from 10 dB below to 20 dB above the normal voice, with the target line.
        val track = MaterialTheme.colorScheme.surfaceVariant
        val line = MaterialTheme.colorScheme.onSurface
        val calibrating = s.phase == LoudVoiceEngine.Phase.CALIBRATING
        Canvas(modifier = Modifier.weight(1f).width(110.dp)) {
            fun yOf(db: Double) = size.height * (1f - ((db + 10.0) / 30.0).coerceIn(0.0, 1.0).toFloat())
            drawRect(track)
            val top = if (calibrating) size.height * 0.5f else yOf(s.aboveDb)
            val color = if (!calibrating && s.aboveDb >= live.targetDb) Good else Warn
            drawRect(color, topLeft = Offset(0f, top), size = androidx.compose.ui.geometry.Size(size.width, size.height - top))
            if (!calibrating) {
                val y = yOf(live.targetDb)
                drawLine(line, Offset(-8f, y), Offset(size.width + 8f, y), strokeWidth = 6f)
            }
        }
    }
}

@Composable
private fun BalanceView(live: BioLive.Balance) {
    val s = live.snapshot
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        Text(
            stringResource(when {
                s.calibrating -> R.string.bio_balance_calibrating
                s.inside -> R.string.bio_balance_inside
                else -> R.string.bio_balance_outside
            }),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (s.inside) Good else Warn
        )
        Text(stringResource(R.string.bio_time, s.elapsedSec.toInt().toPersianDigits(), live.durationSec.toInt().toPersianDigits()))
        val ring = MaterialTheme.colorScheme.primary
        Canvas(modifier = Modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = "balance" }) {
            val c = Offset(size.width / 2, size.height / 2)
            // The whole view is three times the circle.
            val scale = (size.minDimension / 2) / (live.radiusDeg * 3).toFloat()
            drawCircle(ring, radius = (live.radiusDeg * scale).toFloat(), center = c, style = Stroke(width = 8f))
            val dot = Offset(
                (c.x + (s.sideDeg * scale)).toFloat().coerceIn(0f, size.width),
                (c.y - (s.forwardDeg * scale)).toFloat().coerceIn(0f, size.height),
            )
            drawCircle(if (s.inside) Good else Warn, radius = 28f, center = dot)
        }
    }
}

@Composable
private fun WalkView(live: BioLive.Walk) {
    val s = live.snapshot
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        Text(stringResource(R.string.bio_time, s.elapsedSec.toInt().toPersianDigits(), live.durationSec.toInt().toPersianDigits()))
        // Flashes on every beat, for a person who hears the beat poorly.
        val on = live.beats % 2 == 0
        Box(
            modifier = Modifier.size(180.dp).clip(CircleShape)
                .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
        )
        val near = s.cadence > 0 && Math.abs(s.cadence - live.bpm) / live.bpm <= 0.1
        Text(
            if (s.cadence <= 0) stringResource(R.string.bio_walk_wait)
            else stringResource(R.string.bio_walk_line, s.steps.toPersianDigits(), s.cadence.toInt().toPersianDigits(), live.bpm.toInt().toPersianDigits()),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            color = if (s.cadence <= 0) MaterialTheme.colorScheme.onBackground else if (near) Good else Warn
        )
    }
}

@Composable
private fun MemoryView(live: BioLive.Memory, onTap: (Int) -> Unit) {
    val s = live.snapshot
    val answering = s.phase == MemorySequenceEngine.Phase.ANSWERING
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
        Text(
            if (answering) stringResource(R.string.bio_memory_answer, (s.entered + 1).toPersianDigits(), s.length.toPersianDigits())
            else stringResource(R.string.bio_memory_watch),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold
        )
        Text(stringResource(R.string.bio_memory_line, s.length.toPersianDigits(), s.mistakes.toPersianDigits()))
        if (!answering && s.lastCorrect != null) {
            Text(
                stringResource(if (s.lastCorrect) R.string.bio_memory_right else R.string.bio_memory_wrong),
                color = if (s.lastCorrect) Good else Warn, fontWeight = FontWeight.Bold
            )
        }
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            for (row in 0 until 3) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (col in 0 until 3) {
                        val cell = row * 3 + col
                        val lit = live.lit == cell
                        Box(
                            modifier = Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(16.dp))
                                .background(if (lit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                                .clickable(enabled = answering) { onTap(cell) }
                                .semantics { contentDescription = "cell ${cell + 1}" }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TapView(live: BioLive.Tap, onTap: (Int) -> Unit, onSwitch: () -> Unit, speak: (VoiceClip) -> Unit) {
    val s = live.snapshot
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxSize()) {
        val hand = stringResource(if (s.hand == TapRhythmEngine.Hand.RIGHT) R.string.bio_tap_hand_right else R.string.bio_tap_hand_left)
        if (live.handDone && s.hand == TapRhythmEngine.Hand.RIGHT) {
            Text(stringResource(R.string.bio_tap_switch), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Button(onClick = { onSwitch(); speak(VoiceClip.BIO_LEFT_HAND) }, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                Text(stringResource(R.string.bio_tap_hand_left), fontSize = 20.sp)
            }
            return@Column
        }
        Text(
            if (!s.running && s.taps == 0) "$hand — " + stringResource(R.string.bio_tap_first)
            else stringResource(R.string.bio_tap_line, hand, s.taps.toPersianDigits(), one(Math.round(s.rate * 10) / 10.0),
                stringResource(R.string.bio_time, s.elapsedSec.toInt().toPersianDigits(), live.durationSec.toInt().toPersianDigits())),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            color = if (s.taps > 0 && s.rate >= live.targetRate) Good else MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(20.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            for (target in 0..1) {
                val next = s.next == target
                Box(
                    modifier = Modifier.size(130.dp).clip(CircleShape)
                        .background(if (next) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable { onTap(target) }
                        .semantics { contentDescription = "target ${target + 1}" }
                )
            }
        }
    }
}

@Composable
private fun Result(result: BioResult, onAgain: () -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(stringResource(R.string.bio_result_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.bio_result_score, result.score.toPersianDigits()),
            style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            color = if (result.score >= 70) Good else if (result.score >= 40) MaterialTheme.colorScheme.onBackground else Warn
        )
        if (!result.completed) Text(stringResource(R.string.bio_result_stopped), color = Warn)
        result.metrics.forEach { (key, value) ->
            metricLabel(key)?.let { Text(stringResource(it) + ": " + one(value)) }
        }
        Text(
            stringResource(when (result.sent) {
                null -> R.string.bio_result_saving
                true -> R.string.bio_result_sent
                false -> R.string.bio_result_waiting
            }),
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack) { Text(stringResource(R.string.bio_back)) }
            OutlinedButton(onClick = onAgain) { Text(stringResource(R.string.plan_bio_again)) }
        }
    }
}

private fun metricLabel(key: String): Int? = when (key) {
    "baselineDb" -> R.string.bio_metric_baselineDb
    "meanAboveBaselineDb" -> R.string.bio_metric_meanAboveBaselineDb
    "repsInTarget" -> R.string.bio_metric_repsInTarget
    "longestHoldSec" -> R.string.bio_metric_longestHoldSec
    "insidePercent" -> R.string.bio_metric_insidePercent
    "meanSwayDeg" -> R.string.bio_metric_meanSwayDeg
    "steps" -> R.string.bio_metric_steps
    "cadence" -> R.string.bio_metric_cadence
    "maxSpan" -> R.string.bio_metric_maxSpan
    "mistakes" -> R.string.bio_metric_mistakes
    "rateRight" -> R.string.bio_metric_rateRight
    "rateLeft" -> R.string.bio_metric_rateLeft
    else -> null
}
