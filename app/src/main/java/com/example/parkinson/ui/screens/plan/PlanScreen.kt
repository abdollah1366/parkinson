package com.example.parkinson.ui.screens.plan

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.parkinson.R
import com.example.parkinson.plan.IntakeStatus
import com.example.parkinson.plan.PlanKind
import com.example.parkinson.plan.Prescription
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.viewmodel.DoseRow
import com.example.parkinson.viewmodel.ExerciseRow
import com.example.parkinson.viewmodel.PlanState

/** Today's doses with "taken" and "missed", and the exercises with "done", as the doctor prescribed them. */
@Composable
fun PlanScreen(
    state: PlanState,
    onMark: (DoseRow, IntakeStatus) -> Unit,
    onExerciseDone: (Prescription) -> Unit,
    /** Opens a biofeedback exercise. */
    onStartBiofeedback: (Prescription) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.plan_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        if (!state.linked && !state.hasPlan) {
            Text(stringResource(R.string.plan_not_linked), style = MaterialTheme.typography.bodyLarge)
            return@Column
        }
        Text(stringResource(R.string.plan_subtitle, state.personName), style = MaterialTheme.typography.bodyMedium)
        NotificationPermission()
        if (state.refreshing && !state.hasPlan) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator()
                Text(stringResource(R.string.plan_loading))
            }
        }
        if (state.offline) {
            Text(stringResource(R.string.plan_offline), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        }
        if (state.pending > 0) {
            Text(stringResource(R.string.plan_pending, state.pending.toPersianDigits()), style = MaterialTheme.typography.bodySmall)
        }
        if (state.hasPlan && state.doses.isEmpty() && state.exercises.isEmpty()) {
            Text(stringResource(R.string.plan_empty), style = MaterialTheme.typography.bodyLarge)
        }

        if (state.doses.isNotEmpty()) {
            Text(stringResource(R.string.plan_medications), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            state.doses.forEach { DoseCard(it, onMark) }
        } else if (state.exercises.isNotEmpty()) {
            Text(stringResource(R.string.plan_no_doses_today), style = MaterialTheme.typography.bodyMedium)
        }
        if (state.exercises.isNotEmpty()) {
            Text(stringResource(R.string.plan_exercises), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            state.exercises.forEach { ExerciseCard(it, onExerciseDone, onStartBiofeedback) }
        }
    }
}

@Composable
private fun DoseCard(row: DoseRow, onMark: (DoseRow, IntakeStatus) -> Unit) {
    val p = row.dose.prescription
    var changing by remember(row.status) { mutableStateOf(false) }
    PlanCard {
        Text(
            stringResource(R.string.plan_dose_line, PersianFormat.time(row.dose.atEpochMs), p.name),
            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold
        )
        p.dose?.let { Text(PersianFormat.digits(it), style = MaterialTheme.typography.bodyMedium) }
        p.instructions?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (row.status == null || changing) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onMark(row, IntakeStatus.TAKEN) }) { Text(stringResource(R.string.plan_taken)) }
                OutlinedButton(onClick = { onMark(row, IntakeStatus.MISSED) }) { Text(stringResource(R.string.plan_missed)) }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(if (row.status == IntakeStatus.MISSED) R.string.plan_status_missed else R.string.plan_status_taken),
                    color = if (row.status == IntakeStatus.MISSED) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                TextButton(onClick = { changing = true }) { Text(stringResource(R.string.plan_change)) }
            }
        }
        Text(stringResource(R.string.plan_by, p.doctorName), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ExerciseCard(row: ExerciseRow, onDone: (Prescription) -> Unit, onStart: (Prescription) -> Unit) {
    val p = row.prescription
    val biofeedback = p.kind == PlanKind.BIOFEEDBACK
    PlanCard {
        Text((if (biofeedback) "🎯 " else "") + p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(
            if (biofeedback) stringResource(R.string.plan_bio_line, (p.level ?: 1).toPersianDigits(), (p.perWeek ?: 0).toPersianDigits(), row.doneLast7Days.toPersianDigits())
            else stringResource(R.string.plan_exercise_line, (p.perWeek ?: 0).toPersianDigits(), row.doneLast7Days.toPersianDigits()),
            style = MaterialTheme.typography.bodyMedium
        )
        p.instructions?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        row.lastScore?.let { Text(stringResource(R.string.plan_bio_last_score, it.toPersianDigits()), style = MaterialTheme.typography.bodyMedium) }
        if (biofeedback) {
            if (row.doneToday) {
                Text(stringResource(R.string.plan_exercise_done_today), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
            Button(onClick = { onStart(p) }) {
                Text(stringResource(if (row.doneToday) R.string.plan_bio_again else R.string.plan_bio_start))
            }
        } else if (row.doneToday) {
            Text(stringResource(R.string.plan_exercise_done_today), color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
        } else {
            Button(onClick = { onDone(p) }) { Text(stringResource(R.string.plan_exercise_done)) }
        }
    }
}

@Composable
private fun PlanCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { content() }
    }
}

/** On Android 13 and later reminders need the person's permission; asks for it with a button, not on its own. */
@Composable
private fun NotificationPermission() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    if (granted) return
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.plan_notifications_off), style = MaterialTheme.typography.bodyMedium)
            Button(onClick = { launcher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                Text(stringResource(R.string.plan_notifications_allow))
            }
        }
    }
}
