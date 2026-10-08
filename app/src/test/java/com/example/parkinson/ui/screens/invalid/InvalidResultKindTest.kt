package com.example.parkinson.ui.screens.invalid

import com.example.parkinson.tapping.SessionError
import com.example.parkinson.tapping.SessionInvalidReason
import com.example.parkinson.tapping.SessionState
import com.example.parkinson.tapping.quality.QualityIssue
import com.example.parkinson.tapping.quality.QualityReport
import com.example.parkinson.assessment.QualityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InvalidResultKindTest {

    private fun rejected(issue: QualityIssue) = SessionState.Invalid(
        SessionInvalidReason.QualityRejected(
            QualityReport(issue.severity, listOf(issue), 0, 0.0, 0.0, 0.0, 0, null, null)
        )
    )

    @Test
    fun everyQualityRejectionHasASpecificMessage() {
        assertEquals(InvalidResultKind.NO_HAND, InvalidResultKind.from(rejected(QualityIssue.NO_HAND_DETECTED)))
        assertEquals(InvalidResultKind.WRONG_HAND, InvalidResultKind.from(rejected(QualityIssue.WRONG_HAND)))
        assertEquals(InvalidResultKind.MULTIPLE_HANDS, InvalidResultKind.from(rejected(QualityIssue.MULTIPLE_HANDS)))
        assertEquals(InvalidResultKind.INSUFFICIENT_FPS, InvalidResultKind.from(rejected(QualityIssue.INSUFFICIENT_FPS)))
        assertEquals(InvalidResultKind.EXCESSIVE_DROPOUT, InvalidResultKind.from(rejected(QualityIssue.EXCESSIVE_DROPOUT)))
        assertEquals(InvalidResultKind.INSUFFICIENT_FRAMES, InvalidResultKind.from(rejected(QualityIssue.NO_FRAMES)))
        assertEquals(InvalidResultKind.RECORDING_TOO_SHORT, InvalidResultKind.from(rejected(QualityIssue.RECORDING_INCOMPLETE)))
        assertEquals(InvalidResultKind.NO_TAPPING, InvalidResultKind.from(rejected(QualityIssue.NO_TAPPING_DETECTED)))
        assertEquals(InvalidResultKind.TOO_FEW_TAPS, InvalidResultKind.from(rejected(QualityIssue.TOO_FEW_TAPS)))
    }

    @Test
    fun sessionErrorsAndInterruptionMap() {
        assertEquals(InvalidResultKind.INTERRUPTED, InvalidResultKind.from(SessionState.Invalid(SessionInvalidReason.Interrupted)))
        assertEquals(InvalidResultKind.CAMERA_ERROR, InvalidResultKind.from(SessionState.Error(SessionError.FRAME_STARVATION)))
        assertEquals(InvalidResultKind.CAMERA_ERROR, InvalidResultKind.from(SessionState.Error(SessionError.CAMERA_FAILURE)))
        assertEquals(InvalidResultKind.TRACKING_ERROR, InvalidResultKind.from(SessionState.Error(SessionError.TRACKING_FAILURE)))
        assertEquals(InvalidResultKind.STORAGE_ERROR, InvalidResultKind.from(SessionState.Error(SessionError.STORAGE_FAILURE)))
    }

    @Test
    fun nonTerminalStatesHaveNoKind() {
        assertNull(InvalidResultKind.from(SessionState.Idle))
        assertNull(InvalidResultKind.from(SessionState.Processing))
    }

    @Test
    fun navigationArgumentRoundTripsAndUnknownIsSafe() {
        InvalidResultKind.entries.forEach { assertEquals(it, InvalidResultKind.fromName(it.name)) }
        assertEquals(InvalidResultKind.UNEXPECTED_ERROR, InvalidResultKind.fromName("bogus"))
        assertEquals(InvalidResultKind.UNEXPECTED_ERROR, InvalidResultKind.fromName(null))
        assertEquals(QualityStatus.INVALID, QualityIssue.WRONG_HAND.severity)
    }
}
