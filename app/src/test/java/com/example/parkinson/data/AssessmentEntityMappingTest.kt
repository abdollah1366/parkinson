package com.example.parkinson.data

import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.FingerTappingAnalyzer
import com.example.parkinson.tapping.SyntheticTapping
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.tapping.raw.FrameStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AssessmentEntityMappingTest {

    private fun assessment(s: SyntheticTapping) = FingerTappingAnalyzer.toAssessment(
        FingerTappingAnalyzer().analyze(s.recording()), "id-42", 1_700_000_000_000L, SelectedHand.LEFT
    )

    @Test
    fun validAssessmentRoundTripsUnchanged() {
        val a = assessment(SyntheticTapping().regular(3.0))
        assertEquals(a, a.toEntity().toDomain())
    }

    @Test
    fun lowQualityAssessmentRoundTripsWithItsLimitedScore() {
        val a = assessment(
            SyntheticTapping().regular(3.0)
                .status(2_000, 2_600, FrameStatus.NO_HAND)
                .status(5_000, 5_600, FrameStatus.NO_HAND)
                .status(8_000, 8_600, FrameStatus.NO_HAND)
        )
        assertEquals(QualityStatus.LOW_QUALITY, a.qualityStatus)
        // Previously a LOW_QUALITY result was stored WITHOUT a score; now it keeps one, marked LIMITED.
        assertEquals(com.example.parkinson.assessment.ReliabilityLevel.LIMITED, a.performanceScore!!.reliability)
        val back = a.toEntity().toDomain()
        assertEquals(a, back)
        assertEquals(a.performanceScore, back.performanceScore)
        assertEquals(a.payload, back.payload)
    }

    @Test
    fun unknownStoredNamesDegradeSafely() {
        val entity = assessment(SyntheticTapping().regular(3.0)).toEntity()
            .copy(qualityStatus = "SOMETHING_NEW", qualityIssues = "LOW_FPS,FUTURE_ISSUE", hand = "?")
        val back = entity.toDomain()
        assertEquals(QualityStatus.INVALID, back.qualityStatus)
        assertEquals(1, back.qualityIssues.size)
        assertEquals(SelectedHand.RIGHT, back.hand)
    }

    @Test
    fun emptyIssueListRoundTrips() {
        val a = assessment(SyntheticTapping().regular(3.0))
        assertEquals(emptyList<Any>(), a.copy(qualityIssues = emptyList()).toEntity().toDomain().qualityIssues)
    }
}
