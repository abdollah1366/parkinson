package com.example.parkinson.ui.screens.pronation

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
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.StepProgressIndicator
import com.example.parkinson.ui.screens.stability.InfoRowCard
import com.example.parkinson.ui.screens.stability.ListCard

/** Instructions for "چرخش دست" (PronationSupinationTest). */
@Composable
fun PronationSupinationIntroScreen(onContinueClicked: () -> Unit) {
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
            StepProgressIndicator(currentStep = 1, totalSteps = 4)
            Text(
                text = stringResource(R.string.ps_intro_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.ps_intro_instruction),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            InfoRowCard(stringResource(R.string.duration_title), stringResource(R.string.ps_duration_value))

            ListCard(
                title = stringResource(R.string.hs_intro_steps_title),
                items = listOf(R.string.ps_step_1, R.string.ps_step_2, R.string.ps_step_3, R.string.ps_step_4, R.string.ps_step_5)
                    .map { stringResource(it) },
                numbered = true
            )
            ListCard(
                title = stringResource(R.string.ps_measures_title),
                items = listOf(R.string.ps_measure_1, R.string.ps_measure_2, R.string.ps_measure_3).map { stringResource(it) },
                numbered = false
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(R.string.ps_safety_note),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Text(
                        text = stringResource(R.string.result_disclaimer),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
        PrimaryButton(
            text = stringResource(R.string.btn_continue),
            onClick = onContinueClicked,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
