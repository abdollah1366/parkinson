package com.example.parkinson.ui.screens.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.assessment.AssessmentCatalog
import com.example.parkinson.assessment.AssessmentDefinition
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.ui.components.QualityChip
import com.example.parkinson.ui.components.assessmentTitle
import com.example.parkinson.ui.components.handLabel
import com.example.parkinson.ui.components.indexLine
import com.example.parkinson.ui.components.keyMetricsLine
import com.example.parkinson.ui.components.statusLabel
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.screens.splash.MedicalLogoIcon
import com.example.parkinson.viewmodel.Loadable

@Composable
fun HomeScreen(
    latestAssessment: Loadable<AssessmentResult?> = Loadable.Loaded(null),
    tests: List<AssessmentDefinition> = AssessmentCatalog.all,
    onOpenAssessment: (AssessmentResult) -> Unit = {},
    onOpenHistory: () -> Unit = {},
    /** Opens the complete assessment catalog (test selection). */
    onStartAssessmentClicked: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Spacer(modifier = Modifier.height(12.dp))

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.home_greeting),
                style = MaterialTheme.typography.headlineLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        MotorAssessmentsCard(tests, onStartAssessmentClicked)

        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.section_last_assessment),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() }
            )
            Spacer(modifier = Modifier.height(12.dp))

            val latest = (latestAssessment as? Loadable.Loaded)?.value
            if (latest == null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = MaterialTheme.shapes.medium
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = stringResource(
                                if (latestAssessment is Loadable.Loading) R.string.result_loading else R.string.empty_assessment_history
                            ),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LatestAssessmentCard(latest, onOpen = { onOpenAssessment(latest) }, onOpenHistory = onOpenHistory)
            }
        }
    }
}

@Composable
private fun MotorAssessmentsCard(tests: List<AssessmentDefinition>, onOpenCatalog: () -> Unit) {
    val available = tests.count { it.isAvailable }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    MedicalLogoIcon(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(30.dp))
                }
                Spacer(modifier = Modifier.width(16.dp))
                Column {
                    Text(
                        text = stringResource(R.string.home_catalog_title),
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = stringResource(
                            R.string.catalog_summary,
                            PersianFormat.integer(available),
                            PersianFormat.integer(tests.size - available)
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onOpenCatalog,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    text = stringResource(R.string.btn_start_assessment),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            // Compact roadmap: every test with its status, written out (not color only).
            tests.forEach { test ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(test.icon, modifier = Modifier.clearAndSetSemantics { })
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(test.title),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = statusLabel(test.status),
                        style = MaterialTheme.typography.labelLarge,
                        color = if (test.isAvailable) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

        }
    }
}

@Composable
private fun LatestAssessmentCard(
    assessment: AssessmentResult,
    onOpen: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(
                text = assessmentTitle(assessment.type),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = PersianFormat.dateTime(assessment.timestampEpochMs),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                assessment.hand?.let {
                    Text(text = handLabel(it), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = keyMetricsLine(assessment),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = indexLine(assessment),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(10.dp))
            QualityChip(assessment.quality.status)
            Spacer(modifier = Modifier.height(16.dp))
            Button(
                onClick = onOpen,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                shape = MaterialTheme.shapes.medium
            ) {
                Text(
                    text = stringResource(R.string.btn_view_result),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            TextButton(
                onClick = onOpenHistory,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
            ) {
                Text(text = stringResource(R.string.btn_view_history), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}
