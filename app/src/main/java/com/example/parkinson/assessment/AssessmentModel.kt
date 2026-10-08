package com.example.parkinson.assessment

import com.example.parkinson.model.SelectedHand

/** Every motor assessment the app knows about, implemented or not. [id] is stable and stored. */
enum class AssessmentType(val id: String) {
    FINGER_TAPPING("finger_tapping"),
    HAND_STABILITY("hand_stability"),
    PRONATION_SUPINATION("pronation_supination"),
    HAND_OPEN_CLOSE("hand_open_close"),
    RAPID_ALTERNATING("rapid_alternating_movements"),
    RESTING_TREMOR("resting_hand_tremor"),
    GAIT("gait"),
    SIT_TO_STAND("sit_to_stand"),
    SPEECH("speech"),
    DUAL_TASK("dual_task");

    companion object {
        fun fromId(id: String?): AssessmentType? = entries.firstOrNull { it.id == id }
    }
}

/** Implementation state. Only [AVAILABLE] tests can be started or produce measurements. */
enum class AssessmentStatus {
    AVAILABLE,

    /** Planned; no measurement is produced yet. */
    IN_DEVELOPMENT,

    /** Protocol not clinically validated; research and development only, no measurement yet. */
    RESEARCH;

    val canStart: Boolean get() = this == AVAILABLE
}

/** Hardware or bundled model a test needs before it may start. */
enum class SensorRequirement {
    CAMERA,
    HAND_LANDMARK_MODEL,
    ACCELEROMETER,
    GYROSCOPE,
    MICROPHONE
}

/** Recording quality, ordered from best to worst. Shared by all implemented tests. */
enum class QualityStatus { VALID, LOW_QUALITY, INSUFFICIENT_DATA, INVALID }

/** Technical quality of one recording; describes the measurement, never the person. */
data class AssessmentQuality(
    val status: QualityStatus,
    /** 0..100 */
    val score: Int
)

/**
 * Common view of a stored result of any assessment type, used by Home and History.
 * Each test keeps its own detailed result type and engine; this is only the shared summary.
 */
interface AssessmentResult {
    val assessmentId: String
    val type: AssessmentType
    val timestampEpochMs: Long

    /** null for tests that do not use a specific hand. */
    val hand: SelectedHand?
    val quality: AssessmentQuality

    /** Versioned engineering index 0..100 ("شاخص عملکرد"); null when quality was not VALID. */
    val performanceIndex: Int?
    val algorithmVersion: String
    val scoringVersion: String
}
