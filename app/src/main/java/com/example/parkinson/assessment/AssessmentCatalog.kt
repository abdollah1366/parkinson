package com.example.parkinson.assessment

import androidx.annotation.StringRes
import com.example.parkinson.R
import com.example.parkinson.navigation.Screen

/**
 * Everything the UI needs to present one assessment. Texts are string resources (Persian RTL);
 * [englishName] is the internal/technical name used in logs and documentation.
 */
data class AssessmentDefinition(
    val type: AssessmentType,
    @param:StringRes val title: Int,
    val englishName: String,
    @param:StringRes val description: Int,
    @param:StringRes val purpose: Int,
    val sensors: List<SensorRequirement>,
    val status: AssessmentStatus,
    /** Recording length in seconds; null while the protocol is not defined. */
    val durationSeconds: Int?,
    val requiresHandSelection: Boolean,
    /** Decorative card icon (not read by TalkBack). */
    val icon: String,
    /** First screen of the test flow; null for tests that cannot be started. */
    val startRoute: String?,
    /** Route that follows the sensor check; null when there is no flow. */
    val afterSensorCheckRoute: String?,
    /** Used when present; the test can run without them (the result says so). */
    val optionalSensors: List<SensorRequirement> = emptyList(),
    /** Test-specific wording for a missing sensor (overrides the generic message). */
    val missingSensorMessages: Map<SensorRequirement, Int> = emptyMap()
) {
    /** Required and optional sensors, for display. */
    val allSensors: List<SensorRequirement> get() = sensors + optionalSensors

    val id: String get() = type.id
    val isAvailable: Boolean get() = status.canStart && startRoute != null
}

/**
 * Single source of truth for the motor-assessment suite. Every planned test is listed so the
 * patient sees the full roadmap. Only AVAILABLE tests can start; the others never produce a
 * measurement.
 */
object AssessmentCatalog {

    val all: List<AssessmentDefinition> = listOf(
        AssessmentDefinition(
            type = AssessmentType.FINGER_TAPPING,
            title = R.string.test_ft_title,
            englishName = "Finger Tapping",
            description = R.string.test_ft_desc,
            purpose = R.string.test_ft_purpose,
            sensors = listOf(SensorRequirement.CAMERA, SensorRequirement.HAND_LANDMARK_MODEL),
            status = AssessmentStatus.AVAILABLE,
            durationSeconds = 10,
            requiresHandSelection = true,
            icon = "✋",
            startRoute = Screen.FingerTappingIntro.route,
            afterSensorCheckRoute = Screen.FingerTappingPreparation.route
        ),
        AssessmentDefinition(
            type = AssessmentType.HAND_STABILITY,
            title = R.string.test_hs_title,
            englishName = "Hand Holding / Postural Stability",
            description = R.string.test_hs_desc,
            purpose = R.string.test_hs_purpose,
            sensors = listOf(SensorRequirement.ACCELEROMETER, SensorRequirement.GYROSCOPE),
            status = AssessmentStatus.AVAILABLE,
            durationSeconds = 15,
            requiresHandSelection = true,
            icon = "📱",
            startRoute = Screen.HandStabilityIntro.route,
            afterSensorCheckRoute = Screen.HandStabilityTest.route
        ),
        AssessmentDefinition(
            type = AssessmentType.PRONATION_SUPINATION,
            title = R.string.test_ps_title,
            englishName = "Pronation / Supination Test (PRONATION_SUPINATION)",
            description = R.string.test_ps_desc,
            purpose = R.string.test_ps_purpose,
            // The gyroscope is the primary sensor; the accelerometer only supports the analysis.
            sensors = listOf(SensorRequirement.GYROSCOPE),
            optionalSensors = listOf(SensorRequirement.ACCELEROMETER),
            missingSensorMessages = mapOf(SensorRequirement.GYROSCOPE to R.string.ps_sensor_missing_gyroscope),
            status = AssessmentStatus.AVAILABLE,
            // 5 s preparation + 3 s countdown + 10 s recording.
            durationSeconds = 18,
            requiresHandSelection = true,
            icon = "🔄",
            startRoute = Screen.PronationSupinationIntro.route,
            afterSensorCheckRoute = Screen.PronationSupinationTest.route
        ),
        AssessmentDefinition(
            type = AssessmentType.HAND_OPEN_CLOSE,
            title = R.string.test_oc_title,
            englishName = "Hand Opening / Closing",
            description = R.string.test_oc_desc,
            purpose = R.string.test_oc_purpose,
            sensors = listOf(SensorRequirement.CAMERA, SensorRequirement.HAND_LANDMARK_MODEL),
            status = AssessmentStatus.AVAILABLE,
            durationSeconds = 10,
            requiresHandSelection = true,
            icon = "🖐",
            startRoute = Screen.HandOpenCloseIntro.route,
            afterSensorCheckRoute = Screen.HandOpenCloseTest.route
        ),
        AssessmentDefinition(
            type = AssessmentType.RESTING_TREMOR,
            title = R.string.test_rt_title,
            englishName = "Resting Hand Tremor",
            description = R.string.test_rt_desc,
            purpose = R.string.test_rt_purpose,
            sensors = listOf(SensorRequirement.CAMERA, SensorRequirement.HAND_LANDMARK_MODEL),
            status = AssessmentStatus.AVAILABLE,
            // Recording length; a 3 s preparation countdown precedes it.
            durationSeconds = 15,
            requiresHandSelection = true,
            icon = "🤲",
            startRoute = Screen.RestingTremorIntro.route,
            afterSensorCheckRoute = Screen.RestingTremorTest.route
        ),
        planned(
            AssessmentType.GAIT, R.string.test_gait_title, "Gait / Walking",
            R.string.test_gait_desc, R.string.test_gait_purpose,
            listOf(SensorRequirement.ACCELEROMETER, SensorRequirement.GYROSCOPE),
            durationSeconds = null, hand = false, icon = "🚶"
        ),
        planned(
            AssessmentType.SIT_TO_STAND, R.string.test_sts_title, "Sit-to-Stand",
            R.string.test_sts_desc, R.string.test_sts_purpose,
            listOf(SensorRequirement.ACCELEROMETER, SensorRequirement.GYROSCOPE),
            durationSeconds = null, hand = false, icon = "🪑"
        ),
        planned(
            AssessmentType.SPEECH, R.string.test_speech_title, "Speech / Voice",
            R.string.test_speech_desc, R.string.test_speech_purpose,
            listOf(SensorRequirement.MICROPHONE),
            durationSeconds = null, hand = false, icon = "🗣"
        ),
        planned(
            AssessmentType.DUAL_TASK, R.string.test_dual_title, "Dual-Task / Cognitive-Motor",
            R.string.test_dual_desc, R.string.test_dual_purpose,
            listOf(SensorRequirement.ACCELEROMETER, SensorRequirement.GYROSCOPE),
            durationSeconds = null, hand = false, icon = "🧠"
        )
    )

    val available: List<AssessmentDefinition> get() = all.filter { it.isAvailable }

    operator fun get(type: AssessmentType): AssessmentDefinition = all.first { it.type == type }

    fun byId(id: String?): AssessmentDefinition? = AssessmentType.fromId(id)?.let { get(it) }

    /** Next available test after [type] in catalog order, wrapping around; null if there is none. */
    fun nextAvailableAfter(type: AssessmentType): AssessmentDefinition? {
        val list = available
        val index = list.indexOfFirst { it.type == type }
        if (list.size < 2 && index >= 0) return null
        return if (index < 0) list.firstOrNull() else list[(index + 1) % list.size]
    }

    private fun planned(
        type: AssessmentType,
        @StringRes title: Int,
        englishName: String,
        @StringRes description: Int,
        @StringRes purpose: Int,
        sensors: List<SensorRequirement>,
        durationSeconds: Int?,
        hand: Boolean,
        icon: String,
        status: AssessmentStatus = AssessmentStatus.IN_DEVELOPMENT
    ) = AssessmentDefinition(
        type = type,
        title = title,
        englishName = englishName,
        description = description,
        purpose = purpose,
        sensors = sensors,
        status = status,
        durationSeconds = durationSeconds,
        requiresHandSelection = hand,
        icon = icon,
        startRoute = null,
        afterSensorCheckRoute = null
    )
}
