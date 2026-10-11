package com.example.parkinson.imu

import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSampleSink
import com.example.parkinson.sensors.MotionSensorRepository
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.sensors.MotionSensorType
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

/** Gait session flow on a FAKE sensor source and SYNTHETIC samples (test input only). */
class ImuGaitSessionFlowTest {

    private class FakeSource : MotionSensorSource {
        private var sink: MotionSampleSink? = null
        var registrations = 0

        override fun isAvailable(type: MotionSensorType): Boolean = true
        override fun start(samplingPeriodUs: Int, listener: (MotionSample) -> Unit): Boolean = false
        override fun startRaw(
            samplingPeriodUs: Int,
            required: Set<MotionSensorType>,
            optional: Set<MotionSensorType>,
            sink: MotionSampleSink,
        ): Boolean {
            registrations++
            this.sink = sink
            return true
        }

        override fun stop() {
            sink = null
        }

        fun emit(type: MotionSensorType, tsNs: Long, v: DoubleArray) {
            sink?.onSample(type, tsNs, v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), false)
        }
    }

    private val base = SyntheticImu.BASE_NS

    /** Still for [stillMs] (gravity only), then walking (gravity plus a 1 Hz stride), sampled at 100 Hz. */
    private fun TestScope.feed(source: FakeSource, stillMs: Long, durationMs: Long) = backgroundScope.launch {
        val startMs = testScheduler.currentTime
        while (testScheduler.currentTime - startMs < durationMs) {
            val elapsed = testScheduler.currentTime - startMs
            val t = elapsed / 1000.0
            val ts = base + testScheduler.currentTime * 1_000_000L
            val gravity = SyntheticImu.gravityAt(0.0)
            val accel = if (elapsed < stillMs) gravity else {
                val w = SyntheticImu.walkingAccel(t)
                doubleArrayOf(gravity[0] + w[0], gravity[1] + w[1], gravity[2] + w[2])
            }
            source.emit(MotionSensorType.ACCELEROMETER, ts, accel)
            source.emit(MotionSensorType.GYROSCOPE, ts, DoubleArray(3))
            delay(10)
        }
    }

    @Test
    fun standingCalibrationContinuesToCountdownAndWalkingWithoutLeavingTheAssessment() = runTest {
        val source = FakeSource()
        val session = ImuGaitSession(
            scope = this,
            repository = MotionSensorRepository(source),
            clock = { testScheduler.currentTime },
            nanoClock = { base + testScheduler.currentTime * 1_000_000L },
            wallClock = { 1_700_000_000_000L },
            newId = { "gait-result" },
            newSessionId = { "visit" },
            processingDispatcher = StandardTestDispatcher(testScheduler),
        )
        val trace = mutableListOf<ImuGaitState>()
        backgroundScope.launch { session.state.collect { if (trace.lastOrNull() != it) trace += it } }
        feed(source, stillMs = 3_000L, durationMs = 40_000L)

        session.start()
        advanceTimeBy(5_000L)
        runCurrent()
        val afterStanding = session.state.value
        advanceTimeBy(35_000L)
        runCurrent()

        val names = trace.map { it::class.simpleName }
        // Stages in order: standing calibration, countdown, walking. The first published state is IDLE.
        val calibrating = names.indexOf("Calibrating")
        val countdown = names.indexOf("Countdown")
        val walking = names.indexOf("Walking")
        assertTrue("trace: $names", calibrating >= 0 && countdown > calibrating && walking > countdown)
        // The standing stage is not an outcome: the session is still running after it.
        assertTrue("state after standing: $afterStanding", afterStanding is ImuGaitState.Countdown || afterStanding is ImuGaitState.Walking)
        // No error is published on the way from standing to walking.
        assertFalse("trace: $names", names.contains("Error") || names.contains("Invalid"))
        assertEquals("trace: $names", "Done", names.last { it == "Done" || it == "Invalid" || it == "Error" })
        // Session continuity: the stages belong to one session, so the final result carries its identity.
        val done = session.state.value as ImuGaitState.Done
        assertEquals("visit", done.result.sessionId)
    }
}
