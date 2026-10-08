package com.example.parkinson.pronation

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.StreamStats

/**
 * One recording. [samples]: everything received during RECORDING. [baselineSamples]: samples from
 * the preparation period (phone held still). [accelerometerAvailable] = the device has an
 * accelerometer; without it the test runs on the gyroscope alone and the result is marked
 * LOW_QUALITY.
 */
data class PronationRecording(
    val samples: List<MotionSample>,
    val baselineSamples: List<MotionSample>,
    val plannedDurationMs: Long,
    val hand: SelectedHand,
    val accelerometerAvailable: Boolean = true
)

enum class BaselineStatus {
    /** Phone was still during preparation: gyroscope bias, noise and gravity estimated. */
    CALIBRATED,

    /** Phone moved during preparation: no bias estimate (drift is removed per recording instead). */
    NOT_STILL,

    /** No (usable) preparation samples. */
    NO_DATA
}

/** Initial orientation / baseline from the preparation period. Vectors in device coordinates. */
data class RotationBaseline(
    val status: BaselineStatus,
    /** deg/s per axis; zero unless [status] is CALIBRATED. */
    val gyroBiasDegS: List<Double>,
    /** Angular-velocity RMS around the bias while still (sensor noise + physiological tremor). */
    val noiseDegS: Double?,
    /** m/s^2 mean accelerometer vector (initial orientation); null without accelerometer data. */
    val gravity: List<Double>?
)

/**
 * Rotation direction of one movement. Assigned only when the main rotation axis lies along the
 * phone's long axis (standard grip, forearm axis); mirrored for the left hand. Otherwise UNKNOWN.
 */
enum class RotationDirection { PRONATION, SUPINATION, UNKNOWN }

enum class MovementRejection {
    /** A sensor dropout covers too much of the movement. */
    SENSOR_GAP,

    /** Longer than half the maximum cycle duration: not a repetitive movement (e.g. a pause). */
    TOO_SLOW
}

/** One movement between two turning points (one pronation or one supination). Times in s from window start. */
data class RotationMovement(
    val startS: Double,
    /** Time of the highest angular velocity. */
    val peakS: Double,
    val endS: Double,
    val amplitudeDeg: Double,
    val peakAngularVelocityDegS: Double,
    val meanAngularVelocityDegS: Double,
    val direction: RotationDirection,
    /** null = valid. */
    val rejection: MovementRejection?
) {
    val valid: Boolean get() = rejection == null
    val durationS: Double get() = endS - startS
    val midS: Double get() = (startS + endS) / 2.0
}

/** One complete cycle = two consecutive movements (pronation + supination), never overlapping. */
data class RotationCycle(
    val startS: Double,
    val peakS: Double,
    val endS: Double,
    /** Mean of the two movement amplitudes. */
    val amplitudeDeg: Double,
    val peakAngularVelocityDegS: Double,
    val meanAngularVelocityDegS: Double,
    val firstDirection: RotationDirection,
    val valid: Boolean
) {
    val durationS: Double get() = endS - startS
}

enum class TrendSegment { EARLY, MIDDLE, LATE }

/** Raw measurements of one third of the recording (movements assigned by their midpoint). */
data class SegmentMetrics(
    val segment: TrendSegment,
    val movementCount: Int,
    /** null when the segment has fewer movements than the scoring minimum. */
    val cycleRateHz: Double?,
    val medianAmplitudeDeg: Double?,
    val amplitudeCvPercent: Double?,
    val medianPeakVelocityDegS: Double?,
    val velocityCvPercent: Double?,
    /** 2 x median movement duration. */
    val medianCycleDurationMs: Double?,
    /** CV of movement durations (rhythm within the segment). */
    val durationCvPercent: Double?,
    val withinTolerancePercent: Double?
)

enum class TrendDirection { STABLE, INCREASED, DECREASED }

/** One raw measure in the early, middle and late third. */
data class MetricTrend(
    val early: Double?,
    val middle: Double?,
    val late: Double?,
    /** (late - early) / early in percent; null when early or late is missing. */
    val changePercent: Double?,
    val direction: TrendDirection?
)

data class MeasureTrends(
    val amplitudeDeg: MetricTrend,
    val peakVelocityDegS: MetricTrend,
    val cycleDurationMs: MetricTrend
)

/**
 * Raw metrics (no scores). Angles in degrees, angular velocity in deg/s about the main rotation
 * axis, times in ms. CVs are percentages and null with fewer than 3 values.
 */
data class PronationMetrics(
    val movementCount: Int,
    val cycleCount: Int,
    val validCycleCount: Int,
    val cyclesPerSecond: Double,
    val cyclesPerMinute: Double,
    val meanCycleDurationMs: Double?,
    val medianCycleDurationMs: Double?,
    val cycleDurationCvPercent: Double?,
    val meanAmplitudeDeg: Double?,
    val medianAmplitudeDeg: Double?,
    val amplitudeCvPercent: Double?,
    /** Mean |w| while moving (first to last turning point, dropouts excluded). */
    val meanAngularVelocityDegS: Double,
    /** Median of the per-movement peaks: the typical peak speed. */
    val peakAngularVelocityDegS: Double?,
    /** Highest per-movement peak. */
    val maxAngularVelocityDegS: Double?,
    val velocityCvPercent: Double?,
    val angularVelocityRmsDegS: Double,
    val pauseCount: Int,
    val totalPauseMs: Double,
    val longestPauseMs: Double,
    /** Share of movements within +-30 % of the typical amplitude and duration. */
    val withinTolerancePercent: Double?,
    /** First-to-last turning point as a share of the window. */
    val movementCoveragePercent: Double,
    /** High-frequency residual of the rotation signal (raw - filtered), deg/s RMS. */
    val noiseLevelDegS: Double,
    /** RMS of the linear (gravity-removed) acceleration; null without accelerometer. */
    val accelerationRms: Double?,
    val accMagnitudeMean: Double?,
    val dominantFrequencyHz: Double?,
    val rotationAxisSharePercent: Double,
    /** Main rotation axis along the phone's long axis (standard grip): direction labels meaningful. */
    val axisAlignedWithForearm: Boolean,
    val segments: List<SegmentMetrics>,
    val measureTrends: MeasureTrends
)

enum class PronationQualityIssue(val severity: QualityStatus) {
    NO_GYROSCOPE_DATA(QualityStatus.INVALID),
    SAMPLING_RATE_TOO_LOW(QualityStatus.INVALID),
    RECORDING_INCOMPLETE(QualityStatus.INVALID),
    EXCESSIVE_GAPS(QualityStatus.INVALID),
    TOO_MANY_INVALID_SAMPLES(QualityStatus.INVALID),

    TOO_FEW_SAMPLES(QualityStatus.INSUFFICIENT_DATA),
    USABLE_DURATION_TOO_SHORT(QualityStatus.INSUFFICIENT_DATA),
    NO_MOVEMENT_DETECTED(QualityStatus.INSUFFICIENT_DATA),
    TOO_FEW_VALID_CYCLES(QualityStatus.INSUFFICIENT_DATA),

    ACCELEROMETER_UNAVAILABLE(QualityStatus.LOW_QUALITY),
    LOW_SAMPLING_RATE(QualityStatus.LOW_QUALITY),
    GAPS_PRESENT(QualityStatus.LOW_QUALITY),
    INVALID_SAMPLES_PRESENT(QualityStatus.LOW_QUALITY),
    SENSOR_UNRELIABLE(QualityStatus.LOW_QUALITY),
    STREAMS_MISALIGNED(QualityStatus.LOW_QUALITY),
    HIGH_NOISE(QualityStatus.LOW_QUALITY),
    LOW_VALID_CYCLE_PERCENTAGE(QualityStatus.LOW_QUALITY),
    LOW_MOVEMENT_COVERAGE(QualityStatus.LOW_QUALITY),
    ROTATION_AXIS_UNSTABLE(QualityStatus.LOW_QUALITY),
    GRAVITY_IMPLAUSIBLE(QualityStatus.LOW_QUALITY)
}

data class PronationQualityReport(
    val status: QualityStatus,
    /** Most severe first. */
    val issues: List<PronationQualityIssue>,
    /** 0..100 "کیفیت داده": technical recording quality, adjusted for valid cycles. */
    val qualityPercentage: Int,
    /** Synchronized window minus time lost in gaps. */
    val usableDurationMs: Double,
    val validCyclePercent: Double?
) {
    val primaryIssue: PronationQualityIssue? get() = issues.firstOrNull()
    val isUsable: Boolean get() = status == QualityStatus.VALID || status == QualityStatus.LOW_QUALITY
}

enum class PerformanceTrendState { STABLE, IMPROVING, DECLINING, INSUFFICIENT_DATA }

/** "روند عملکرد در طول آزمون": segment scores and their change. Not disease progression. */
data class PerformanceTrend(
    val state: PerformanceTrendState,
    val earlyScore: Int?,
    val middleScore: Int?,
    val lateScore: Int?,
    /** late - early, points. */
    val scoreChange: Int?
)

/** Component scores 0..100 ("software reference", not normative). null = could not be measured. */
data class ComponentScores(
    val speed: Int,
    /** = movement regularity. */
    val rhythm: Int?,
    val amplitude: Int,
    val consistency: Int?,
    val trend: Int?
)

enum class ReliabilityLevel { RELIABLE, LIMITED, NOT_RELIABLE }

/**
 * "شاخص عملکرد حرکتی" (Motor Performance Index), 0..100. An internal software index: NOT a
 * diagnosis, NOT a disease probability, NOT clinically validated.
 */
data class PronationSupinationPerformanceScore(
    val total: Int,
    val components: ComponentScores,
    val trend: PerformanceTrend,
    val reliability: ReliabilityLevel,
    val referenceName: String,
    val scoringVersion: String = PronationSupinationVersions.SCORING_VERSION,
    val isClinicallyValidated: Boolean = false
)

/** Software interpretation bands, exact and non-overlapping over 0..100. */
enum class MotorPerformanceBand(val min: Int, val max: Int) {
    VERY_LOW(0, 19),
    SIGNIFICANTLY_REDUCED(20, 39),
    REDUCED(40, 59),
    ACCEPTABLE(60, 79),
    GOOD(80, 100);

    companion object {
        fun forScore(score: Int): MotorPerformanceBand {
            require(score in 0..100) { "score out of range: $score" }
            return entries.first { score in it.min..it.max }
        }
    }
}

/** Language-neutral interpretation codes; the UI turns them into Persian sentences. */
enum class InterpretationNote {
    SPEED_AND_RHYTHM_GOOD,
    SPEED_OR_RHYTHM_LOWER,
    AMPLITUDE_LOWER,
    PAUSES_OBSERVED,
    TREND_DECLINING,
    TREND_IMPROVING,
    QUALITY_GOOD,
    QUALITY_LIMITED,
    GYROSCOPE_ONLY,
    REPEAT_RECOMMENDED
}

data class PronationInterpretation(
    /** null when no score could be computed. */
    val band: MotorPerformanceBand?,
    val reliability: ReliabilityLevel,
    val notes: List<InterpretationNote>
)

/** Everything the pipeline produced for one recording. */
data class PronationAnalysis(
    val accel: StreamStats,
    val gyro: StreamStats,
    val accelerometerAvailable: Boolean,
    val baseline: RotationBaseline,
    val movements: List<RotationMovement>,
    val cycles: List<RotationCycle>,
    /** null when the streams are too short to process. */
    val metrics: PronationMetrics?,
    val quality: PronationQualityReport,
    /** VALID and LOW_QUALITY only (LOW_QUALITY with LIMITED reliability). */
    val score: PronationSupinationPerformanceScore?,
    val interpretation: PronationInterpretation,
    /** Overlap of both streams (gyroscope only without accelerometer). */
    val analyzedDurationMs: Double,
    /** Hysteresis actually used for turning points, degrees. */
    val hysteresisDeg: Double,
    /** Filtered angular velocity about the main axis, deg/s, at [PronationDetectionConfig.traceHz]. */
    val velocityTrace: List<Float>
)
