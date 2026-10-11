package com.example.parkinson.speech

import com.example.parkinson.assessment.QualityStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/** The pre-recording microphone check maps detected conditions to one instruction each (synthetic reports). */
class SpeechInputCheckTest {

    private fun report(vararg issues: SpeechQualityIssue, status: QualityStatus = QualityStatus.VALID) = SpeechQualityReport(
        status = status,
        issues = issues.toList(),
        durationMs = 2_000.0,
        sampleRateHz = 16_000,
        rmsDbfs = -20.0,
        clippedPercent = 0.0,
        noiseFloorDb = -60.0,
        snrDb = 25.0,
    )

    @Test
    fun aCleanSignalIsReady() {
        assertEquals(InputCheckResult.READY, SpeechInputCheck.interpret(report()))
    }

    @Test
    fun aWarningAloneStillCountsAsReady() {
        // A low-quality warning is reported on the result; it does not block the person from starting.
        assertEquals(
            InputCheckResult.READY,
            SpeechInputCheck.interpret(report(SpeechQualityIssue.LOW_SNR, status = QualityStatus.LOW_QUALITY)),
        )
    }

    @Test
    fun eachDetectedConditionHasItsOwnInstruction() {
        assertEquals(InputCheckResult.TOO_QUIET, SpeechInputCheck.interpret(report(SpeechQualityIssue.SILENCE)))
        assertEquals(InputCheckResult.CLIPPING, SpeechInputCheck.interpret(report(SpeechQualityIssue.CLIPPING)))
        assertEquals(InputCheckResult.NOISY, SpeechInputCheck.interpret(report(SpeechQualityIssue.UNUSABLE_SNR)))
        assertEquals(InputCheckResult.NO_SIGNAL, SpeechInputCheck.interpret(report(SpeechQualityIssue.NO_SAMPLES)))
        assertEquals(InputCheckResult.NO_SIGNAL, SpeechInputCheck.interpret(report(SpeechQualityIssue.TRUNCATED)))
    }

    @Test
    fun noSignalTakesPrecedenceOverTheLevelFindings() {
        assertEquals(
            InputCheckResult.NO_SIGNAL,
            SpeechInputCheck.interpret(report(SpeechQualityIssue.TRUNCATED, SpeechQualityIssue.SILENCE)),
        )
    }
}
