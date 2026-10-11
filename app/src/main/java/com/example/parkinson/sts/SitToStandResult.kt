package com.example.parkinson.sts

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Timestamped per-frame data kept with the result. Times are relative to the first accepted sample (ms).
 * [frameValid] has one letter per sample: V = valid, I = invalid (see [SitToStandIssue]). Measurements of an
 * invalid frame are NaN.
 */
data class SitToStandSeries(
    val frameTimesMs: List<Long>,
    val frameValid: String,
    val hipRiseShinLengths: List<Double>,
    val kneeAngleDeg: List<Double>,
    val trunkLeanDeg: List<Double>,
) {
    companion object {
        val EMPTY = SitToStandSeries(emptyList(), "", emptyList(), emptyList(), emptyList())
    }
}

/**
 * The stored result of one completed Five Times Sit-to-Stand attempt.
 *
 * Measured values are the per-repetition phase times, peaks and the time series. Derived values (means,
 * variability, comparisons) are computed from the measured values by the properties below and are labelled as
 * derived in the UI. No score, severity or diagnosis is produced.
 *
 * Timing convention: total time = end of the last repetition (sustained seated posture) minus the start of the
 * first repetition (sustained start of the movement). Partial rises and incomplete repetitions are not counted.
 */
data class SitToStandResult(
    override val assessmentId: String,
    /** Wall-clock time the attempt ended (ms since epoch). */
    override val timestampEpochMs: Long,
    val startEpochMs: Long,
    val endEpochMs: Long,
    val protocolId: String,
    val targetRepetitions: Int,
    val side: SitToStandSide,
    val baseline: SeatedBaseline,
    /** Times relative to the first accepted sample (ms). */
    val repetitions: List<SitToStandRepetition>,
    val totalTimeMs: Long,
    val validFrames: Int,
    val invalidFrames: Int,
    val invalidFramesByIssue: Map<SitToStandIssue, Int>,
    val partialAttempts: Int,
    val qualityStatus: QualityStatus,
    val series: SitToStandSeries,
    override val algorithmVersion: String,
    val configSummary: String,
    override val scoringVersion: String,
) : AssessmentResult {

    override val type: AssessmentType get() = AssessmentType.SIT_TO_STAND

    /** Not tied to a selected hand. */
    override val hand: SelectedHand? get() = null

    val validFramePercent: Double
        get() = if (validFrames + invalidFrames == 0) 0.0 else 100.0 * validFrames / (validFrames + invalidFrames)

    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, validFramePercent.roundToInt().coerceIn(0, 100))

    /** No performance index: this test has no validated scoring. */
    override val performanceIndex: Int? get() = null

    /** Derived: mean duration of a repetition (ms). */
    val meanRepetitionMs: Double? get() = mean(repetitions.map { it.durationMs.toDouble() })

    /** Derived: standard deviation / mean of the repetition durations, percent (inter-repetition variability). Null with fewer than two repetitions. */
    val repetitionCvPercent: Double?
        get() {
            val values = repetitions.map { it.durationMs.toDouble() }
            if (values.size < 2) return null
            val m = values.average()
            if (m <= 0) return null
            return 100.0 * sqrt(values.sumOf { (it - m) * (it - m) } / values.size) / m
        }

    /** Derived: mean standing-up phase (ms). */
    val meanStandingUpMs: Double? get() = mean(repetitions.map { it.standingUpMs.toDouble() })

    /** Derived: mean sitting-down phase (ms). */
    val meanSittingDownMs: Double? get() = mean(repetitions.map { it.sittingDownMs.toDouble() })

    /** Derived: mean pause between the end of one repetition and the start of the next (ms). Null with one repetition. */
    val meanInterRepetitionMs: Double?
        get() = mean(repetitions.zipWithNext { a, b -> (b.standOnsetMs - a.seatedReachedMs).toDouble() })

    /** Measured per repetition: peak hip rise in shin lengths. Derived summary: the mean. */
    val meanPeakHipRiseShinLengths: Double? get() = mean(repetitions.map { it.peakHipRiseShinLengths })

    /** Measured per repetition: peak knee extension in degrees. Derived summary: the mean. */
    val meanPeakKneeExtensionDeg: Double? get() = mean(repetitions.map { it.peakKneeExtensionDeg })

    private fun mean(values: List<Double>): Double? = if (values.isEmpty()) null else values.average()

    companion object {
        /** Quality of an attempt: complete, with enough valid frames. Null when the attempt cannot be a result. */
        fun qualityOf(complete: Boolean, validPercent: Double, thresholds: SitToStandQualityThresholds): QualityStatus = when {
            !complete -> QualityStatus.INSUFFICIENT_DATA
            validPercent < thresholds.minValidFramePercent -> QualityStatus.INSUFFICIENT_DATA
            validPercent < thresholds.goodValidFramePercent -> QualityStatus.LOW_QUALITY
            else -> QualityStatus.VALID
        }

        /**
         * Builds the result of a finished, complete attempt. [samples] are the accepted samples of the attempt
         * (in order, duplicates removed by the caller); [engine] must be complete.
         */
        fun from(
            engine: SitToStandEngine,
            samples: List<SitToStandSample>,
            assessmentId: String,
            startEpochMs: Long,
            endEpochMs: Long,
            qualityThresholds: SitToStandQualityThresholds,
            algorithmVersion: String = SitToStandVersions.ALGORITHM_VERSION,
            scoringVersion: String = SitToStandVersions.SCORING_VERSION,
        ): SitToStandResult {
            val total = engine.totalTimeMs ?: error("The attempt is not complete")
            val origin = samples.first().timestampMs
            val validPercent = if (engine.validFrameCount + engine.invalidFrameCount == 0) 0.0 else
                100.0 * engine.validFrameCount / (engine.validFrameCount + engine.invalidFrameCount)
            val series = SitToStandSeries(
                frameTimesMs = samples.map { it.timestampMs - origin },
                frameValid = samples.map { if (it.isValid) 'V' else 'I' }.joinToString(""),
                hipRiseShinLengths = samples.map { if (it.isValid) (engine.baseline.hipYPx - it.hipYPx) / engine.baseline.shinPx else Double.NaN },
                kneeAngleDeg = samples.map { if (it.isValid) it.kneeAngleDeg else Double.NaN },
                trunkLeanDeg = samples.map { if (it.isValid) it.trunkLeanDeg else Double.NaN },
            )
            return SitToStandResult(
                assessmentId = assessmentId,
                timestampEpochMs = endEpochMs,
                startEpochMs = startEpochMs,
                endEpochMs = endEpochMs,
                protocolId = engine.protocol.id,
                targetRepetitions = engine.protocol.targetRepetitions,
                side = engine.baseline.side,
                baseline = engine.baseline,
                repetitions = engine.repetitions.map { rep ->
                    rep.copy(
                        standOnsetMs = rep.standOnsetMs - origin,
                        standReachedMs = rep.standReachedMs - origin,
                        sitOnsetMs = rep.sitOnsetMs - origin,
                        seatedReachedMs = rep.seatedReachedMs - origin,
                    )
                },
                totalTimeMs = total,
                validFrames = engine.validFrameCount,
                invalidFrames = engine.invalidFrameCount,
                invalidFramesByIssue = engine.invalidFramesByIssue,
                partialAttempts = engine.partialAttempts,
                qualityStatus = qualityOf(engine.isComplete, validPercent, qualityThresholds),
                series = series,
                algorithmVersion = algorithmVersion,
                configSummary = engine.thresholds.summary(),
                scoringVersion = scoringVersion,
            )
        }
    }
}
