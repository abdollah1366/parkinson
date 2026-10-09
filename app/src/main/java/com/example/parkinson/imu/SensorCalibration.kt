package com.example.parkinson.imu

import kotlin.math.sqrt

/**
 * ENGINEERING limits of the still-period calibration. The reference direction is the mean gravity direction of a
 * still period; it is the phone's orientation in the pocket at that moment, and every later angle is measured from it.
 */
data class CalibrationLimits(
    /** Time at the start of the window that is ignored while the gravity low-pass settles (ms). */
    val warmupMs: Double = 500.0,
    /** Minimum valid grid time after the warm-up (ms). */
    val minValidMs: Double = 1_000.0,
    /** Largest RMS gyroscope magnitude accepted as "still" (deg/s). */
    val maxGyroRmsDegPerSec: Double = 8.0,
    /** Largest standard deviation of the gravity direction over the window (deg). */
    val maxTiltSdDeg: Double = 3.0,
    /** Plausible gravity magnitude (m/s^2). Outside it the accelerometer is not reporting gravity. */
    val gravityRange: ClosedFloatingPointRange<Double> = 7.0..12.0,
)

enum class CalibrationIssue {
    /** Too little valid data after the warm-up (sensor gaps or a too short window). */
    INSUFFICIENT_DATA,

    /** The phone moved during the still period (gyroscope or gravity direction changed). */
    NOT_STILL,

    /** The gravity magnitude is outside the plausible range: the sensor data cannot be trusted for orientation. */
    IMPLAUSIBLE_GRAVITY,
}

sealed interface CalibrationOutcome {
    /** [reference] is the unit gravity direction in the device frame at rest. */
    data class Ready(val reference: Vec3, val gyroRmsDegPerSec: Double, val tiltSdDeg: Double) : CalibrationOutcome

    data class Failed(val issue: CalibrationIssue) : CalibrationOutcome
}

object SensorCalibration {

    /** Calibrates from the grid points in [grid] that lie in the window [startMs, endMs] (grid times, ms). */
    fun calibrate(
        grid: ImuGrid,
        startMs: Double,
        endMs: Double,
        limits: CalibrationLimits = CalibrationLimits(),
    ): CalibrationOutcome {
        val from = startMs + limits.warmupMs
        val indices = (0 until grid.size).filter { grid.valid[it] && grid.timeMs[it] >= from && grid.timeMs[it] <= endMs }
        if (indices.isEmpty() || indices.last().let { grid.timeMs[it] - grid.timeMs[indices.first()] } < limits.minValidMs) {
            return CalibrationOutcome.Failed(CalibrationIssue.INSUFFICIENT_DATA)
        }

        val magnitudes = indices.map { grid.gravityMagnitude(it) }
        if (magnitudes.any { it !in limits.gravityRange }) {
            return CalibrationOutcome.Failed(CalibrationIssue.IMPLAUSIBLE_GRAVITY)
        }

        val gyroRms = sqrt(indices.sumOf { grid.gyroDegPerSec(it).let { w -> w * w } } / indices.size)
        if (gyroRms > limits.maxGyroRmsDegPerSec) {
            return CalibrationOutcome.Failed(CalibrationIssue.NOT_STILL)
        }

        val directions = indices.map { Vec3(grid.gx[it], grid.gy[it], grid.gz[it]).normalized()!! }
        val mean = Vec3.mean(directions)!!.normalized() ?: return CalibrationOutcome.Failed(CalibrationIssue.NOT_STILL)
        val angles = indices.map { grid.gravityAngleDeg(it, mean) }
        val tiltSd = sqrt(angles.sumOf { it * it } / angles.size)
        if (tiltSd > limits.maxTiltSdDeg) {
            return CalibrationOutcome.Failed(CalibrationIssue.NOT_STILL)
        }
        return CalibrationOutcome.Ready(mean, gyroRms, tiltSd)
    }
}
