package com.example.parkinson.ui.screens.catalog

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.assessment.AssessmentCatalog
import com.example.parkinson.assessment.AssessmentDefinition
import com.example.parkinson.assessment.AssessmentStatus
import com.example.parkinson.ui.components.sensorsLabel
import com.example.parkinson.ui.components.statusLabel
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.theme.MedicalSuccess
import com.example.parkinson.ui.theme.MedicalSuccessContainer
import com.example.parkinson.ui.theme.MedicalWarningContainer

/**
 * Test selection: every test of the suite, so the patient sees the full roadmap.
 * Only available tests are clickable; the others show their development status and never start.
 */
@Composable
fun AssessmentCatalogScreen(
    tests: List<AssessmentDefinition> = AssessmentCatalog.all,
    onTestSelected: (AssessmentDefinition) -> Unit,
) {
    val availableCount = tests.count { it.isAvailable }
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(R.string.catalog_title),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
        }
        item {
            Text(
                text = stringResource(R.string.catalog_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item {
            Text(
                text = stringResource(
                    R.string.catalog_summary,
                    PersianFormat.integer(availableCount),
                    PersianFormat.integer(tests.size - availableCount)
                ),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold
            )
        }
        items(tests, key = { it.id }) { test ->
            TestCard(test, onClick = { onTestSelected(test) })
        }
    }
}

@Composable
private fun TestCard(test: AssessmentDefinition, onClick: () -> Unit) {
    val available = test.isAvailable
    val title = stringResource(test.title)
    val status = statusLabel(test.status)

    val clickModifier = if (available) {
        Modifier.clickable(role = Role.Button, onClickLabel = stringResource(R.string.btn_start), onClick = onClick)
    } else {
        // Not clickable at all: unimplemented tests can never start.
        Modifier.semantics { disabled() }
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(clickModifier)
            .semantics(mergeDescendants = true) { },
        colors = CardDefaults.cardColors(
            containerColor = if (available) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceVariant
        ),
        border = if (available) BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)) else null,
        shape = MaterialTheme.shapes.large,
        elevation = CardDefaults.cardElevation(defaultElevation = if (available) 2.dp else 0.dp)
    ) {
        Row(modifier = Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(
                        if (available) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
                    )
                    .clearAndSetSemantics { },
                contentAlignment = Alignment.Center
            ) {
                Text(text = test.icon, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = test.englishName,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(test.description),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = stringResource(R.string.catalog_purpose, stringResource(test.purpose)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = test.durationSeconds?.let {
                        "⏱ " + stringResource(R.string.catalog_duration, PersianFormat.integer(it))
                    } ?: stringResource(R.string.catalog_duration_unknown),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.catalog_sensors, sensorsLabel(test.sensors)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                StatusPill(test.status, status)
            }
        }
    }
}

/** Status is always written out, so color is never the only cue. */
@Composable
private fun StatusPill(status: AssessmentStatus, text: String) {
    val (background, foreground) = when (status) {
        AssessmentStatus.AVAILABLE -> MedicalSuccessContainer to MedicalSuccess
        else -> MedicalWarningContainer to MaterialTheme.colorScheme.onSurface
    }
    Text(
        text = (if (status == AssessmentStatus.AVAILABLE) "● " else "◌ ") + text,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = foreground,
        modifier = Modifier
            .padding(top = 4.dp)
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}
