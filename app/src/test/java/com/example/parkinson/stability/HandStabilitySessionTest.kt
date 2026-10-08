package com.example.parkinson.stability

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.sensors.MotionSensorType
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
import kotlin.math.sin

/**
 * Fake accelerometer + gyroscope that emits on virtual time: every [periodMs] one sample of each
 * sensor, timestamped with the virtual clock. [stopAfterMs] simulates sensors that go silent.
 */
private class FakeMotionSource(
    private val scope: CoroutineScope,
    private val now: () -> Long,
    var accelAvailable: Boolean = true,
    var gyroAvailable: Boolean = true,
    var registerSucceeds: Boolean = true,
    var periodMs: Long = 10,
    var stopAfterMs: Long = Long.MAX_VALUE,
    var tremorHz: Double = 0.0
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
                val w = if (tremorHz > 0) (20.0 * sin(2 * PI * tremorHz * t / 1000.0) * PI / 180).toFloat() else 0.001f
                listener(MotionSample(MotionSensorType.ACCELEROMETER, t * 1_000_000, 0.01f, 0f, 9.81f))
                listener(MotionSample(MotionSensorType.GYROSCOPE, t * 1_000_000, w, 0.001f, -0.001f))
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
class HandStabilitySessionTest {

    private val saved = mutableListOf<HandStabilityResult>()

    private fun TestScope.source() = FakeMotionSource(backgroundScope, { testScheduler.currentTime })

    /** Past preparation + recording + processing. The session runs in backgroundScope, so advance time explicitly. */
    private fun TestScope.finish() {
        advanceTimeBy(30_000)
        runCurrent()
    }

    private fun TestScope.newSession(
        source: MotionSensorSource,
        onCompleted: suspend (HandStabilityResult) -> Unit = { saved += it }
    ) = HandStabilitySession(
        scope = backgroundScope,
        source = source,
        clock = { testScheduler.currentTime },
        wallClock = { 1_700_000_000_000L },
        newAssessmentId = { "hs-1" },
        processingDispatcher = StandardTestDispatcher(testScheduler),
        onCompleted = onCompleted
    )

    @Test
    fun fullRunGoesThroughPreparationRecordingAndIsSaved() = runTest {
        val src = source()
        val session = newSession(src)
        session.start(SelectedHand.LEFT)
        runCurrent()
        assertEquals(StabilityState.Preparation(5), session.state.value)

        advanceTimeBy(4_100)
        assertEquals(StabilityState.Preparation(1), session.state.value)
        advanceTimeBy(1_000)
        assertEquals(StabilityState.Recording(15), session.state.value)
        advanceTimeBy(13_950)
        assertEquals(StabilityState.Recording(1), session.state.value)
        finish()

        val done = session.state.value as StabilityState.Done
        val r = done.result
        assertEquals("hs-1", r.assessmentId)
        assertEquals(SelectedHand.LEFT, r.hand)
        assertEquals(QualityStatus.VALID, r.qualityStatus)
        // Only the 15 s recording window is kept (100 Hz), not the preparation.
        assertEquals(1_500.0, r.accSampleCount.toDouble(), 3.0)
        assertEquals(100.0, r.gyroSamplingRateHz, 0.5)
        assertEquals(listOf(r), saved)
        assertFalse("sensors stopped after the test", src.running)
        assertEquals(HandStabilityVersions.ALGORITHM_VERSION, r.algorithmVersion)
    }

    @Test
    fun missingSensorIsReportedImmediately() = runTest {
        val src = source().apply { gyroAvailable = false }
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        runCurrent()
        assertEquals(StabilityState.Error(StabilityError.SENSOR_UNAVAILABLE), session.state.value)
        assertEquals(0, src.starts)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun failedRegistrationIsReported() = runTest {
        val src = source().apply { registerSucceeds = false }
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        runCurrent()
        assertEquals(StabilityState.Error(StabilityError.SENSOR_UNAVAILABLE), session.state.value)
    }

    @Test
    fun sensorsThatStopDeliveringEndTheTest() = runTest {
        val src = source().apply { stopAfterMs = 8_000 }
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        finish()
        assertEquals(StabilityState.Error(StabilityError.SENSOR_STOPPED), session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun interruptionDuringRecordingGivesNoResult() = runTest {
        val src = source()
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(9_000)
        assertTrue(session.state.value is StabilityState.Recording)
        session.abort()
        assertEquals(StabilityState.Invalid(StabilityInvalidReason.Interrupted), session.state.value)
        finish()
        assertEquals(StabilityState.Invalid(StabilityInvalidReason.Interrupted), session.state.value)
        assertTrue(saved.isEmpty())
        assertFalse(src.running)
    }

    @Test
    fun interruptionDuringPreparationGivesNoResult() = runTest {
        val session = newSession(source())
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(2_000)
        session.abort()
        finish()
        assertEquals(StabilityState.Invalid(StabilityInvalidReason.Interrupted), session.state.value)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun lowSamplingRateIsRejectedByQualityControl() = runTest {
        val src = source().apply { periodMs = 125 } // 8 Hz
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        finish()
        val invalid = session.state.value as StabilityState.Invalid
        val report = (invalid.reason as StabilityInvalidReason.QualityRejected).report
        assertEquals(StabilityQualityIssue.SAMPLING_RATE_TOO_LOW, report.primaryIssue)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun storageFailureIsAnErrorNotAResult() = runTest {
        val session = newSession(source(), onCompleted = { error("disk full") })
        session.start(SelectedHand.RIGHT)
        finish()
        assertEquals(StabilityState.Error(StabilityError.STORAGE_FAILURE), session.state.value)
    }

    @Test
    fun oscillationIsMeasuredThroughTheSession() = runTest {
        val src = source().apply { tremorHz = 6.0 }
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        finish()
        val r = (session.state.value as StabilityState.Done).result
        assertEquals(6.0, r.dominantFrequencyHz!!, 0.2)
    }

    @Test
    fun startIsIgnoredWhileActiveAndResetReturnsToIdle() = runTest {
        val src = source()
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(1_000)
        session.start(SelectedHand.LEFT)
        assertEquals(1, src.starts)
        session.reset()
        assertEquals(StabilityState.Idle, session.state.value)
        assertFalse(src.running)
    }

    @Test
    fun retryAfterInterruptionStartsAFreshRun() = runTest {
        val src = source()
        val session = newSession(src)
        session.start(SelectedHand.RIGHT)
        advanceTimeBy(7_000)
        session.abort()
        session.start(SelectedHand.RIGHT)
        finish()
        val r = (session.state.value as StabilityState.Done).result
        assertEquals(1_500.0, r.gyroSampleCount.toDouble(), 3.0)
        assertEquals(1, saved.size)
    }
}
