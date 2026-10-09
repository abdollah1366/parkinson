package com.example.parkinson.ui.screens.sts

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
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
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

private val CHECKLIST = listOf(
    R.string.sts_check_chair,
    R.string.sts_check_floor,
    R.string.sts_check_supervision,
    R.string.sts_check_stop,
)

/**
 * Purpose, safety and the readiness checklist of the Five Times Sit-to-Stand test. Continue stays disabled
 * until every item is confirmed. The test is an assessment aid, not a diagnosis.
 */
@Composable
fun SitToStandIntroScreen(onContinueClicked: () -> Unit) {
    val checked = remember { mutableStateListOf<Boolean>().apply { repeat(CHECKLIST.size) { add(false) } } }
    val allChecked = checked.all { it }

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
                text = stringResource(R.string.test_sts_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.sts_intro_instruction),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )
            InfoRowCard(stringResource(R.string.duration_title), stringResource(R.string.sts_duration_value))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                shape = MaterialTheme.shapes.medium
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = stringResource(R.string.sts_safety_title),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = stringResource(R.string.sts_safety_text),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            ListCard(
                title = stringResource(R.string.sts_intro_steps_title),
                items = listOf(
                    R.string.sts_step_1, R.string.sts_step_2, R.string.sts_step_3,
                    R.string.sts_step_4, R.string.sts_step_5
                ).map { stringResource(it) },
                numbered = true
            )

            Text(
                text = stringResource(R.string.sts_checklist_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            CHECKLIST.forEachIndexed { index, res ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(checked = checked[index], onCheckedChange = { checked[index] = it })
                    Text(
                        text = stringResource(res),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }

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
            enabled = allChecked,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}
