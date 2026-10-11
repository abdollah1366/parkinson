package com.example.parkinson.ui.components

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.parkinson.R
import com.example.parkinson.assessment.AssessmentCatalog
import com.example.parkinson.assessment.AssessmentStatus
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.SensorRequirement
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.ui.theme.MedicalSuccess
import com.example.parkinson.ui.theme.MedicalSuccessContainer
import com.example.parkinson.ui.theme.MedicalWarningContainer

@Composable
fun handLabel(hand: SelectedHand): String = stringResource(
    if (hand == SelectedHand.LEFT) R.string.hand_left else R.string.hand_right
)

@Composable
fun qualityLabel(status: QualityStatus): String = stringResource(
    when (status) {
        QualityStatus.VALID -> R.string.label_quality_valid
        QualityStatus.LOW_QUALITY -> R.string.label_quality_low
        QualityStatus.INSUFFICIENT_DATA -> R.string.label_quality_insufficient
        QualityStatus.INVALID -> R.string.label_quality_invalid
    }
)

/** Small pill showing the recording quality. Text is always shown, so color is never the only cue. */
@Composable
fun QualityChip(status: QualityStatus, modifier: Modifier = Modifier) {
    val (background, foreground) = when (status) {
        QualityStatus.VALID -> MedicalSuccessContainer to MedicalSuccess
        QualityStatus.LOW_QUALITY -> MedicalWarningContainer to Color(0xFF7A5300)
        else -> MaterialTheme.colorScheme.errorContainer to MaterialTheme.colorScheme.error
    }
    Text(
        text = stringResource(R.string.label_quality, qualityLabel(status)),
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = foreground,
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

@Composable
fun statusLabel(status: AssessmentStatus): String = stringResource(
    when (status) {
        AssessmentStatus.AVAILABLE -> R.string.status_available
        AssessmentStatus.IN_DEVELOPMENT -> R.string.status_in_development
        AssessmentStatus.RESEARCH -> R.string.status_research
    }
)

@StringRes
fun sensorLabelRes(requirement: SensorRequirement): Int = when (requirement) {
    SensorRequirement.CAMERA -> R.string.sensor_camera
    SensorRequirement.HAND_LANDMARK_MODEL -> R.string.sensor_hand_model
    SensorRequirement.POSE_LANDMARK_MODEL -> R.string.sensor_pose_model
    SensorRequirement.ACCELEROMETER -> R.string.sensor_accelerometer
    SensorRequirement.GYROSCOPE -> R.string.sensor_gyroscope
    SensorRequirement.MICROPHONE -> R.string.sensor_microphone
}

/** Patient-facing message for a missing sensor, e.g. "حسگر ژیروسکوپ در این دستگاه در دسترس نیست." */
@StringRes
fun sensorMissingRes(requirement: SensorRequirement): Int = when (requirement) {
    SensorRequirement.CAMERA -> R.string.sensor_missing_camera
    SensorRequirement.HAND_LANDMARK_MODEL -> R.string.sensor_missing_hand_model
    SensorRequirement.POSE_LANDMARK_MODEL -> R.string.sensor_missing_pose_model
    SensorRequirement.ACCELEROMETER -> R.string.sensor_missing_accelerometer
    SensorRequirement.GYROSCOPE -> R.string.sensor_missing_gyroscope
    SensorRequirement.MICROPHONE -> R.string.sensor_missing_microphone
}

@Composable
fun sensorsLabel(sensors: List<SensorRequirement>): String {
    val separator = stringResource(R.string.sensor_separator)
    return sensors.map { stringResource(sensorLabelRes(it)) }.joinToString(separator)
}

@Composable
fun assessmentTitle(type: AssessmentType): String = stringResource(AssessmentCatalog[type].title)
