package com.example.parkinson.ui.screens.gait

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

/** Safety, positioning and the measures of the walking test; continues to the sensor check. */
@Composable
fun GaitIntroScreen(onContinueClicked: () -> Unit) {
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
            StepProgressIndicator(currentStep = 1, totalSteps = 2)
            Text(
                text = stringResource(R.string.test_gait_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.gait_intro_instruction),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            InfoRowCard(stringResource(R.string.duration_title), stringResource(R.string.gait_duration_value))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.gait_safety_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.gait_safety_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            ListCard(
                title = stringResource(R.string.gait_intro_steps_title),
                items = listOf(
                    R.string.gait_step_1, R.string.gait_step_2, R.string.gait_step_3,
                    R.string.gait_step_4, R.string.gait_step_5
                ).map { stringResource(it) },
                numbered = true
            )
            ListCard(
                title = stringResource(R.string.gait_measures_title),
                items = listOf(
                    R.string.gait_measure_1, R.string.gait_measure_2, R.string.gait_measure_3,
                    R.string.gait_measure_4
                ).map { stringResource(it) },
                numbered = false
            )
            ListCard(
                title = stringResource(R.string.gait_not_measured_title),
                items = listOf(R.string.gait_not_measured_1, R.string.gait_not_measured_2).map { stringResource(it) },
                numbered = false
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = stringResource(R.string.disclaimer_text),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        PrimaryButton(
            text = stringResource(R.string.btn_continue),
            onClick = onContinueClicked,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
