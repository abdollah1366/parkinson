package com.example.parkinson.sts

import kotlin.math.max

enum class SitToStandPhase {
    /** Before the first valid frame of the attempt. */
    READY,
    SEATED,
    STANDING_UP,
    STANDING,
    SITTING_DOWN,
    /** One repetition has just been counted; the next valid frame returns to SEATED. */
    REPETITION_COMPLETED,
    TEST_COMPLETED,
    /** The attempt is invalid (tracking lost, timestamps broken, timeout or interruption). */
    PAUSED_OR_INVALID,
    /** The pose model failed; no result is produced. */
    ERROR,
}

/** Why an attempt became invalid. None of them is a score. */
enum class SitToStandInvalidReason {
    /** No valid frame for longer than the tracking-loss limit. */
    TRACKING_LOST,

    /** A frame arrived with an earlier timestamp than its predecessor. */
    NON_MONOTONIC_TIMESTAMPS,

    /** The protocol's time limit passed before the target repetitions were completed. */
    TIMEOUT_INCOMPLETE,

    /** Stopped by the person or by the app (leaving the screen, backgrounding, rotation). */
    INTERRUPTED,
}

/**
 * One counted repetition. Times are sample timestamps (ms, same clock as the samples):
 * - standOnsetMs: first sample of the sustained start of the movement (rise above the seated band);
 * - standReachedMs: first sample of the sustained standing posture;
 * - sitOnsetMs: first sample of the sustained start of sitting down;
 * - seatedReachedMs: first sample of the sustained seated posture (the repetition ends here).
 * Peaks are taken over the repetition: [peakHipRiseShinLengths] (shin lengths, upward positive) and
 * [peakKneeExtensionDeg] (degrees above the seated knee angle).
 */
data class SitToStandRepetition(
    val index: Int,
    val standOnsetMs: Long,
    val standReachedMs: Long,
    val sitOnsetMs: Long,
    val seatedReachedMs: Long,
    val peakHipRiseShinLengths: Double,
    val peakKneeExtensionDeg: Double,
) {
    /** Standing-up phase: from the start of the movement to the sustained standing posture (ms). */
    val standingUpMs: Long get() = standReachedMs - standOnsetMs

    /** Sitting-down phase: from the start of sitting down to the sustained seated posture (ms). */
    val sittingDownMs: Long get() = seatedReachedMs - sitOnsetMs

    /** Whole repetition: from the start of standing up to the sustained seated posture (ms). */
    val durationMs: Long get() = seatedReachedMs - standOnsetMs
}

/**
 * The sit-to-stand state machine. It consumes one [SitToStandSample] at a time (monotonic timestamps) and
 * decides the phase from the relative movement [score] of the seated baseline:
 *
 *   score = max(hipRise / hipRiseFullStand, kneeExtension / kneeExtensionFullStand)
 *
 * A repetition is counted only when the person (1) leaves the seated band, (2) reaches the standing score,
 * (3) leaves the standing score and (4) returns to the seated band. Every transition must hold for
 * [SitToStandThresholds.minHoldMs]. A rise that returns to seated before standing is a partial attempt and is
 * not counted. Duplicate timestamps are ignored; a backwards timestamp invalidates the attempt.
 *
 * Not thread-safe: the session calls it under its lock.
 */
class SitToStandEngine(
    val baseline: SeatedBaseline,
    val protocol: SitToStandProtocol,
    val thresholds: SitToStandThresholds = SitToStandThresholds(),
) {

    var phase: SitToStandPhase = SitToStandPhase.READY
        private set

    var invalidReason: SitToStandInvalidReason? = null
        private set

    private val completed = ArrayList<SitToStandRepetition>()

    /** Repetitions counted so far (copy). */
    val repetitions: List<SitToStandRepetition> get() = completed.toList()

    /** Rises that returned to seated before the full stand (not counted). */
    var partialAttempts: Int = 0
        private set

    /** Valid and invalid frames seen so far (duplicates excluded). */
    var validFrameCount: Int = 0
        private set
    var invalidFrameCount: Int = 0
        private set
    private val invalidIssueCounts = LinkedHashMap<SitToStandIssue, Int>()

    val invalidFramesByIssue: Map<SitToStandIssue, Int> get() = invalidIssueCounts.toMap()

    private var lastTimestampMs: Long? = null
    private var firstTimestampMs: Long? = null
    private var lastValidTimestampMs: Long? = null

    // Candidate times of the sustained transitions, and the current repetition's landmarks.
    private var risingSince: Long? = null
    private var standingSince: Long? = null
    private var leavingSince: Long? = null
    private var seatedSince: Long? = null
    private var onsetMs: Long? = null
    private var standReachedMs: Long? = null
    private var sitOnsetMs: Long? = null
    private var peakHip = Double.NaN
    private var peakKnee = Double.NaN

    val isComplete: Boolean get() = phase == SitToStandPhase.TEST_COMPLETED

    /** Time from the start of the first repetition to the end of the last one (ms). Null until complete. */
    val totalTimeMs: Long?
        get() = if (isComplete) completed.last().seatedReachedMs - completed.first().standOnsetMs else null

    /** Feeds one sample. Ignored once the attempt is complete, invalid or failed. */
    fun process(sample: SitToStandSample) {
        if (isFinished()) return
        val ts = sample.timestampMs
        val last = lastTimestampMs
        if (last != null) {
            if (ts == last) return
            if (ts < last) {
                invalidate(SitToStandInvalidReason.NON_MONOTONIC_TIMESTAMPS)
                return
            }
        }
        lastTimestampMs = ts
        if (firstTimestampMs == null) firstTimestampMs = ts

        if (!sample.isValid) {
            invalidFrameCount++
            val issue = sample.issue ?: SitToStandIssue.ERROR
            invalidIssueCounts[issue] = (invalidIssueCounts[issue] ?: 0) + 1
            val since = lastValidTimestampMs ?: firstTimestampMs!!
            if (ts - since >= thresholds.maxTrackingLossMs) invalidate(SitToStandInvalidReason.TRACKING_LOST)
            return
        }

        validFrameCount++
        lastValidTimestampMs = ts
        val hip = hipRiseShinLengths(sample)
        val knee = kneeExtensionDeg(sample)
        val score = max(hip / thresholds.hipRiseFullStand, knee / thresholds.kneeExtensionFullStand)
        step(ts, score, hip, knee)
    }

    /** Ends the attempt as incomplete once the protocol's time limit has passed. */
    fun checkTimeout(nowMs: Long) {
        if (isFinished()) return
        val first = firstTimestampMs ?: return
        if (nowMs - first >= protocol.timeoutMs) invalidate(SitToStandInvalidReason.TIMEOUT_INCOMPLETE)
    }

    /** Stops the attempt (interruption by the person or the app). */
    fun interrupt() {
        if (!isFinished()) invalidate(SitToStandInvalidReason.INTERRUPTED)
    }

    /** The pose model failed: no result will be produced. */
    fun fail() {
        if (!isFinished()) phase = SitToStandPhase.ERROR
    }

    private fun isFinished(): Boolean =
        phase == SitToStandPhase.TEST_COMPLETED || phase == SitToStandPhase.PAUSED_OR_INVALID || phase == SitToStandPhase.ERROR

    private fun invalidate(reason: SitToStandInvalidReason) {
        invalidReason = reason
        phase = SitToStandPhase.PAUSED_OR_INVALID
        resetRepetition()
    }

    private fun step(ts: Long, score: Double, hip: Double, knee: Double) {
        if (phase == SitToStandPhase.READY || phase == SitToStandPhase.REPETITION_COMPLETED) phase = SitToStandPhase.SEATED
        val t = thresholds
        when (phase) {
            SitToStandPhase.SEATED -> {
                if (score >= t.moveStartScore) {
                    if (risingSince == null) risingSince = ts
                    if (ts - risingSince!! >= t.minHoldMs) {
                        onsetMs = risingSince
                        peakHip = hip
                        peakKnee = knee
                        phase = SitToStandPhase.STANDING_UP
                        updatePeaks(hip, knee)
                    }
                } else {
                    risingSince = null
                }
            }

            SitToStandPhase.STANDING_UP -> {
                updatePeaks(hip, knee)
                if (score >= t.standingScore) {
                    if (standingSince == null) standingSince = ts
                    if (ts - standingSince!! >= t.minHoldMs) {
                        standReachedMs = standingSince
                        standingSince = null
                        phase = SitToStandPhase.STANDING
                    }
                } else {
                    standingSince = null
                    if (score <= t.seatedScore) {
                        // Back to seated before standing: a partial attempt, not a repetition.
                        partialAttempts++
                        resetRepetition()
                        phase = SitToStandPhase.SEATED
                    }
                }
            }

            SitToStandPhase.STANDING -> {
                updatePeaks(hip, knee)
                if (score < t.leaveStandingScore) {
                    if (leavingSince == null) leavingSince = ts
                    if (ts - leavingSince!! >= t.minHoldMs) {
                        sitOnsetMs = leavingSince
                        leavingSince = null
                        phase = SitToStandPhase.SITTING_DOWN
                    }
                } else {
                    leavingSince = null
                }
            }

            SitToStandPhase.SITTING_DOWN -> {
                updatePeaks(hip, knee)
                if (score >= t.standingScore) {
                    // Stood up again before sitting down: the sitting-down phase is discarded.
                    sitOnsetMs = null
                    seatedSince = null
                    phase = SitToStandPhase.STANDING
                } else if (score <= t.seatedScore) {
                    if (seatedSince == null) seatedSince = ts
                    if (ts - seatedSince!! >= t.minHoldMs) completeRepetition(seatedSince!!)
                } else {
                    seatedSince = null
                }
            }

            else -> Unit
        }
    }

    private fun completeRepetition(seatedAt: Long) {
        val rep = SitToStandRepetition(
            index = completed.size + 1,
            standOnsetMs = onsetMs!!,
            standReachedMs = standReachedMs!!,
            sitOnsetMs = sitOnsetMs!!,
            seatedReachedMs = seatedAt,
            peakHipRiseShinLengths = peakHip,
            peakKneeExtensionDeg = peakKnee,
        )
        completed += rep
        resetRepetition()
        phase = if (completed.size >= protocol.targetRepetitions) SitToStandPhase.TEST_COMPLETED
        else SitToStandPhase.REPETITION_COMPLETED
    }

    private fun updatePeaks(hip: Double, knee: Double) {
        peakHip = if (peakHip.isNaN()) hip else max(peakHip, hip)
        peakKnee = if (peakKnee.isNaN()) knee else max(peakKnee, knee)
    }

    private fun resetRepetition() {
        risingSince = null
        standingSince = null
        leavingSince = null
        seatedSince = null
        onsetMs = null
        standReachedMs = null
        sitOnsetMs = null
        peakHip = Double.NaN
        peakKnee = Double.NaN
    }

    private fun hipRiseShinLengths(sample: SitToStandSample): Double =
        (baseline.hipYPx - sample.hipYPx) / baseline.shinPx

    private fun kneeExtensionDeg(sample: SitToStandSample): Double =
        sample.kneeAngleDeg - baseline.kneeAngleDeg

    /** The movement score of one sample (see class documentation). */
    fun scoreOf(sample: SitToStandSample): Double =
        max(hipRiseShinLengths(sample) / thresholds.hipRiseFullStand, kneeExtensionDeg(sample) / thresholds.kneeExtensionFullStand)
}
