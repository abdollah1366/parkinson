package com.example.parkinson.pronation

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.sensors.StreamStats
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Technical quality of one recording (never a judgement of the person). Every rule uses the
 * centralized ENGINEERING thresholds in [PronationQualityThresholds]; the status is the most
 * severe issue. INVALID / INSUFFICIENT_DATA never get a score.
 */
class PronationSupinationQualityEngine(
    private val thresholds: PronationQualityThresholds = PronationQualityThresholds()
) {

    fun assess(
        accel: StreamStats,
        gyro: StreamStats,
        accelerometerUsed: Boolean,
        metrics: PronationMetrics?,
        windowMs: Double,
        streamOffsetMs: Double
    ): PronationQualityReport {
        val th = thresholds
        val issues = linkedSetOf<PronationQualityIssue>()

        if (gyro.validSamples == 0) issues += PronationQualityIssue.NO_GYROSCOPE_DATA
        // The gyroscope alone is enough to measure rotation; the supporting checks are lost.
        if (!accelerometerUsed) issues += PronationQualityIssue.ACCELEROMETER_UNAVAILABLE

        val streams = listOfNotNull(gyro.takeIf { it.validSamples > 0 }, accel.takeIf { accelerometerUsed && it.validSamples > 0 })
        for (s in streams) {
            if (s.samplingRateHz < th.minSamplingRateHz) issues += PronationQualityIssue.SAMPLING_RATE_TOO_LOW
            else if (s.samplingRateHz < th.goodSamplingRateHz) issues += PronationQualityIssue.LOW_SAMPLING_RATE

            if (s.completenessPercent < th.minCompletenessPercent) issues += PronationQualityIssue.RECORDING_INCOMPLETE

            if (s.dropoutShare > th.maxGapShare || s.longestGapMs > th.maxAllowedGapMs) {
                issues += PronationQualityIssue.EXCESSIVE_GAPS
            } else if (s.dropoutShare > th.goodGapShare || s.longestGapMs > th.goodLongestGapMs) {
                issues += PronationQualityIssue.GAPS_PRESENT
            }

            if (s.validPercent < th.minValidSamplePercent) issues += PronationQualityIssue.TOO_MANY_INVALID_SAMPLES
            else if (s.validPercent < th.goodValidSamplePercent) issues += PronationQualityIssue.INVALID_SAMPLES_PRESENT

            if (s.validSamples < th.minValidSamples) issues += PronationQualityIssue.TOO_FEW_SAMPLES
            if (s.unreliablePercent > th.maxUnreliablePercent) issues += PronationQualityIssue.SENSOR_UNRELIABLE
        }
        if (streams.size == 2 && streamOffsetMs > th.maxStreamOffsetMs) issues += PronationQualityIssue.STREAMS_MISALIGNED

        val usableMs = (windowMs - gyro.dropoutTotalMs).coerceAtLeast(0.0)
        if (gyro.validSamples > 0 && usableMs < th.minUsableDurationMs) issues += PronationQualityIssue.USABLE_DURATION_TOO_SHORT

        val validCyclePercent = metrics?.takeIf { it.cycleCount > 0 }?.let { it.validCycleCount * 100.0 / it.cycleCount }
        if (metrics == null) {
            if (gyro.validSamples > 0) issues += PronationQualityIssue.TOO_FEW_SAMPLES
        } else {
            if (metrics.cycleCount < th.minValidCycles) {
                issues += PronationQualityIssue.NO_MOVEMENT_DETECTED
            } else {
                if (metrics.validCycleCount < th.minValidCycles) issues += PronationQualityIssue.TOO_FEW_VALID_CYCLES
                if (validCyclePercent != null && validCyclePercent < th.minValidCyclePercent) {
                    issues += PronationQualityIssue.LOW_VALID_CYCLE_PERCENTAGE
                }
                if (metrics.movementCoveragePercent < th.minMovementCoveragePercent) issues += PronationQualityIssue.LOW_MOVEMENT_COVERAGE
                if (metrics.rotationAxisSharePercent < th.minAxisSharePercent) issues += PronationQualityIssue.ROTATION_AXIS_UNSTABLE
            }
            if (metrics.noiseLevelDegS > th.maxNoiseLevelDegS) issues += PronationQualityIssue.HIGH_NOISE
            metrics.accMagnitudeMean?.let {
                if (it !in th.minGravity..th.maxGravity) issues += PronationQualityIssue.GRAVITY_IMPLAUSIBLE
            }
        }

        val sorted = issues.sortedWith(compareByDescending<PronationQualityIssue> { it.severity.ordinal }.thenBy { it.ordinal })
        val status = sorted.maxOfOrNull { it.severity.ordinal }?.let { QualityStatus.entries[it] } ?: QualityStatus.VALID
        return PronationQualityReport(
            status = status,
            issues = sorted,
            qualityPercentage = qualityPercentage(streams, validCyclePercent),
            usableDurationMs = usableMs,
            validCyclePercent = validCyclePercent
        )
    }

    /**
     * 0..100 "کیفیت داده" from the weakest stream (weighted rate, completeness, valid samples and
     * gaps), blended with the valid-cycle share when cycles exist.
     */
    private fun qualityPercentage(streams: List<StreamStats>, validCyclePercent: Double?): Int {
        val th = thresholds
        fun one(s: StreamStats): Double {
            if (s.validSamples < 2) return 0.0
            val rate = (s.samplingRateHz / th.goodSamplingRateHz).coerceIn(0.0, 1.0)
            val complete = (s.completenessPercent / 100.0).coerceIn(0.0, 1.0)
            val valid = (s.validPercent / 100.0).coerceIn(0.0, 1.0)
            val gaps = 1.0 - (s.dropoutShare / th.maxGapShare).coerceIn(0.0, 1.0)
            return th.qualityWeightRate * rate + th.qualityWeightCompleteness * complete +
                th.qualityWeightValidSamples * valid + th.qualityWeightGaps * gaps
        }
        if (streams.isEmpty()) return 0
        val technical = streams.minOf { one(it) }
        val combined = if (validCyclePercent == null) technical else
            th.qualityTechnicalShare * technical + (1 - th.qualityTechnicalShare) * validCyclePercent
        return min(100, combined.roundToInt()).coerceAtLeast(0)
    }
}
