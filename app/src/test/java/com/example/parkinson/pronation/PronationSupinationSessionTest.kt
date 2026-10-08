package com.example.parkinson.pronation

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.sensors.MotionSensorType
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.coroutines.CoroutineContext

/**
 * Fake accelerometer + gyroscope on virtual time: every [periodMs] one sample of each sensor.
 * The phone is still until [rotateAfterMs] after start, then rotates at [rotationHz] (90 deg
 * peak-to-peak) unless [rotationHz] is 0. [stopAfterMs] simulates sensors that go silent.
 */
private class FakeRotationSource(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    var accelAvailable: Boolean = true,
    var gyroAvailable: Boolean = true,
    var registerSucceeds: Boolean = true,
    var periodMs: Long = 10,
    var stopAfterMs: Long = Long.MAX_VALUE,
    var rotateAfterMs: Long = 8_000,
    var rotateUntilMs: Long = Long.MAX_VALUE,
    var rotationHz: Double = 1.5
) : MotionSensorSource {
    var starts = 0
    var stops = 0
    private var job: Job? = null
    val running: Boolean get() = job?.isActive == true

    override fun isAvailable(type: MotionSensorType) =
        if (type == MotionSensorType.ACCELEROMETER) accelAvailable else gyroAvailable

    override fun start(samplingPeriodUs: Int, listener: (MotionSample) -> Unit): Boolean {
        if (!registerSucceeds) return false
        starts++
        val startedAt = now()
        job = scope.launch {
            while (now() - startedAt < stopAfterMs) {
                val t = now()
                val sinceRotation = (t - startedAt - rotateAfterMs) / 1000.0
                // d/dt of 45 sin(2 pi f t) in rad/s.
                val rotating = t - startedAt < rotateUntilMs
                val w = if (rotationHz > 0 && sinceRotation >= 0 && rotating) {
                    (45.0 * 2 * PI * rotationHz * cos(2 * PI * rotationHz * sinceRotation) * PI / 180).toFloat()
                } else 0.001f
                listener(MotionSample(MotionSensorType.ACCELEROMETER, t * 1_000_000, 0.01f, 0f, 9.81f))
                listener(MotionSample(MotionSensorType.GYROSCOPE, t * 1_000_000, 0.001f, w, -0.001f))
                delay(periodMs)
            }
        }
        return true
    }

    override fun stop() {
        stops++
        job?.cancel()
        job = null
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class PronationSupinationSessionTest {

    private val saved = mutableListOf<PronationSupinationResult>()

    private fun TestScope.source() = FakeRotationSource(backgroundScope, { testScheduler.currentTime })

    private fun TestScope.newSession(
        source: MotionSensorSource,
        onCompleted: suspend (PronationSupinationResult) -> Unit = { saved += it }
    ) = PronationSupinationSession(
        scope = backgroundScope,
        source = source,
        clock = { testScheduler.currentTime },
        wallClock = { 1_700_000_000_000L },
        newAssessmentId = { "ps-1" },
        processingDispatcher = StandardTestDispatcher(testScheduler),
        onCompleted = onCompleted
    )

    /** Past preparation + countdown + recording + processing (virtual time, no real delay). */
    private fun TestScope.finish() {
        advanceTimeBy(30_000)
        runCurrent()
    }

    @Test
    fun fullRunGoesThroughEveryPhaseAndIsSaved() = runTest {
        val src = source()
        val session = newSession(src)
        session.start(SelectedHand.LEFT)
        runCurrent()
        assertEquals(PronationState.Preparing(5), session.state.value)

        advanceTimeBy(4_100)
        assertEquals(PronationState.Preparing(1), session.state.value)
        advanceTimeBy(1_000)
        assertEquals(PronationState.Countdown(3), session.state.value)
        advanceTimeBy(1_000)
        assertEquals(PronationState.Countdown(2), session.state.value)
        advanceTimeBy(1_000)
        assertEquals(PronationState.Countdown(1), session.state.value)
        advanceTimeBy(1_000)
        assertEquals(PronationState.Recording(10, showStartCue = true), session.state.value)
        advanceTimeBy(1_000)
        assertEquals(PronationState.Recording(9, showStartCue = false), session.state.value)
        advanceTimeBy(7_950)
        assertEquals(PronationState.Recording(1, showStartCue = false), session.state.value)
        finish()

        val r = (session.state.value as PronationState.Done).result
        assertEquals("ps-1", r.assessmentId)
        assertEquals(SelectedHand.LEFT, r.hand)
        assertEquals(QualityStatus.VALID, r.qualityStatus)
        assertTrue("cycles ${r.cycleCount}", r.cycleCount in 13..15)
        assertEquals(100.0, r.effectiveSamplingRate, 0.5)
        assertEquals(listOf(r), saved)
        assertFalse("sensors stopped after the test", src.running)
        assertEquals(PronationSupinationVersions.ALGORITHM_VERSION, r.algorithmVersion)
    }

    @Test
    fun abortDuringRecordingIsInterruptedAndNothingIsStored() = runTest {
        val src = source()
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(12_000)
        assertTrue(session.state.value is PronationState.Recording)
        session.abort()
        assertEquals(PronationState.Interrupted, session.state.value)
        assertFalse(src.running)
        finish()
        assertEquals(PronationState.Interrupted, session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun abortDuringPreparationOrCountdownIsInterrupted() = runTest {
        for (at in listOf(1_000L, 6_000L)) {
            val session = newSession(source())
            session.start(SelectedHand.RIGHT)
            advanceTimeBy(at)
            session.abort()
            assertEquals(PronationState.Interrupted, session.state.value)
            finish()
            assertEquals(PronationState.Interrupted, session.state.value)
        }
        assertTrue(saved.isEmpty())
    }

    @Test
    fun abortWhileProcessingDiscardsTheAnalysis() = runTest {
        // Holds the analysis until released, so the session is observably PROCESSING.
        val pending = mutableListOf<Runnable>()
        val manual = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                pending += block
            }
        }
        val session = PronationSupinationSession(
            scope = backgroundScope,
            source = source(),
            clock = { testScheduler.currentTime },
            processingDispatcher = manual,
            onCompleted = { saved += it }
        )
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(18_000)
        runCurrent()
        assertEquals(PronationState.Processing, session.state.value)
        // Leaving the app (abort) wins over an analysis that is still running.
        session.abort()
        pending.toList().forEach { it.run() }
        finish()
        assertEquals(PronationState.Interrupted, session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun noRotationIsRejectedByQualityControlAndNotStored() = runTest {
        val src = source().apply { rotationHz = 0.0 }
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        finish()
        val invalid = session.state.value as PronationState.Invalid
        assertEquals(QualityStatus.INSUFFICIENT_DATA, invalid.report.status)
        assertEquals(PronationQualityIssue.NO_MOVEMENT_DETECTED, invalid.report.primaryIssue)
        assertTrue(saved.isEmpty())
        assertFalse(src.running)
    }

    @Test
    fun movementBeforeRecordingIsNotAnalyzed() = runTest {
        // Rotation during preparation and countdown only: the recording itself has no movement.
        val src = source().apply { rotateAfterMs = 0; rotateUntilMs = 8_000 }
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        finish()
        val invalid = session.state.value as PronationState.Invalid
        assertEquals(PronationQualityIssue.NO_MOVEMENT_DETECTED, invalid.report.primaryIssue)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun missingGyroscopeIsReportedImmediately() = runTest {
        val src = source().apply { gyroAvailable = false }
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        runCurrent()
        assertEquals(PronationState.Error(PronationError.SENSOR_UNAVAILABLE), session.state.value)
        assertEquals(0, src.starts)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun failedRegistrationIsReported() = runTest {
        val src = source().apply { registerSucceeds = false }
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        runCurrent()
        assertEquals(PronationState.Error(PronationError.SENSOR_UNAVAILABLE), session.state.value)
    }

    @Test
    fun sensorInterruptionDuringRecordingIsAnError() = runTest {
        val src = source().apply { stopAfterMs = 12_000 }
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        finish()
        assertEquals(PronationState.Error(PronationError.SENSOR_STOPPED), session.state.value)
        assertTrue(saved.isEmpty())
        assertFalse(src.running)
    }

    @Test
    fun storageFailureIsReportedNotShownAsResult() = runTest {
        val session = newSession(source()) { error("disk full") }
        session.start(SelectedHand.RIGHT)
        finish()
        assertEquals(PronationState.Error(PronationError.STORAGE_FAILURE), session.state.value)
    }

    @Test
    fun retryAfterAnOutcomeStartsAFreshRun() = runTest {
        val src = source().apply { rotationHz = 0.0 }
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        finish()
        assertTrue(session.state.value is PronationState.Invalid)

        src.rotationHz = 1.5
        session.start(SelectedHand.RIGHT)
        runCurrent()
        assertEquals(PronationState.Preparing(5), session.state.value)
        finish()
        assertTrue(session.state.value is PronationState.Done)
        assertEquals(2, src.starts)
        assertEquals(1, saved.size)
    }

    @Test
    fun resetReturnsToIdleAndStopsSensors() = runTest {
        val src = source()
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(2_000)
        session.reset()
        assertEquals(PronationState.Idle, session.state.value)
        assertFalse(src.running)
        finish()
        assertEquals(PronationState.Idle, session.state.value)
    }
}
