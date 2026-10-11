package com.example.parkinson.tremor

import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.sensors.MotionSensorType

/** Result of starting the phone-motion capture for one recording. */
enum class MotionStartOutcome {
    STARTED,

    /** No accelerometer on the device, or it could not be probed: no accelerometer stream exists. */
    ACCELEROMETER_UNAVAILABLE,

    /** The accelerometer exists but its listener could not be registered. */
    REGISTRATION_FAILED,
}

/** The samples captured between [MotionCapture.windowStartNs] and the moment it was stopped. */
data class MotionCapture(
    /** Monotonic time (ns, same base as the sensor event timestamps) at which capture was requested. */
    val windowStartNs: Long,
    val accelerometer: List<MotionSample>,
    /** Empty when the gyroscope is missing, not registered, or produced no events. */
    val gyroscope: List<MotionSample>,
)

/**
 * Captures the phone's accelerometer (required) and gyroscope (optional) for one resting-hand recording.
 *
 * Independent of the camera: it only starts and stops the sensors and keeps the events. The accelerometer is
 * required for the recording to produce any phone-motion data. The gyroscope is requested as optional, so a
 * missing gyroscope never stops the accelerometer. Whether gyroscope events arrived is judged from the captured
 * samples themselves, because the sensor source does not report which optional sensor registered.
 *
 * [elapsedNs] must read the same clock as the sensor event timestamps (elapsed realtime, in ns).
 * Thread-safety: the sensor thread writes under [lock]; [stop] takes the buffers under the same lock.
 */
class RestingTremorMotionRecorder(
    private val source: MotionSensorSource,
    private val elapsedNs: () -> Long,
) {
    private val lock = Any()
    private val accel = ArrayList<MotionSample>(4096)
    private val gyro = ArrayList<MotionSample>(4096)
    private var running = false
    private var windowStartNs = 0L

    private val onEvent = { type: MotionSensorType, ts: Long, x: Float, y: Float, z: Float, unreliable: Boolean ->
        synchronized(lock) {
            if (running) {
                val s = MotionSample(type, ts, x, y, z, unreliable)
                when (type) {
                    MotionSensorType.ACCELEROMETER -> accel += s
                    MotionSensorType.GYROSCOPE -> gyro += s
                }
            }
        }
    }

    /** Whether the device has an accelerometer. A probe that throws counts as missing. */
    fun accelerometerAvailable(): Boolean = runCatching { source.isAvailable(MotionSensorType.ACCELEROMETER) }.getOrDefault(false)

    /** Whether the device has a gyroscope. A probe that throws counts as missing. */
    fun gyroscopeAvailable(): Boolean = runCatching { source.isAvailable(MotionSensorType.GYROSCOPE) }.getOrDefault(false)

    /**
     * Clears any previous capture and starts the sensors. Called before the countdown, so the sensors are already
     * recording when the timed recording begins. Events are accepted from the moment the listener is requested.
     */
    fun start(samplingPeriodUs: Int): MotionStartOutcome {
        synchronized(lock) {
            accel.clear()
            gyro.clear()
            running = false
        }
        if (!accelerometerAvailable()) return MotionStartOutcome.ACCELEROMETER_UNAVAILABLE
        synchronized(lock) {
            windowStartNs = elapsedNs()
            running = true
        }
        val ok = runCatching {
            source.startRaw(
                samplingPeriodUs = samplingPeriodUs,
                required = setOf(MotionSensorType.ACCELEROMETER),
                optional = setOf(MotionSensorType.GYROSCOPE),
                sink = { type, ts, x, y, z, unreliable -> onEvent(type, ts, x, y, z, unreliable) },
            )
        }.getOrDefault(false)
        if (!ok) {
            synchronized(lock) { running = false }
            return MotionStartOutcome.REGISTRATION_FAILED
        }
        return MotionStartOutcome.STARTED
    }

    /** Marks the start of the timed recording: samples before this instant are not part of the analysis window. */
    fun markRecordingStart() {
        synchronized(lock) { windowStartNs = elapsedNs() }
    }

    /** Releases the sensor listeners and returns what was captured. Safe to call repeatedly. */
    fun stop(): MotionCapture {
        runCatching { source.stop() }
        return synchronized(lock) {
            running = false
            MotionCapture(windowStartNs, accel.toList(), gyro.toList())
        }
    }

    /** Stops the sensors and discards the captured events. Used on cancel, abort and reset. */
    fun discard() {
        runCatching { source.stop() }
        synchronized(lock) {
            running = false
            accel.clear()
            gyro.clear()
        }
    }

    val isRunning: Boolean get() = synchronized(lock) { running }

    /** Count of events currently buffered (diagnostics and tests). */
    fun bufferedCount(): Int = synchronized(lock) { accel.size + gyro.size }
}
