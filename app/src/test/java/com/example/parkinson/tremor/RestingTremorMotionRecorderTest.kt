package com.example.parkinson.tremor

import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSampleSink
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.sensors.MotionSensorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recorder states on a SYNTHETIC sensor source (no hardware). */
class RestingTremorMotionRecorderTest {

    private class Source(
        private val accelPresent: Boolean,
        private val registrationOk: Boolean,
        private val gyroPresent: Boolean = true,
    ) : MotionSensorSource {
        var stopCount = 0
        var sink: MotionSampleSink? = null

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
            if (!registrationOk) return false
            this.sink = sink
            // Events delivered during registration are kept: the listener is live once it is requested.
            sink.onSample(MotionSensorType.ACCELEROMETER, 1_000L, 0f, 0f, 9.8f, false)
            return true
        }

        override fun stop() {
            stopCount++
        }
    }

    @Test
    fun missingAccelerometerIsReportedAndNothingIsRegistered() {
        val source = Source(accelPresent = false, registrationOk = true)
        val recorder = RestingTremorMotionRecorder(source, elapsedNs = { 0L })
        assertEquals(MotionStartOutcome.ACCELEROMETER_UNAVAILABLE, recorder.start(10_000))
        assertFalse(recorder.isRunning)
        assertEquals(null, source.sink)
    }

    @Test
    fun failedRegistrationIsReportedAsAFailureNotAsAnEmptyCapture() {
        val source = Source(accelPresent = true, registrationOk = false)
        val recorder = RestingTremorMotionRecorder(source, elapsedNs = { 0L })
        assertEquals(MotionStartOutcome.REGISTRATION_FAILED, recorder.start(10_000))
        assertFalse(recorder.isRunning)
    }

    @Test
    fun eventsDeliveredAtRegistrationAreKeptAndStopReleasesTheListener() {
        val source = Source(accelPresent = true, registrationOk = true)
        val recorder = RestingTremorMotionRecorder(source, elapsedNs = { 500L })
        assertEquals(MotionStartOutcome.STARTED, recorder.start(10_000))
        assertTrue(recorder.isRunning)

        val capture = recorder.stop()
        assertEquals(500L, capture.windowStartNs)
        assertEquals(1, capture.accelerometer.size)
        assertTrue(capture.gyroscope.isEmpty())
        assertEquals(1, source.stopCount)
        assertFalse(recorder.isRunning)
    }

    @Test
    fun discardClearsEverythingAndReleasesTheListener() {
        val source = Source(accelPresent = true, registrationOk = true, gyroPresent = false)
        val recorder = RestingTremorMotionRecorder(source, elapsedNs = { 0L })
        recorder.start(10_000)
        assertEquals(1, recorder.bufferedCount())

        recorder.discard()
        assertEquals(0, recorder.bufferedCount())
        assertFalse(recorder.isRunning)
        assertEquals(1, source.stopCount)
    }
}
