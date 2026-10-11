package com.example.parkinson.imu

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import com.example.parkinson.sensors.MotionStreams
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** The phone-placement protocol every IMU assessment uses. Stored with each result. */
const val PLACEMENT_FRONT_TROUSER_POCKET = "FRONT_TROUSER_POCKET"

/**
 * Facts about the sensor data of one recording, computed from the sensor timestamps: measured rates (not requested
 * ones), dropouts, events removed because they were duplicated or out of order, and events flagged unreliable.
 * No sample values are kept here.
 */
data class ImuDataInfo(
    val accelerometerRateHz: Double,
    val gyroscopeRateHz: Double,
    val dropoutCount: Int,
    val timestampIssues: Int,
    val receivedEvents: Int,
    val unreliablePercent: Double,
) {
    /** Share of events removed because their timestamps were duplicated or out of order (percent). */
    val timestampIssuePercent: Double get() = if (receivedEvents == 0) 0.0 else 100.0 * timestampIssues / receivedEvents

    companion object {
        /** Builds the facts from the raw events of a recording, using the same cleaning as the analysis. */
        fun of(raw: List<MotionSample>, clean: List<MotionSample>, plannedMs: Long): ImuDataInfo {
            val accRaw = raw.filter { it.type == MotionSensorType.ACCELEROMETER }
            val accClean = clean.filter { it.type == MotionSensorType.ACCELEROMETER }
            val gyroRaw = raw.filter { it.type == MotionSensorType.GYROSCOPE }
            val gyroClean = clean.filter { it.type == MotionSensorType.GYROSCOPE }
            val accStats = MotionStreams.streamStats(MotionSensorType.ACCELEROMETER, accRaw, accClean, plannedMs)
            val gyroStats = MotionStreams.streamStats(MotionSensorType.GYROSCOPE, gyroRaw, gyroClean, plannedMs)
            return ImuDataInfo(
                accelerometerRateHz = accStats.samplingRateHz,
                gyroscopeRateHz = gyroStats.samplingRateHz,
                dropoutCount = accStats.dropoutCount + gyroStats.dropoutCount,
                timestampIssues = raw.size - clean.size,
                receivedEvents = raw.size,
                unreliablePercent = if (raw.isEmpty()) 0.0 else 100.0 * raw.count { it.unreliable } / raw.size,
            )
        }
    }
}

/**
 * Quality of an IMU result, from the grid coverage, the timestamp problems and the amount of usable events. Only
 * VALID and LOW_QUALITY recordings can be stored.
 */
object ImuQuality {
    const val VALID_COVERAGE_PERCENT = 90.0
    const val LOW_COVERAGE_PERCENT = 70.0
    const val MAX_TIMESTAMP_ISSUE_PERCENT = 5.0

    fun status(coveragePercent: Double, data: ImuDataInfo, usable: Boolean): QualityStatus = when {
        data.timestampIssuePercent > MAX_TIMESTAMP_ISSUE_PERCENT -> QualityStatus.INVALID
        !usable -> QualityStatus.INSUFFICIENT_DATA
        coveragePercent >= VALID_COVERAGE_PERCENT -> QualityStatus.VALID
        coveragePercent >= LOW_COVERAGE_PERCENT -> QualityStatus.LOW_QUALITY
        else -> QualityStatus.INSUFFICIENT_DATA
    }
}

/**
 * Stored result of the IMU sit-to-stand test. Only completed tests (the protocol's repetitions, each with rotation
 * evidence and no data gap) are stored. Times are relative to the first analysed sample (ms). Derived values are
 * computed from the stored repetitions.
 */
data class ImuSitToStandResult(
    override val assessmentId: String,
    override val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val sessionId: String,
    val protocolId: String,
    val placement: String,
    val targetRepetitions: Int,
    val repetitions: List<ImuRepetition>,
    val rejections: List<Rejection>,
    val invalidIntervals: List<InvalidInterval>,
    val coveragePercent: Double,
    val data: ImuDataInfo,
    val qualityStatus: QualityStatus,
    override val algorithmVersion: String,
    val configSummary: String,
    override val scoringVersion: String,
) : AssessmentResult {

    override val type: AssessmentType get() = AssessmentType.SIT_TO_STAND
    override val hand: SelectedHand? get() = null
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, coveragePercent.roundToInt().coerceIn(0, 100))
    override val performanceIndex: Int? get() = null

    /** Measured: from the first repetition's start to the last one's sustained seated posture (ms). */
    val totalTimeMs: Double get() = repetitions.last().seatedReachedMs - repetitions.first().standOnsetMs

    /** Derived: mean duration of a repetition (ms). */
    val meanRepetitionMs: Double? get() = repetitions.map { it.durationMs }.takeIf { it.isNotEmpty() }?.average()

    /** Derived: variability of the repetition durations, percent (standard deviation / mean). Null with fewer than two. */
    val repetitionCvPercent: Double? get() = cv(repetitions.map { it.durationMs })

    /** Derived: mean standing-up phase (ms). */
    val meanStandingUpMs: Double? get() = repetitions.map { it.standingUpMs }.takeIf { it.isNotEmpty() }?.average()

    /** Derived: mean sitting-down phase (ms). */
    val meanSittingDownMs: Double? get() = repetitions.map { it.sittingDownMs }.takeIf { it.isNotEmpty() }?.average()

    /** Derived: mean pause between one repetition's end and the next one's start (ms). */
    val meanPauseMs: Double? get() = repetitions.zipWithNext { a, b -> b.standOnsetMs - a.seatedReachedMs }.takeIf { it.isNotEmpty() }?.average()

    companion object {
        fun cv(values: List<Double>): Double? {
            if (values.size < 2) return null
            val mean = values.average()
            if (mean <= 0.0) return null
            val sd = sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
            return 100.0 * sd / mean
        }
    }
}

/**
 * Stored result of the IMU walking test. A trial is stored only when the quality allows it. Cadence and step-interval
 * statistics are null when fewer steps than the minimum passed their checks. Left/right timing, stride length, walking
 * speed and turning-based gait measures are not computed here and are not stored.
 */
data class ImuGaitResult(
    override val assessmentId: String,
    override val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val sessionId: String,
    val protocolId: String,
    val placement: String,
    val plannedWalkingMs: Double,
    /** Duration of walking bouts with plausible steps (ms). */
    val validWalkingMs: Double,
    val steps: Int,
    val bouts: Int,
    val cadenceStepsPerMinute: Double?,
    val meanStepIntervalMs: Double?,
    val stepIntervalCvPercent: Double?,
    val turningMs: Double,
    val turnRejectedSteps: Int,
    val implausibleRejectedPeaks: Int,
    val coveragePercent: Double,
    val invalidIntervals: List<InvalidInterval>,
    val data: ImuDataInfo,
    val qualityStatus: QualityStatus,
    override val algorithmVersion: String,
    val configSummary: String,
    override val scoringVersion: String,
) : AssessmentResult {

    override val type: AssessmentType get() = AssessmentType.GAIT
    override val hand: SelectedHand? get() = null
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, coveragePercent.roundToInt().coerceIn(0, 100))
    override val performanceIndex: Int? get() = null

    /** Plantar pressure is not measured; the phone is the only sensor. */
    val plantarPressureMeasured: Boolean get() = false
}
