package com.example.parkinson.assessment

import com.example.parkinson.data.StoredResult
import com.example.parkinson.dualtask.DualTaskResultTest
import com.example.parkinson.medication.MedicationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Trend points are built from stored results (SYNTHETIC values); unscored results are left out. */
class TrendsTest {

    private fun result(id: String, time: Long, index: Int?): AssessmentResult {
        val base = DualTaskResultTest.sample().copy(assessmentId = id, timestampEpochMs = time)
        return object : AssessmentResult by base {
            override val performanceIndex: Int? = index
        }
    }

    private val history = listOf(
        StoredResult(result("c", 3_000, 80), MedicationState.ON),
        StoredResult(result("b", 2_000, null), MedicationState.OFF),
        StoredResult(result("a", 1_000, 60), MedicationState.OFF),
        StoredResult(result("d", 4_000, 70), null),
    )

    @Test
    fun scoredResultsOldestFirst() {
        val points = Trends.points(history, AssessmentType.DUAL_TASK, hand = null)
        assertEquals(listOf("a", "c", "d"), points.map { it.assessmentId })
        assertEquals(listOf(MedicationState.OFF, MedicationState.ON, null), points.map { it.medicationState })
        assertEquals(listOf(AssessmentType.DUAL_TASK), Trends.typesWithScores(history))
        assertEquals(listOf(null), Trends.hands(history, AssessmentType.DUAL_TASK))
    }

    @Test
    fun meansByState() {
        val points = Trends.points(history, AssessmentType.DUAL_TASK, hand = null)
        assertEquals(80.0, Trends.mean(points, MedicationState.ON)!!, 1e-9)
        assertEquals(60.0, Trends.mean(points, MedicationState.OFF)!!, 1e-9)
        assertNull(Trends.mean(points, MedicationState.UNSURE))
    }

    @Test
    fun nothingScoredMeansNoTypes() {
        val unscored = listOf(StoredResult(result("x", 1, null), null))
        assertEquals(emptyList<AssessmentType>(), Trends.typesWithScores(unscored))
    }
}
