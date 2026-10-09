package com.example.parkinson.ui.screens.speech

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.speech.SpeechTask
import com.example.parkinson.ui.components.StepProgressIndicator

/**
 * Choice of one speech task. Standardized tasks are labelled as such; spontaneous speech is labelled optional and
 * not standardized, so its results are never presented as comparable with the standard tasks.
 */
@Composable
fun SpeechTaskSelectionScreen(onTaskSelected: (SpeechTask) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        StepProgressIndicator(currentStep = 2, totalSteps = 3)
        Text(
            text = stringResource(R.string.speech_select_title),
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.onBackground,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Text(
            text = stringResource(R.string.speech_select_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground
        )
        SpeechTask.entries.forEach { task ->
            TaskCard(task = task, onClick = { onTaskSelected(task) })
        }
    }
}

@Composable
private fun TaskCard(task: SpeechTask, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = stringResource(titleRes(task)),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = stringResource(descRes(task)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(if (task.standardized) R.string.speech_standard_label else R.string.speech_optional_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
        }
    }
}

internal fun titleRes(task: SpeechTask): Int = when (task) {
    SpeechTask.SUSTAINED_VOWEL -> R.string.speech_task_vowel_title
    SpeechTask.REPEATED_SYLLABLE -> R.string.speech_task_syllable_title
    SpeechTask.READING -> R.string.speech_task_reading_title
    SpeechTask.SPONTANEOUS -> R.string.speech_task_spontaneous_title
}

internal fun descRes(task: SpeechTask): Int = when (task) {
    SpeechTask.SUSTAINED_VOWEL -> R.string.speech_task_vowel_desc
    SpeechTask.REPEATED_SYLLABLE -> R.string.speech_task_syllable_desc
    SpeechTask.READING -> R.string.speech_task_reading_desc
    SpeechTask.SPONTANEOUS -> R.string.speech_task_spontaneous_desc
}
