package com.example.parkinson.imu

/**
 * ENGINEERING thresholds of the sit-to-stand detector, in degrees of posture change (the angle between the current
 * gravity direction and the seated reference) and milliseconds of hold. They are not clinically validated and are
 * documented in docs/imu-assessments-algorithm.md.
 */
data class SitToStandImuThresholds(
    /** Posture change that starts a movement. */
    val onsetDeg: Double = 10.0,
    /** Posture change that counts as standing. */
    val standDeg: Double = 35.0,
    /** Posture change below which a standing person is considered to be sitting down (hysteresis below [standDeg]). */
    val leaveDeg: Double = 25.0,
    /** Posture change at or below which the person is seated again. */
    val seatedDeg: Double = 12.0,
    val holdOnsetMs: Double = 150.0,
    val holdStandMs: Double = 200.0,
    val holdLeaveMs: Double = 200.0,
    val holdSeatedMs: Double = 300.0,
    /** A repetition needs a total rotation rate of at least this (deg/s) somewhere in it: slow posture drift is not a transfer. */
    val rotationEvidenceDegPerSec: Double = 25.0,
    /** A sensor gap longer than this is a data gap; a transfer overlapping it is discarded. */
    val maxGapMs: Double = 300.0,
    /** Half-width of the moving average that smooths the posture angle (ms). */
    val smoothingHalfMs: Double = 50.0,
    val targetRepetitions: Int = 5,
) {
    fun summary(): String =
        "onset=$onsetDeg;stand=$standDeg;leave=$leaveDeg;seated=$seatedDeg;" +
            "holds=$holdOnsetMs/$holdStandMs/$holdLeaveMs/$holdSeatedMs;rot=$rotationEvidenceDegPerSec;gap=$maxGapMs"
}

/** One counted chair transfer. Times are grid times (ms from the first sample of the analysed data). */
data class ImuRepetition(
    val index: Int,
    /** Sustained start of the movement (posture above the onset threshold). */
    val standOnsetMs: Double,
    /** Sustained standing posture reached. */
    val standReachedMs: Double,
    /** Sustained start of sitting down (posture below the leave threshold). */
    val sitOnsetMs: Double,
    /** Sustained seated posture reached: the end of the repetition. */
    val seatedReachedMs: Double,
    val peakPostureDeg: Double,
    val peakRotationDegPerSec: Double,
) {
    val standingUpMs: Double get() = standReachedMs - standOnsetMs
    val sittingDownMs: Double get() = seatedReachedMs - sitOnsetMs
    val durationMs: Double get() = seatedReachedMs - standOnsetMs
}

/** Why a candidate transfer was not counted. */
enum class RejectionReason {
    /** The posture rose but not to the standing threshold, then returned to seated. */
    PARTIAL_RISE,

    /** The posture changed without rotation evidence (drift, not a transfer). */
    NO_ROTATION_EVIDENCE,

    /** A sensor gap overlapped the transfer. */
    SENSOR_GAP,
}

data class Rejection(val reason: RejectionReason, val startMs: Double, val endMs: Double)

/** A stretch without sensor data, longer than the gap limit. */
data class InvalidInterval(val startMs: Double, val endMs: Double)

data class SitToStandImuAnalysis(
    val repetitions: List<ImuRepetition>,
    val rejections: List<Rejection>,
    val invalidIntervals: List<InvalidInterval>,
    /** Largest posture change seen (deg). A value near zero means no transfer was registered. Null without data. */
    val maxPostureDeg: Double?,
    val coveragePercent: Double,
) {
    /** Total time from the first repetition's start to the last one's sustained seated posture (ms). */
    val totalTimeMs: Double? get() = if (repetitions.isEmpty()) null else repetitions.last().seatedReachedMs - repetitions.first().standOnsetMs

    fun isComplete(target: Int): Boolean = repetitions.size >= target
}

/**
 * Sit-to-stand detector on the posture angle. For each grid point the posture is the angle between the current gravity
 * direction and the seated reference from calibration. A repetition follows:
 *
 *   SEATED --(posture >= onset, held)--> RISING --(posture >= stand, held)--> STANDING
 *          --(posture < leave, held)--> SITTING --(posture <= seated, held)--> repetition counted
 *
 * A rise that returns to seated before the standing threshold is a partial rise and is not counted. A repetition needs
 * rotation evidence (the gyroscope rate reaches the evidence threshold) and no data gap inside it. Each transition has
 * its own hold timer, so one isolated acceleration spike cannot start, end or count a repetition.
 *
 * Time base: the grid, which is built from the sensor timestamps. No UI timer is used.
 */
object SitToStandImuDetector {

    private enum class Phase { SEATED, RISING, STANDING, SITTING, UNKNOWN }

    // One hold timer per transition, so the conditions do not reset each other.
    private const val HOLD_ONSET = 0
    private const val HOLD_STAND = 1
    private const val HOLD_LEAVE = 2
    private const val HOLD_SEATED = 3

    fun analyze(
        grid: ImuGrid,
        reference: Vec3,
        thresholds: SitToStandImuThresholds = SitToStandImuThresholds(),
    ): SitToStandImuAnalysis {
        val posture = smoothedPosture(grid, reference, thresholds.smoothingHalfMs)
        val repetitions = ArrayList<ImuRepetition>()
        val rejections = ArrayList<Rejection>()
        val holds = DoubleArray(4) { Double.NaN }

        fun held(key: Int, now: Double, condition: Boolean, holdMs: Double): Boolean {
            if (!condition) {
                holds[key] = Double.NaN
                return false
            }
            if (holds[key].isNaN()) holds[key] = now
            return now - holds[key] >= holdMs
        }

        fun clearHolds() = holds.fill(Double.NaN)

        var phase = Phase.SEATED
        var onset = 0.0
        var standReached = 0.0
        var sitOnset = 0.0
        var peakPosture = Double.NaN
        var peakRotation = Double.NaN
        var lastPosture = Double.NaN
        var lastValidMs = 0.0

        fun endRepetition() {
            phase = Phase.SEATED
            clearHolds()
            peakPosture = Double.NaN
            peakRotation = Double.NaN
        }

        for (i in 0 until grid.size) {
            val t = grid.timeMs[i]
            val p = posture[i]
            if (!p.isFinite()) {
                // Data gap. A transfer in progress cannot be trusted and is discarded. So is a gap that starts while the
                // person is not seated yet (the rise may already be under way): the transfer's start is then unknown.
                val notSeated = phase != Phase.SEATED || (lastPosture.isFinite() && lastPosture > thresholds.seatedDeg)
                if (notSeated) {
                    val from = if (phase == Phase.RISING || phase == Phase.STANDING || phase == Phase.SITTING) onset else lastValidMs
                    if (phase != Phase.UNKNOWN) rejections += Rejection(RejectionReason.SENSOR_GAP, from, t)
                    phase = Phase.UNKNOWN
                }
                clearHolds()
                continue
            }
            lastPosture = p
            lastValidMs = t
            if (phase == Phase.UNKNOWN) {
                // Posture known again: wait for the seated posture before the next transfer can start.
                if (held(HOLD_SEATED, t, p <= thresholds.seatedDeg, thresholds.holdSeatedMs)) endRepetition()
                continue
            }

            val rotation = grid.gyroDegPerSec(i)
            if (phase != Phase.SEATED) {
                peakPosture = if (peakPosture.isNaN()) p else maxOf(peakPosture, p)
                peakRotation = if (peakRotation.isNaN()) rotation else maxOf(peakRotation, rotation)
            }

            when (phase) {
                Phase.SEATED -> {
                    if (held(HOLD_ONSET, t, p >= thresholds.onsetDeg, thresholds.holdOnsetMs)) {
                        onset = holds[HOLD_ONSET]
                        phase = Phase.RISING
                        clearHolds()
                        peakPosture = p
                        peakRotation = rotation
                    }
                }

                Phase.RISING -> {
                    if (held(HOLD_SEATED, t, p <= thresholds.seatedDeg, thresholds.holdSeatedMs)) {
                        rejections += Rejection(RejectionReason.PARTIAL_RISE, onset, t)
                        endRepetition()
                    } else if (held(HOLD_STAND, t, p >= thresholds.standDeg, thresholds.holdStandMs)) {
                        standReached = holds[HOLD_STAND]
                        phase = Phase.STANDING
                        clearHolds()
                    }
                }

                Phase.STANDING -> {
                    if (held(HOLD_LEAVE, t, p < thresholds.leaveDeg, thresholds.holdLeaveMs)) {
                        sitOnset = holds[HOLD_LEAVE]
                        phase = Phase.SITTING
                        clearHolds()
                    }
                }

                Phase.SITTING -> {
                    if (held(HOLD_STAND, t, p >= thresholds.standDeg, thresholds.holdStandMs)) {
                        // Stood up again before sitting down: the sitting phase is cancelled, the transfer goes on.
                        phase = Phase.STANDING
                        clearHolds()
                    } else if (held(HOLD_SEATED, t, p <= thresholds.seatedDeg, thresholds.holdSeatedMs)) {
                        val seatedAt = holds[HOLD_SEATED]
                        if (peakRotation.isFinite() && peakRotation >= thresholds.rotationEvidenceDegPerSec) {
                            repetitions += ImuRepetition(
                                index = repetitions.size + 1,
                                standOnsetMs = onset,
                                standReachedMs = standReached,
                                sitOnsetMs = sitOnset,
                                seatedReachedMs = seatedAt,
                                peakPostureDeg = peakPosture,
                                peakRotationDegPerSec = peakRotation,
                            )
                        } else {
                            rejections += Rejection(RejectionReason.NO_ROTATION_EVIDENCE, onset, seatedAt)
                        }
                        endRepetition()
                    }
                }

                Phase.UNKNOWN -> Unit
            }
        }
        // A transfer still open at the end of the data is neither counted nor rejected: the data simply ended.

        val maxPosture = posture.filter { it.isFinite() }.maxOrNull()
        return SitToStandImuAnalysis(
            repetitions = repetitions,
            rejections = rejections,
            invalidIntervals = invalidIntervals(grid, thresholds.maxGapMs),
            maxPostureDeg = maxPosture,
            coveragePercent = grid.coveragePercent,
        )
    }

    /** Posture angle per grid point (deg), smoothed by a moving average over valid points within [halfMs]. */
    private fun smoothedPosture(grid: ImuGrid, reference: Vec3, halfMs: Double): DoubleArray {
        val raw = DoubleArray(grid.size) { grid.gravityAngleDeg(it, reference) }
        val out = DoubleArray(grid.size) { Double.NaN }
        var lo = 0
        var hi = -1
        for (i in 0 until grid.size) {
            if (!raw[i].isFinite()) continue
            val t = grid.timeMs[i]
            while (lo < i && grid.timeMs[lo] < t - halfMs) lo++
            while (hi + 1 < grid.size && grid.timeMs[hi + 1] <= t + halfMs) hi++
            var sum = 0.0
            var n = 0
            for (k in lo..hi) {
                if (raw[k].isFinite()) {
                    sum += raw[k]
                    n++
                }
            }
            out[i] = sum / n
        }
        return out
    }

    /** Stretches without data, longer than [maxGapMs]. */
    private fun invalidIntervals(grid: ImuGrid, maxGapMs: Double): List<InvalidInterval> {
        val out = ArrayList<InvalidInterval>()
        var start: Double? = null
        for (i in 0 until grid.size) {
            if (!grid.valid[i]) {
                if (start == null) start = grid.timeMs[i]
            } else if (start != null) {
                val end = grid.timeMs[i]
                if (end - start > maxGapMs) out += InvalidInterval(start, end)
                start = null
            }
        }
        if (start != null && grid.size > 0) {
            val end = grid.timeMs[grid.size - 1]
            if (end - start > maxGapMs) out += InvalidInterval(start, end)
        }
        return out
    }
}
