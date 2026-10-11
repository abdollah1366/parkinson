package com.example.parkinson.dualtask

import com.example.parkinson.assessment.AssessmentQuality
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.imu.ImuGaitResult
import com.example.parkinson.model.SelectedHand
import kotlin.math.roundToInt

/**
 * Stored result of the motor-cognitive (dual-task) test: a usual walk, then a walk while subtracting threes aloud.
 * Only the summary values of both walks are kept (the walks are not stored as walking-test results). Null means not
 * measured, never zero.
 */
data class DualTaskResult(
    override val assessmentId: String,
    override val timestampEpochMs: Long,
    val sessionId: String,
    val protocolId: String,
    val plannedWalkingMs: Double,
    val single: WalkSummary,
    val dual: WalkSummary,
    val singleSteps: Int,
    val dualSteps: Int,
    val startNumber: Int,
    /** The last number the person reported after the walk; null when they did not enter one. */
    val lastNumber: Int?,
    /** Answers from the reported number; null without one. */
    val answers: Int?,
    /** False when the reported number is not reachable by subtracting threes (at least one wrong answer). */
    val answersExact: Boolean?,
    /** Microphone estimate (no audio kept); null when the microphone was not used or heard nothing usable. */
    val spokenUtterances: Int?,
    val longPauses: Int?,
    val longestPauseMs: Double?,
    val coveragePercent: Double,
    val qualityStatus: QualityStatus,
    override val algorithmVersion: String,
    val configSummary: String,
    override val scoringVersion: String,
) : AssessmentResult {

    override val type: AssessmentType get() = AssessmentType.DUAL_TASK
    override val hand: SelectedHand? get() = null
    override val quality: AssessmentQuality get() = AssessmentQuality(qualityStatus, coveragePercent.roundToInt().coerceIn(0, 100))

    /** Answers per minute: from the reported number, else from the microphone count. */
    val answersPerMinute: Double?
        get() {
            val n = answers ?: spokenUtterances ?: return null
            return if (plannedWalkingMs <= 0.0) null else n * 60_000.0 / plannedWalkingMs
        }

    /** Percent fewer steps per minute while thinking; positive means slower. */
    val cadenceCostPercent: Double? get() = DualTaskScoring.costPercent(single.cadence, dual.cadence)

    /** Change of the stride-time variability while thinking (percentage points). */
    val strideCvChange: Double?
        get() = if (single.strideCvPercent == null || dual.strideCvPercent == null) null
        else dual.strideCvPercent - single.strideCvPercent

    val score: DualTaskScore?
        get() = if (scoringVersion != DualTaskScoring.VERSION) null
        else DualTaskScoring.score(single, dual, answersPerMinute, longPauses)

    override val performanceIndex: Int? get() = score?.overall

    companion object {
        const val PROTOCOL_ID = "dual_task_walk_serial3_imu"
        const val ALGORITHM_VERSION = "dual-task-1.0"

        fun summaryOf(walk: ImuGaitResult) = WalkSummary(
            cadence = walk.cadenceStepsPerMinute,
            strideTimeMs = walk.strideTimeMs,
            strideCvPercent = walk.strideCvPercent,
            stepAsymmetryPercent = walk.stepAsymmetryPercent,
            meanTurnVelocityDegPerSec = walk.meanTurnVelocityDegPerSec,
        )

        /** The worse of the two walks' quality decides the result's quality. */
        fun combine(
            id: String,
            timestampEpochMs: Long,
            single: ImuGaitResult,
            dual: ImuGaitResult,
            startNumber: Int,
            lastNumber: Int?,
            spoken: SpokenAnswers?,
        ): DualTaskResult {
            val answers = lastNumber?.let { SerialSubtraction.answers(startNumber, it) }
            return DualTaskResult(
                assessmentId = id,
                timestampEpochMs = timestampEpochMs,
                sessionId = dual.sessionId,
                protocolId = PROTOCOL_ID,
                plannedWalkingMs = dual.plannedWalkingMs,
                single = summaryOf(single),
                dual = summaryOf(dual),
                singleSteps = single.steps,
                dualSteps = dual.steps,
                startNumber = startNumber,
                lastNumber = lastNumber.takeIf { answers != null },
                answers = answers?.count,
                answersExact = answers?.exact,
                spokenUtterances = spoken?.utterances?.takeIf { it > 0 },
                longPauses = spoken?.longPauses,
                longestPauseMs = spoken?.longestPauseMs,
                coveragePercent = minOf(single.coveragePercent, dual.coveragePercent),
                qualityStatus = maxOf(single.qualityStatus, dual.qualityStatus),
                algorithmVersion = "$ALGORITHM_VERSION+${dual.algorithmVersion}",
                configSummary = dual.configSummary,
                scoringVersion = DualTaskScoring.VERSION,
            )
        }
    }
}
