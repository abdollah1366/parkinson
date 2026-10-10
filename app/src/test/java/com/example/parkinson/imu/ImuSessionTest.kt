package com.example.parkinson.imu

import com.example.parkinson.sensors.MotionSampleSink
import com.example.parkinson.sensors.MotionSensorRepository
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.sensors.MotionSensorType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * IMU session flow on a FAKE sensor source and SYNTHETIC events under virtual time. No device sensor is used. These
 * tests check the lifecycle, listener handling and result rules, not the sensor hardware.
 */
class ImuSessionTest {

    /** Scripted sensor source: records registrations, delivers events through the sink while registered. */
    private class FakeSensorSource(
        var accelerometer: Boolean = true,
        var gyroscope: Boolean = true,
    ) : MotionSensorSource {
        var registrations = 0
        var stops = 0
        private var sink: MotionSampleSink? = null
        val running: Boolean get() = sink != null

        override fun isAvailable(type: MotionSensorType): Boolean =
            if (type == MotionSensorType.ACCELEROMETER) accelerometer else gyroscope

        override fun start(samplingPeriodUs: Int, listener: (com.example.parkinson.sensors.MotionSample) -> Unit): Boolean = false

        override fun startRaw(
            samplingPeriodUs: Int,
            required: Set<MotionSensorType>,
            optional: Set<MotionSensorType>,
            sink: MotionSampleSink,
        ): Boolean {
            if (!accelerometer || !gyroscope) return false
            registrations++
            this.sink = sink
            return true
        }

        override fun stop() {
            stops++
            sink = null
        }

        fun emit(type: MotionSensorType, tsNs: Long, v: DoubleArray) {
            sink?.onSample(type, tsNs, v[0].toFloat(), v[1].toFloat(), v[2].toFloat(), false)
        }
    }

    private val base = SyntheticImu.BASE_NS

    /** Feeds both sensors from the profile at 100 Hz while [enabled] returns true, starting at the current time. */
    private fun TestScope.feed(
        source: FakeSensorSource,
        profile: SyntheticImu.TransferProfile,
        durationMs: Long,
        enabled: () -> Boolean = { true },
    ) = launch {
        val startMs = testScheduler.currentTime
        while (testScheduler.currentTime - startMs < durationMs) {
            if (enabled()) {
                val t = (testScheduler.currentTime - startMs) / 1000.0
                val ts = base + testScheduler.currentTime * 1_000_000L
                source.emit(MotionSensorType.ACCELEROMETER, ts, profile.accel(t))
                source.emit(MotionSensorType.GYROSCOPE, ts, profile.gyro(t))
            }
            delay(10)
        }
    }

    private fun TestScope.sitToStand(
        source: FakeSensorSource,
        out: MutableList<ImuSitToStandResult>,
        save: suspend (ImuSitToStandResult) -> Unit = { out += it },
    ): ImuSitToStandSession = ImuSitToStandSession(
        scope = this,
        repository = MotionSensorRepository(source),
        config = ImuSitToStandSessionConfig(),
        clock = { testScheduler.currentTime },
        nanoClock = { base + testScheduler.currentTime * 1_000_000L },
        wallClock = { 1_700_000_000_000L },
        newId = { "sts-session" },
        newSessionId = { "visit" },
        processingDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
        onCompleted = save,
    )

    @Test
    fun withoutAnAccelerometerNoRecordingStartsAndTheStateSaysSo() = runTest {
        val source = FakeSensorSource(accelerometer = false)
        val session = sitToStand(source, mutableListOf())
        session.start()
        assertEquals(ImuSitToStandState.Error(ImuError.SENSOR_UNAVAILABLE), session.state.value)
        assertEquals(0, source.registrations)
    }

    @Test
    fun aFullFiveTransferSessionCalibratesCountsAndSavesOnce() = runTest {
        val source = FakeSensorSource()
        val saved = mutableListOf<ImuSitToStandResult>()
        val session = sitToStand(source, saved)
        // Calibration 0 - 2.5 s and countdown 2.5 - 5.5 s are seated and still; transfers start at 6 s.
        val profile = SyntheticImu.TransferProfile(calibrationS = 6.0, cycles = 5)
        feed(source, profile, durationMs = 30_000L)
        session.start()
        advanceTimeBy(32_000L)
        runCurrent()
        val done = session.state.value as ImuSitToStandState.Done
        assertEquals(5, done.result.repetitions.size)
        assertEquals(1, saved.size)
        assertEquals(done.result, saved.single())
        assertEquals(1, source.registrations)
        assertTrue(!source.running)
    }

    @Test
    fun theWriteRunsInTheSavingStateAndDoneIsPublishedOnlyAfterIt() = runTest {
        val source = FakeSensorSource()
        val saved = mutableListOf<ImuSitToStandResult>()
        lateinit var session: ImuSitToStandSession
        val statesDuringWrite = mutableListOf<ImuSitToStandState>()
        session = sitToStand(source, saved, save = { result ->
            statesDuringWrite += session.state.value
            delay(1_000L)
            saved += result
        })
        feed(source, SyntheticImu.TransferProfile(calibrationS = 6.0, cycles = 5), durationMs = 30_000L)
        session.start()
        advanceTimeBy(32_000L)
        runCurrent()
        assertEquals(listOf<ImuSitToStandState>(ImuSitToStandState.Saving), statesDuringWrite)
        assertEquals(1, saved.size)
        assertEquals(ImuSitToStandState.Done(saved.single()), session.state.value)
    }

    @Test
    fun anAbortArrivingDuringTheWriteIsRefusedAndTheMeasurementStillCompletes() = runTest {
        val source = FakeSensorSource()
        val saved = mutableListOf<ImuSitToStandResult>()
        lateinit var session: ImuSitToStandSession
        session = sitToStand(source, saved, save = { result ->
            // Screen-off or back press while the result is being written.
            session.abort()
            delay(1_000L)
            saved += result
        })
        feed(source, SyntheticImu.TransferProfile(calibrationS = 6.0, cycles = 5), durationMs = 30_000L)
        session.start()
        advanceTimeBy(32_000L)
        runCurrent()
        assertEquals(1, saved.size)
        assertTrue("state=${session.state.value}", session.state.value is ImuSitToStandState.Done)
    }

    @Test
    fun aFailedWriteReportsAStorageErrorAndPublishesNoResult() = runTest {
        val source = FakeSensorSource()
        val session = sitToStand(source, mutableListOf(), save = { throw IllegalStateException("disk full") })
        feed(source, SyntheticImu.TransferProfile(calibrationS = 6.0, cycles = 5), durationMs = 30_000L)
        session.start()
        advanceTimeBy(32_000L)
        runCurrent()
        assertEquals(ImuSitToStandState.Error(ImuError.STORAGE_FAILURE), session.state.value)
        assertTrue(!source.running)
    }

    @Test
    fun theFinishedOutcomeIsClaimedOnceSoARecreatedScreenDoesNotNavigateAgain() = runTest {
        val source = FakeSensorSource()
        val saved = mutableListOf<ImuSitToStandResult>()
        val session = sitToStand(source, saved)
        feed(source, SyntheticImu.TransferProfile(calibrationS = 6.0, cycles = 5), durationMs = 30_000L)
        session.start()
        advanceTimeBy(32_000L)
        runCurrent()
        assertTrue(session.claimOutcome())
        assertTrue(!session.claimOutcome())
    }

    @Test
    fun startingAgainAfterACompletedRunRegistersNothingAndSavesNothing() = runTest {
        val source = FakeSensorSource()
        val saved = mutableListOf<ImuSitToStandResult>()
        val session = sitToStand(source, saved)
        feed(source, SyntheticImu.TransferProfile(calibrationS = 6.0, cycles = 5), durationMs = 30_000L)
        session.start()
        advanceTimeBy(32_000L)
        runCurrent()
        assertTrue(session.state.value is ImuSitToStandState.Done)
        session.start()
        advanceTimeBy(1_000L)
        assertEquals(1, source.registrations)
        assertEquals(1, saved.size)
    }

    @Test
    fun aSecondStartWhileRunningDoesNotRegisterASecondListener() = runTest {
        val source = FakeSensorSource()
        val session = sitToStand(source, mutableListOf())
        feed(source, SyntheticImu.TransferProfile(calibrationS = 60.0), durationMs = 5_000L)
        session.start()
        advanceTimeBy(500L)
        session.start()
        assertEquals(1, source.registrations)
        session.reset()
        advanceTimeBy(100L)
        assertEquals(ImuSitToStandState.Idle, session.state.value)
        assertTrue(!source.running)
    }

    @Test
    fun aMovingPhoneFailsCalibrationAndReturnsToIdleWithTheReason() = runTest {
        val source = FakeSensorSource()
        val session = sitToStand(source, mutableListOf())
        // The phone is rotating throughout: the still-period check must fail.
        feed(source, SyntheticImu.TransferProfile(calibrationS = 0.0, cycles = 5), durationMs = 4_000L)
        session.start()
        advanceTimeBy(3_000L)
        runCurrent()
        assertEquals(ImuSitToStandState.Idle, session.state.value)
        assertEquals(CalibrationIssue.NOT_STILL, session.calibrationIssue.value)
        assertTrue(!source.running)
    }

    @Test
    fun pausingReleasesTheListenerAndResumingRegistersItAgain() = runTest {
        val source = FakeSensorSource()
        val session = sitToStand(source, mutableListOf())
        feed(source, SyntheticImu.TransferProfile(calibrationS = 6.0), durationMs = 12_000L) { true }
        session.start()
        advanceTimeBy(7_000L)
        runCurrent()
        assertTrue(session.state.value is ImuSitToStandState.Active)
        session.pause()
        assertTrue(!source.running)
        assertTrue(session.state.value is ImuSitToStandState.Paused)
        session.resume()
        assertEquals(2, source.registrations)
        assertTrue(source.running)
        session.reset()
    }

    @Test
    fun aPauseInsideTheTimedSpanMakesTheResultInvalidNotMeasured() = runTest {
        val source = FakeSensorSource()
        val saved = mutableListOf<ImuSitToStandResult>()
        val session = sitToStand(source, saved)
        // Transfers from 6 s. The pause lasts 3 s inside the first transfer, while the person is standing.
        val profile = SyntheticImu.TransferProfile(calibrationS = 6.0, cycles = 5)
        // The sensor feed runs for the whole attempt, so the session ends by its own rules, not by a stopped feed.
        feed(source, profile, durationMs = 100_000L) { true }
        session.start()
        advanceTimeBy(7_500L)
        runCurrent()
        session.pause()
        advanceTimeBy(3_000L)
        session.resume()
        advanceTimeBy(80_000L)
        runCurrent()
        // Either the pause lies inside the timed span, or the attempt ends by its time limit: never a saved result.
        val state = session.state.value
        assertTrue("state=$state", state is ImuSitToStandState.Invalid)
        assertEquals(0, saved.size)
    }

    @Test
    fun abortingStopsTheListenerAndProducesNoResult() = runTest {
        val source = FakeSensorSource()
        val saved = mutableListOf<ImuSitToStandResult>()
        val session = sitToStand(source, saved)
        feed(source, SyntheticImu.TransferProfile(calibrationS = 6.0), durationMs = 12_000L)
        session.start()
        advanceTimeBy(7_000L)
        session.abort()
        advanceTimeBy(100L)
        assertEquals(ImuSitToStandState.Invalid(ImuFailure.Interrupted), session.state.value)
        assertTrue(!source.running)
        assertTrue(saved.isEmpty())
    }

    @Test
    fun sensorEventsStoppingDuringTheAssessmentIsReportedAsStarvation() = runTest {
        val source = FakeSensorSource()
        val session = sitToStand(source, mutableListOf())
        // Events for 6 s, then nothing: the listener stays registered but delivers no events.
        feed(source, SyntheticImu.TransferProfile(calibrationS = 60.0), durationMs = 6_000L)
        session.start()
        advanceTimeBy(12_000L)
        runCurrent()
        assertEquals(ImuSitToStandState.Error(ImuError.SENSOR_STARVED), session.state.value)
        assertTrue(!source.running)
    }

    @Test
    fun gaitSessionCountsStepsOnTheSensorClockAndSavesTheWalk() = runTest {
        val source = FakeSensorSource()
        val saved = mutableListOf<ImuGaitResult>()
        val config = ImuGaitSessionConfig(walkingMs = 12_000L)
        val session = ImuGaitSession(
            scope = this,
            repository = MotionSensorRepository(source),
            config = config,
            clock = { testScheduler.currentTime },
            nanoClock = { base + testScheduler.currentTime * 1_000_000L },
            wallClock = { 1_700_000_000_000L },
            newId = { "gait-session" },
            newSessionId = { "visit" },
            processingDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            onCompleted = { saved += it },
        )
        // Standing still 0 - 3 s (calibration), countdown 3 - 6 s, then 12 s of walking from 6 s.
        feedGait(source, startAtMs = 0L, durationMs = 25_000L)
        session.start()
        advanceTimeBy(26_000L)
        runCurrent()
        val done = session.state.value
        assertTrue("state=$done", done is ImuGaitState.Done)
        assertEquals(1, saved.size)
        // Two steps per second for 12 s of walking: about 24 steps.
        assertTrue("steps=${saved.single().steps}", saved.single().steps in 18..26)
        assertEquals("gait-session", saved.single().assessmentId)
    }

    @Test
    fun anAbortDuringTheGaitWriteIsRefusedAndTheWalkIsSavedOnceAsDone() = runTest {
        val source = FakeSensorSource()
        val saved = mutableListOf<ImuGaitResult>()
        lateinit var session: ImuGaitSession
        session = ImuGaitSession(
            scope = this,
            repository = MotionSensorRepository(source),
            config = ImuGaitSessionConfig(walkingMs = 12_000L),
            clock = { testScheduler.currentTime },
            nanoClock = { base + testScheduler.currentTime * 1_000_000L },
            wallClock = { 1_700_000_000_000L },
            newId = { "gait-session" },
            newSessionId = { "visit" },
            processingDispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            onCompleted = { result ->
                // Screen-off or back press while the walk is being written.
                session.abort()
                delay(1_000L)
                saved += result
            },
        )
        feedGait(source, startAtMs = 0L, durationMs = 25_000L)
        session.start()
        advanceTimeBy(26_000L)
        runCurrent()
        assertEquals(1, saved.size)
        assertTrue("state=${session.state.value}", session.state.value is ImuGaitState.Done)
        assertTrue(session.claimOutcome())
        assertTrue(!session.claimOutcome())
    }

    /** Standing still for the first 6 s, then 2 steps per second of synthetic walking. */
    private fun TestScope.feedGait(source: FakeSensorSource, startAtMs: Long, durationMs: Long) = launch {
        val start = testScheduler.currentTime
        while (testScheduler.currentTime - start < durationMs) {
            val ms = testScheduler.currentTime - start
            val t = ms / 1000.0
            val accel = if (t < 6.0) doubleArrayOf(0.0, 0.0, SyntheticImu.G) else SyntheticImu.walkingAccel(t)
            val ts = base + (startAtMs + ms) * 1_000_000L
            source.emit(MotionSensorType.ACCELEROMETER, ts, accel)
            source.emit(MotionSensorType.GYROSCOPE, ts, doubleArrayOf(0.0, 0.0, 0.0))
            delay(10)
        }
    }
}
