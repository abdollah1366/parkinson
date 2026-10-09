package com.example.parkinson.ui.screens.speech

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.StepProgressIndicator
import com.example.parkinson.ui.screens.stability.InfoRowCard
import com.example.parkinson.ui.screens.stability.ListCard

/**
 * Introduction and privacy notice of the speech assessment. Shows exactly what is recorded, where it stays and what
 * is stored. Continue stays disabled until the person gives explicit consent for this visit.
 */
@Composable
fun SpeechIntroScreen(
    consent: Boolean,
    onConsentChanged: (Boolean) -> Unit,
    onContinueClicked: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            StepProgressIndicator(currentStep = 1, totalSteps = 3)
            Text(
                text = stringResource(R.string.test_speech_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.speech_intro_instruction),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            InfoRowCard(stringResource(R.string.duration_title), stringResource(R.string.speech_duration_value))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.speech_privacy_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(stringResource(R.string.speech_privacy_text), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.speech_privacy_not_stored), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.speech_privacy_no_transcript), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.speech_privacy_stored), style = MaterialTheme.typography.bodyMedium)
                }
            }

            ListCard(
                title = stringResource(R.string.gait_safety_title),
                items = listOf(stringResource(R.string.speech_safety_text)),
                numbered = false
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(checked = consent, onCheckedChange = onConsentChanged)
                Text(
                    text = stringResource(R.string.speech_consent_checkbox),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(start = 4.dp)
                )
            }
            if (!consent) {
                Text(
                    text = stringResource(R.string.speech_consent_required),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = stringResource(R.string.speech_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        PrimaryButton(
            text = stringResource(R.string.btn_continue),
            onClick = onContinueClicked,
            enabled = consent,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
