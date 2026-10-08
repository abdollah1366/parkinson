package com.example.parkinson.pronation

import com.example.parkinson.assessment.QualityStatus

/**
 * Turns score, quality and metrics into language-neutral interpretation codes. The UI maps them
 * to Persian sentences (string resources). Never diagnostic: the notes describe this recording
 * relative to the internal software reference.
 */
object PronationSupinationInterpreter {

    fun interpret(
        score: PronationSupinationPerformanceScore?,
        quality: PronationQualityReport,
        metrics: PronationMetrics?,
        accelerometerUsed: Boolean,
        config: PronationScoringConfig = PronationScoringConfig()
    ): PronationInterpretation {
        if (score == null) {
            return PronationInterpretation(null, ReliabilityLevel.NOT_RELIABLE, listOf(InterpretationNote.REPEAT_RECOMMENDED))
        }
        val c = score.components
        val low = config.lowComponentScore
        val notes = mutableListOf<InterpretationNote>()
        notes += if (c.speed >= low && (c.rhythm ?: low) >= low) {
            InterpretationNote.SPEED_AND_RHYTHM_GOOD
        } else {
            InterpretationNote.SPEED_OR_RHYTHM_LOWER
        }
        if (c.amplitude < low) notes += InterpretationNote.AMPLITUDE_LOWER
        if ((metrics?.pauseCount ?: 0) > 0) notes += InterpretationNote.PAUSES_OBSERVED
        when (score.trend.state) {
            PerformanceTrendState.DECLINING -> notes += InterpretationNote.TREND_DECLINING
            PerformanceTrendState.IMPROVING -> notes += InterpretationNote.TREND_IMPROVING
            else -> Unit
        }
        if (quality.status == QualityStatus.VALID) {
            notes += InterpretationNote.QUALITY_GOOD
        } else {
            notes += InterpretationNote.QUALITY_LIMITED
        }
        if (!accelerometerUsed) notes += InterpretationNote.GYROSCOPE_ONLY
        if (quality.status != QualityStatus.VALID) notes += InterpretationNote.REPEAT_RECOMMENDED
        return PronationInterpretation(MotorPerformanceBand.forScore(score.total), score.reliability, notes)
    }
}
