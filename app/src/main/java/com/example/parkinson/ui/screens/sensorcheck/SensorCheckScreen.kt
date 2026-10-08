package com.example.parkinson.ui.screens.sensorcheck

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.assessment.AssessmentDefinition
import com.example.parkinson.assessment.SensorCheckResult
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.sensorLabelRes
import com.example.parkinson.ui.components.sensorMissingRes
import com.example.parkinson.ui.theme.MedicalSuccess

/**
 * Shows which required sensors this device has. A test with a missing sensor cannot continue:
 * the patient gets a clear Persian message instead of a silent failure.
 */
@Composable
fun SensorCheckScreen(
    definition: AssessmentDefinition,
    result: SensorCheckResult,
    onContinue: () -> Unit,
    onBack: () -> Unit,
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.sensor_check_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.sensor_check_desc, stringResource(definition.title)),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            result.available.forEach { sensor ->
                Text(
                    text = "✓ " + stringResource(R.string.sensor_check_ok, stringResource(sensorLabelRes(sensor))),
                    style = MaterialTheme.typography.titleMedium,
                    color = MedicalSuccess,
                    fontWeight = FontWeight.SemiBold
                )
            }

            val summaryColor = if (result.canStart) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.errorContainer
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    // Announced by TalkBack as soon as the check result is shown.
                    .semantics { liveRegion = LiveRegionMode.Polite },
                colors = CardDefaults.cardColors(containerColor = summaryColor),
                shape = MaterialTheme.shapes.large
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (result.canStart) {
                        Text(
                            text = stringResource(R.string.sensor_check_all_ok),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.sensor_check_failed),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        result.missing.forEach { sensor ->
                            Text(
                                text = stringResource(sensorMissingRes(sensor)),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
        PrimaryButton(
            text = stringResource(R.string.btn_continue),
            onClick = onContinue,
            enabled = result.canStart
        )
        TextButton(
            onClick = onBack,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
        ) {
            Text(text = stringResource(R.string.btn_back), style = MaterialTheme.typography.titleMedium)
        }
    }
}
