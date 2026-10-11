package com.example.parkinson.sts

import kotlin.math.sqrt

/**
 * The person's own seated reference, from the calibration window: the knee angle, the hip height in the image
 * and the shin length in pixels (the scale used to express hip movement in shin lengths).
 */
data class SeatedBaseline(
    val side: SitToStandSide,
    val kneeAngleDeg: Double,
    val hipYPx: Double,
    val shinPx: Double,
)

enum class CalibrationIssue {
    /** Too few valid frames in the window: the body was not visible enough. */
    TOO_FEW_VALID_FRAMES,

    /** The knee was not bent enough: the person is not seated. */
    NOT_SEATED,

    /** The knee angle changed too much during the window: the person was moving or the tracking jumped. */
    UNSTABLE,
}

sealed interface CalibrationOutcome {
    data class Ready(val baseline: SeatedBaseline) : CalibrationOutcome
    data class Failed(val issue: CalibrationIssue) : CalibrationOutcome
}

/**
 * Collects the samples of the seated calibration window and derives the [SeatedBaseline]. Pure: no Android
 * calls. The side is the majority side of the valid frames, so one noisy frame cannot change it.
 */
class SeatedCalibrator(private val thresholds: SitToStandQualityThresholds = SitToStandQualityThresholds()) {

    private val samples = ArrayList<SitToStandSample>()

    fun add(sample: SitToStandSample) {
        samples += sample
    }

    val sampleCount: Int get() = samples.size

    fun result(): CalibrationOutcome {
        val valid = samples.filter { it.isValid }
        if (valid.size < thresholds.minCalibrationFrames) return CalibrationOutcome.Failed(CalibrationIssue.TOO_FEW_VALID_FRAMES)

        val side = if (valid.count { it.side == SitToStandSide.LEFT } > valid.size / 2) SitToStandSide.LEFT else SitToStandSide.RIGHT
        val sideFrames = valid.filter { it.side == side }
        if (sideFrames.size < thresholds.minCalibrationFrames) return CalibrationOutcome.Failed(CalibrationIssue.TOO_FEW_VALID_FRAMES)

        val knees = sideFrames.map { it.kneeAngleDeg }
        val kneeMean = knees.average()
        val kneeSd = sqrt(knees.sumOf { (it - kneeMean) * (it - kneeMean) } / knees.size)
        val kneeMedian = median(knees)
        if (kneeMedian > thresholds.seatedKneeMaxDeg) return CalibrationOutcome.Failed(CalibrationIssue.NOT_SEATED)
        if (kneeSd > thresholds.maxCalibrationKneeSdDeg) return CalibrationOutcome.Failed(CalibrationIssue.UNSTABLE)

        return CalibrationOutcome.Ready(
            SeatedBaseline(
                side = side,
                kneeAngleDeg = kneeMedian,
                hipYPx = median(sideFrames.map { it.hipYPx }),
                shinPx = median(sideFrames.map { it.shinPx }),
            )
        )
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2.0
    }
}
