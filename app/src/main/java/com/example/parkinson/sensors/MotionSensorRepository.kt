package com.example.parkinson.sensors

/** Which of the required inertial sensors the device has. */
data class SensorAvailability(
    val accelerometer: Boolean,
    val gyroscope: Boolean,
) {
    val ready: Boolean get() = accelerometer && gyroscope
}

/**
 * Owns one recording of the accelerometer and gyroscope for an IMU assessment: availability check, a single
 * listener registration (a second [start] while running is ignored), an in-memory buffer of raw events, and a
 * [stop] that always releases the listener. Pausing an assessment is [stop]; resuming is [start]. The sensor
 * timestamps of the buffered events keep the real time gap, so a pause shows up as a gap in the data, never as a
 * silent join.
 *
 * Thread-safety: the sensor thread writes under [lock]; readers take [snapshot] under the same lock.
 */
class MotionSensorRepository(private val source: MotionSensorSource) {

    private val lock = Any()
    private val buffer = MotionSampleBuffer(initialCapacity = 8192)
    private var running = false

    private val sink = MotionSampleSink { type, ts, x, y, z, unreliable ->
        synchronized(lock) {
            if (running) buffer.add(type, ts, x, y, z, unreliable)
        }
    }

    /** Availability of the two sensors this repository needs. A failing probe counts as missing. */
    fun availability(): SensorAvailability = SensorAvailability(
        accelerometer = runCatching { source.isAvailable(MotionSensorType.ACCELEROMETER) }.getOrDefault(false),
        gyroscope = runCatching { source.isAvailable(MotionSensorType.GYROSCOPE) }.getOrDefault(false),
    )

    /**
     * Starts recording both sensors. Returns false, with nothing registered, when either is missing or cannot be
     * registered. Idempotent: calling it while running does not register a second listener.
     */
    fun start(samplingPeriodUs: Int): Boolean = synchronized(lock) {
        if (running) return true
        if (!availability().ready) return false
        val ok = source.startRaw(
            samplingPeriodUs,
            required = setOf(MotionSensorType.ACCELEROMETER, MotionSensorType.GYROSCOPE),
            optional = emptySet(),
            sink = sink,
        )
        running = ok
        ok
    }

    /** Stops delivery and releases the listener. Safe to call repeatedly. */
    fun stop() {
        synchronized(lock) {
            if (!running) return
            running = false
            runCatching { source.stop() }
        }
    }

    val isRunning: Boolean get() = synchronized(lock) { running }

    /** Copy of every buffered event so far, in arrival order. */
    fun snapshot(): List<MotionSample> = synchronized(lock) { buffer.toSamples() }

    /** Timestamp (ns) of the newest buffered event, or null when nothing has been received. */
    fun lastTimestampNs(): Long? = synchronized(lock) { buffer.lastTimestampNs() }

    /** Discards the buffer; the listener state is unchanged. */
    fun clear() = synchronized(lock) { buffer.clear() }
}
