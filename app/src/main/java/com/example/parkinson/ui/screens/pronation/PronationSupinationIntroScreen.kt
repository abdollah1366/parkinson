package com.example.parkinson.ui.screens.pronation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.ui.components.PrimaryButton
import com.example.parkinson.ui.components.StepProgressIndicator
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.screens.stability.InfoRowCard
import com.example.parkinson.ui.screens.stability.ListCard

/**
 * Instructions and hand selection for "آزمون چرخش دست" (PRONATION_SUPINATION). The hand is an
 * explicit choice: sensor data cannot tell the left hand from the right one.
 */
@Composable
fun PronationSupinationIntroScreen(
    selectedHand: SelectedHand?,
    onHandSelected: (SelectedHand) -> Unit,
    onStartClicked: () -> Unit
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
            StepProgressIndicator(currentStep = 1, totalSteps = 4)
            Text(
                text = stringResource(R.string.test_ps_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Text(
                text = stringResource(R.string.ps_intro_title_en),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = stringResource(R.string.ps_intro_instruction),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground
            )

            InfoRowCard(stringResource(R.string.duration_title), stringResource(R.string.ps_duration_value))

            ListCard(
                title = stringResource(R.string.ps_tips_title),
                items = listOf(R.string.ps_tip_1, R.string.ps_tip_2, R.string.ps_tip_3, R.string.ps_tip_4, R.string.ps_tip_5)
                    .map { stringResource(it) },
                numbered = false
            )
            ListCard(
                title = stringResource(R.string.hs_intro_steps_title),
                items = listOf(R.string.ps_step_1, R.string.ps_step_2, R.string.ps_step_3, R.string.ps_step_4)
                    .map { stringResource(it) },
                numbered = true
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f)),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = stringResource(R.string.ps_intro_disclaimer),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.padding(16.dp)
                )
            }

            Text(
                text = stringResource(R.string.ps_select_hand),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableGroup(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                listOf(SelectedHand.RIGHT, SelectedHand.LEFT).forEach { hand ->
                    HandOption(hand, selected = hand == selectedHand, onClick = { onHandSelected(hand) }, modifier = Modifier.weight(1f))
                }
            }
            if (selectedHand == null) {
                Text(
                    text = stringResource(R.string.ps_select_hand_first),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        PrimaryButton(
            text = stringResource(R.string.btn_start_test),
            onClick = onStartClicked,
            enabled = selectedHand != null,
            modifier = Modifier.padding(top = 8.dp)
        )
    }
}

@Composable
private fun HandOption(hand: SelectedHand, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val label = handLabel(hand)
    val description = stringResource(if (selected) R.string.ps_hand_selected_cd else R.string.ps_hand_not_selected_cd, label)
    Card(
        modifier = modifier
            .height(72.dp)
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .semantics { contentDescription = description },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface
        ),
        border = if (selected) null else CardDefaults.outlinedCardBorder(),
        shape = MaterialTheme.shapes.medium
    ) {
        Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                // Check mark: selection is never shown by color alone.
                text = (if (selected) "✓ " else "") + label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
