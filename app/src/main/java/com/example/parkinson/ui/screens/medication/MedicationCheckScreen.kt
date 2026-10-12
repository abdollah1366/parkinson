package com.example.parkinson.ui.screens.medication

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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.medication.MedicationState

/**
 * Asked before the tests of a person who takes Parkinson's medication: is it working now (ON), worn off or not
 * taken yet (OFF), or unsure. The answer is saved with each result so ON and OFF results are not mixed up.
 */
@Composable
fun MedicationCheckScreen(onChoose: (MedicationState) -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.med_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        Text(stringResource(R.string.med_intro), style = MaterialTheme.typography.bodyLarge)
        Option(R.string.med_on, R.string.med_on_text) { onChoose(MedicationState.ON) }
        Option(R.string.med_off, R.string.med_off_text) { onChoose(MedicationState.OFF) }
        Option(R.string.med_unsure, R.string.med_unsure_text) { onChoose(MedicationState.UNSURE) }
    }
}

@Composable
private fun Option(title: Int, text: Int, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Text(stringResource(text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The short label of a medication state: "ON", "OFF" or "نامشخص". */
@Composable
fun medicationLabel(state: MedicationState): String = stringResource(
    when (state) {
        MedicationState.ON -> R.string.med_label_on
        MedicationState.OFF -> R.string.med_label_off
        MedicationState.UNSURE -> R.string.med_label_unsure
    }
)
