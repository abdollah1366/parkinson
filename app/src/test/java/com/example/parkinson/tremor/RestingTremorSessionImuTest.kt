package com.example.parkinson.tremor

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSampleSink
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.sensors.MotionSensorType
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Session behaviour with a SYNTHETIC sensor source and virtual time (test input only, never a patient measurement).
 * The camera path is kept healthy with NO_HAND results unless a test says otherwise, so camera-side outcomes are
 * deterministic.
 */
class RestingTremorSessionImuTest {

    private val baseNs = 7_000_000_000L

    /** Accelerometer: gravity on z plus 0.3 m/s^2 at 5 Hz, sampled every [dtMs] from [startNs] for [durationS]. */
    private fun accel(startNs: Long, durationS: Double = 20.0, dtMs: Long = 10L): List<MotionSample> =
        (0 until (durationS * 1000 / dtMs).toInt()).map { i ->
            val t = i * dtMs / 1000.0
            MotionSample(
                MotionSensorType.ACCELEROMETER, startNs + i * dtMs * 1_000_000L,
                0f, 0f, (9.81 + 0.3 * sin(2 * PI * 5.0 * t)).toFloat(),
            )
        }

    private fun gyro(startNs: Long, durationS: Double = 20.0, dtMs: Long = 10L): List<MotionSample> =
        (0 until (durationS * 1000 / dtMs).toInt()).map { i ->
            val t = i * dtMs / 1000.0
            MotionSample(
                MotionSensorType.GYROSCOPE, startNs + i * dtMs * 1_000_000L,
                (0.05 * sin(2 * PI * 5.0 * t)).toFloat(), 0f, 0f,
            )
        }

    /** Fake sensor source: delivers the scripted events synchronously when registered. */
    private inner class FakeMotionSource(
        private val accelPresent: Boolean = true,
        private val gyroPresent: Boolean = true,
        private val nowNs: () -> Long,
        private val accelFor: (Long) -> List<MotionSample> = { accel(it) },
        private val gyroFor: (Long) -> List<MotionSample> = { gyro(it) },
    ) : MotionSensorSource {
        var stopCount = 0
        var startCount = 0

        override fun isAvailable(type: MotionSensorType): Boolean = when (type) {
            MotionSensorType.ACCELEROMETER -> accelPresent
            MotionSensorType.GYROSCOPE -> gyroPresent
        }

        override fun start(samplingPeriodUs: Int, listener: (MotionSample) -> Unit): Boolean = false

        override fun startRaw(
            samplingPeriodUs: Int,
            required: Set<MotionSensorType>,
            optional: Set<MotionSensorType>,
            sink: MotionSampleSink,
        ): Boolean {
            if (MotionSensorType.ACCELEROMETER in required && !accelPresent) return false
            startCount++
            val now = nowNs()
            accelFor(now).forEach { sink.onSample(it.type, it.timestampNs, it.x, it.y, it.z, it.unreliable) }
            if (gyroPresent) gyroFor(now).forEach { sink.onSample(it.type, it.timestampNs, it.x, it.y, it.z, it.unreliable) }
            return true
        }

        override fun stop() {
            stopCount++
        }
    }

    private class Harness(
        val session: RestingTremorSession,
        val source: FakeMotionSource,
        val savedImu: MutableList<RestingTremorImuResult>,
        val recorder: RestingTremorMotionRecorder,
    )

    /** Builds a session on [scope]'s virtual clock and keeps the camera path reporting NO_HAND every 33 ms. */
    private fun TestScope.harness(
        accelPresent: Boolean = true,
        gyroPresent: Boolean = true,
        accelFor: (Long) -> List<MotionSample> = { accel(it) },
        gyroFor: (Long) -> List<MotionSample> = { gyro(it) },
        failImuSave: Boolean = false,
        keepCameraAlive: Boolean = true,
    ): Harness {
        val nowNs: () -> Long = { baseNs + testScheduler.currentTime * 1_000_000L }
        val source = FakeMotionSource(accelPresent, gyroPresent, nowNs, accelFor, gyroFor)
        val recorder = RestingTremorMotionRecorder(source, elapsedNs = nowNs)
        val saved = mutableListOf<RestingTremorImuResult>()
        var ids = 0
        val session = RestingTremorSession(
            scope = this,
            clock = { testScheduler.currentTime },
            wallClock = { 1_700_000_000_000L + testScheduler.currentTime },
            elapsedNs = nowNs,
            newAssessmentId = { "imu-${ids++}" },
            motion = recorder,
            processingDispatcher = UnconfinedTestDispatcher(testScheduler),
            onImuCompleted = { result ->
                if (failImuSave) throw IllegalStateException("disk full")
                saved += result
            },
        )
        if (keepCameraAlive) {
            backgroundScope.launch {
                while (true) {
                    session.onTrackingResult(HandTrackingResult.NoHandDetected(testScheduler.currentTime))
                    delay(33)
                }
            }
        }
        return Harness(session, source, saved, recorder)
    }

    @Test
    fun imuOnlyValidRecordingIsSavedAndReportedAsDoneImu() = runTest {
        val h = harness()
        h.session.start(SelectedHand.RIGHT)
        advanceUntilIdle()

        val state = h.session.state.value
        assertTrue("state was $state", state is RestingTremorState.DoneImu)
        val result = (state as RestingTremorState.DoneImu).result
        assertEquals(1, h.savedImu.size)
        assertEquals(result, h.savedImu.single())
        assertTrue(result.isUsable)
        assertEquals(QualityStatus.VALID, result.qualityStatus)
        assertTrue(result.gyroscopeUsed)
        val m = requireNotNull(result.metrics)
        assertEquals(5.0, requireNotNull(m.dominantFrequencyHz), 0.15)
        assertEquals(0.3 / Math.sqrt(2.0), m.accelRmsMs2, 0.01)
        // The analysis window is the timed recording: 15 s at 100 Hz, inclusive window ends.
        assertEquals(1_501, m.accelSamples)
    }

    @Test
    fun accelerometerOnlyRecordingIsLowQualityAndNeverComputesGyroFeatures() = runTest {
        val h = harness(gyroPresent = false)
        h.session.start(SelectedHand.LEFT)
        advanceUntilIdle()

        val result = (h.session.state.value as RestingTremorState.DoneImu).result
        assertFalse(result.gyroscopePresent)
        assertFalse(result.gyroscopeUsed)
        assertEquals(QualityStatus.LOW_QUALITY, result.qualityStatus)
        assertTrue(ImuTremorQualityIssue.GYROSCOPE_UNUSABLE in result.qualityIssues)
        val m = requireNotNull(result.metrics)
        assertNull(m.gyroRmsRadS)
        assertNull(m.gyroSamples)
        assertEquals(5.0, requireNotNull(m.dominantFrequencyHz), 0.15)
        assertEquals(1, h.savedImu.size)
    }

    @Test
    fun missingAccelerometerSavesNoPhoneMotionRowAndReportsTheCameraOutcome() = runTest {
        val h = harness(accelPresent = false)
        h.session.start(SelectedHand.RIGHT)
        advanceUntilIdle()

        assertTrue(h.savedImu.isEmpty())
        val state = h.session.state.value
        assertTrue("state was $state", state is RestingTremorState.Invalid)
        assertEquals(0, h.source.startCount)
    }

    @Test
    fun insufficientAccelerometerSamplesAreStoredAsInsufficientWithAReason() = runTest {
        // Two seconds of samples in a fifteen-second recording: far too few.
        val h = harness(accelFor = { accel(it, durationS = 2.0) }, gyroFor = { emptyList() })
        h.session.start(SelectedHand.RIGHT)
        advanceUntilIdle()

        assertTrue(h.session.state.value is RestingTremorState.Invalid)
        val stored = h.savedImu.single()
        assertEquals(QualityStatus.INSUFFICIENT_DATA, stored.qualityStatus)
        assertTrue(ImuTremorQualityIssue.TOO_FEW_SAMPLES in stored.qualityIssues)
        assertNull("no feature may be invented from insufficient data", stored.metrics)
        assertFalse(stored.isUsable)
    }

    @Test
    fun invalidAccelerometerValuesAreDroppedBeforeTheGateAndNeverReachTheFeatures() = runTest {
        // Every sample is NaN: nothing usable remains.
        val h = harness(accelFor = { start ->
            (0 until 1_500).map { i ->
                MotionSample(MotionSensorType.ACCELEROMETER, start + i * 10_000_000L, Float.NaN, 0f, 0f)
            }
        }, gyroFor = { emptyList() })
        h.session.start(SelectedHand.RIGHT)
        advanceUntilIdle()

        val stored = h.savedImu.single()
        assertEquals(QualityStatus.INSUFFICIENT_DATA, stored.qualityStatus)
        assertNull(stored.metrics)
    }

    @Test
    fun samplesFromTheCountdownAreNotPartOfTheAnalysisWindow() = runTest {
        // The sensors start before the countdown: the first three seconds must not be analysed.
        val h = harness()
        h.session.start(SelectedHand.RIGHT)
        advanceUntilIdle()

        val m = requireNotNull((h.session.state.value as RestingTremorState.DoneImu).result.metrics)
        assertEquals(1_501, m.accelSamples)
        assertTrue("span ${m.spanMs}", m.spanMs in 14_900.0..15_100.0)
    }

    @Test
    fun cameraFailureDuringTheImuRecordingContinuesWithMotionDataOnly() = runTest {
        val h = harness()
        h.session.start(SelectedHand.RIGHT)
        advanceTimeBy(4_000)
        assertTrue(h.session.state.value is RestingTremorState.Recording)

        h.session.fail(RestingTremorError.CAMERA_FAILURE)
        advanceUntilIdle()

        val state = h.session.state.value
        assertTrue("state was $state", state is RestingTremorState.DoneImu)
        assertEquals(1, h.savedImu.size)
    }

    @Test
    fun cameraFailureWithoutAnyImuCaptureEndsTheRecordingWithAnError() = runTest {
        val h = harness(accelPresent = false)
        h.session.start(SelectedHand.RIGHT)
        advanceTimeBy(4_000)

        h.session.fail(RestingTremorError.CAMERA_FAILURE)
        advanceUntilIdle()

        assertEquals(RestingTremorState.Error(RestingTremorError.CAMERA_FAILURE), h.session.state.value)
        assertTrue(h.savedImu.isEmpty())
    }

    @Test
    fun abortReleasesTheSensorsAndStoresNothing() = runTest {
        val h = harness()
        h.session.start(SelectedHand.RIGHT)
        advanceTimeBy(4_000)

        h.session.abort()
        advanceUntilIdle()

        assertEquals(RestingTremorState.Invalid(RestingTremorInvalidReason.Interrupted), h.session.state.value)
        assertTrue(h.savedImu.isEmpty())
        assertTrue("sensors must be released", h.source.stopCount >= 1)
        assertFalse(h.recorder.isRunning)
        assertEquals(0, h.recorder.bufferedCount())
    }

    @Test
    fun resetDuringTheCountdownReleasesTheSensorsAndKeepsNoBuffer() = runTest {
        val h = harness()
        h.session.start(SelectedHand.RIGHT)
        advanceTimeBy(1_000)

        h.session.reset()
        advanceUntilIdle()

        assertEquals(RestingTremorState.Idle, h.session.state.value)
        assertTrue(h.savedImu.isEmpty())
        assertFalse(h.recorder.isRunning)
        assertEquals(0, h.recorder.bufferedCount())
    }

    @Test
    fun aFailedImuSaveIsAStorageErrorAndNeverASuccess() = runTest {
        val h = harness(failImuSave = true)
        h.session.start(SelectedHand.RIGHT)
        advanceUntilIdle()

        assertEquals(RestingTremorState.Error(RestingTremorError.STORAGE_FAILURE), h.session.state.value)
        assertTrue(h.savedImu.isEmpty())
    }
}
