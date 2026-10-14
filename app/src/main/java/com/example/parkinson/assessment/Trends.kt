package com.example.parkinson.assessment

import com.example.parkinson.data.StoredResult
import com.example.parkinson.medication.MedicationState
import com.example.parkinson.model.SelectedHand

/** One scored result on a trend chart. */
data class TrendPoint(
    val assessmentId: String,
    val timestampEpochMs: Long,
    /** The performance index, 0..100. */
    val index: Int,
    val medicationState: MedicationState?,
)

/**
 * The performance index of one test over time, for one person (the history passed in is already theirs). Results
 * without an index (quality not VALID) are left out: they are not measurements of the person.
 */
object Trends {

    /** Test types with at least one scored result, in enum order. */
    fun typesWithScores(history: List<StoredResult>): List<AssessmentType> {
        val present = history.filter { it.result.performanceIndex != null }.map { it.result.type }.toSet()
        return AssessmentType.entries.filter { it in present }
    }

    /** The hands with scored results of [type] (right before left); a single null for tests without a hand. */
    fun hands(history: List<StoredResult>, type: AssessmentType): List<SelectedHand?> =
        history.filter { it.result.type == type && it.result.performanceIndex != null }
            .map { it.result.hand }
            .distinct()
            .sortedBy { if (it == SelectedHand.LEFT) 1 else 0 }

    /** The scored results of [type] (and [hand], when the test uses one), oldest first. */
    fun points(history: List<StoredResult>, type: AssessmentType, hand: SelectedHand?): List<TrendPoint> =
        history.asSequence()
            .filter { it.result.type == type && it.result.hand == hand }
            .mapNotNull { s ->
                s.result.performanceIndex?.let {
                    TrendPoint(s.result.assessmentId, s.result.timestampEpochMs, it, s.medicationState)
                }
            }
            .sortedBy { it.timestampEpochMs }
            .toList()

    /** Mean index of the points recorded in [state], or null when there are none. */
    fun mean(points: List<TrendPoint>, state: MedicationState): Double? =
        points.filter { it.medicationState == state }.map { it.index }.takeIf { it.isNotEmpty() }?.average()
}
