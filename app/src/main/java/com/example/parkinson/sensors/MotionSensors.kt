package com.example.parkinson.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.util.Log

enum class MotionSensorType { ACCELEROMETER, GYROSCOPE }

/**
 * One raw sensor event in device coordinates.
 * Accelerometer: m/s^2 including gravity. Gyroscope: rad/s.
 * [timestampNs] is the sensor event timestamp. Both sensors share the same clock base, so the
 * two streams are synchronized on it; it must not be compared with other clocks.
 */
data class MotionSample(
    val type: MotionSensorType,
    val timestampNs: Long,
    val x: Float,
    val y: Float,
    val z: Float,
    /** SensorManager reported SENSOR_STATUS_UNRELIABLE (or NO_CONTACT) for this event. */
    val unreliable: Boolean = false
)

/** Allocation-free delivery of one sensor event (called on the sensor thread). */
fun interface MotionSampleSink {
    fun onSample(type: MotionSensorType, timestampNs: Long, x: Float, y: Float, z: Float, unreliable: Boolean)
}

/** Source of accelerometer + gyroscope samples. Implementations deliver on a background thread. */
interface MotionSensorSource {
    fun isAvailable(type: MotionSensorType): Boolean

    /**
     * Starts both sensors. Returns false (and delivers nothing) if a sensor is missing or could not
     * be registered. [samplingPeriodUs] is a request only: the real rate must be measured.
     */
    fun start(samplingPeriodUs: Int, listener: (MotionSample) -> Unit): Boolean

    /**
     * Starts every sensor in [required] (all must register, otherwise returns false and delivers
     * nothing) plus those in [optional] that exist. Delivers events without allocating objects.
     * The default implementation (for simple sources) starts both sensors through [start].
     */
    fun startRaw(
        samplingPeriodUs: Int,
        required: Set<MotionSensorType>,
        optional: Set<MotionSensorType>,
        sink: MotionSampleSink
    ): Boolean = start(samplingPeriodUs) { s -> sink.onSample(s.type, s.timestampNs, s.x, s.y, s.z, s.unreliable) }

    /** Stops delivery. Safe to call repeatedly. */
    fun stop()
}

class AndroidMotionSensorSource(context: Context) : MotionSensorSource {

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    private val lock = Any()
    private var thread: HandlerThread? = null
    private var registered: SensorEventListener? = null

    private fun sensorFor(type: MotionSensorType): Sensor? = sensorManager?.getDefaultSensor(
        when (type) {
            MotionSensorType.ACCELEROMETER -> Sensor.TYPE_ACCELEROMETER
            MotionSensorType.GYROSCOPE -> Sensor.TYPE_GYROSCOPE
        }
    )

    override fun isAvailable(type: MotionSensorType): Boolean = sensorFor(type) != null

    override fun start(samplingPeriodUs: Int, listener: (MotionSample) -> Unit): Boolean =
        startRaw(samplingPeriodUs, MotionSensorType.entries.toSet(), emptySet()) { type, ts, x, y, z, unreliable ->
            listener(MotionSample(type, ts, x, y, z, unreliable))
        }

    override fun startRaw(
        samplingPeriodUs: Int,
        required: Set<MotionSensorType>,
        optional: Set<MotionSensorType>,
        sink: MotionSampleSink
    ): Boolean = synchronized(lock) {
        stopLocked()
        val manager = sensorManager ?: return false
        if (required.any { sensorFor(it) == null }) return false
        val wanted = (required + optional).mapNotNull { type -> sensorFor(type)?.let { type to it } }

        val eventListener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val type = when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> MotionSensorType.ACCELEROMETER
                    Sensor.TYPE_GYROSCOPE -> MotionSensorType.GYROSCOPE
                    else -> return
                }
                val v = event.values
                sink.onSample(type, event.timestamp, v[0], v[1], v[2], event.accuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE)
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }

        // Dedicated thread: sensor callbacks never wait for the UI thread.
        val handlerThread = HandlerThread("MotionSensors").also { it.start() }
        val handler = Handler(handlerThread.looper)
        thread = handlerThread
        registered = eventListener
        // maxReportLatencyUs = 0: no batching, events arrive as they are measured.
        // Requested rate stays <= 200 Hz, so HIGH_SAMPLING_RATE_SENSORS is not needed (Android 12+).
        for ((type, sensor) in wanted) {
            val ok = manager.registerListener(eventListener, sensor, samplingPeriodUs, 0, handler)
            if (!ok && type in required) {
                Log.w(TAG, "registerListener failed for required $type")
                stopLocked()
                return false
            }
            if (!ok) Log.w(TAG, "registerListener failed for optional $type")
        }
        true
    }

    override fun stop() = synchronized(lock) { stopLocked() }

    private fun stopLocked() {
        registered?.let { sensorManager?.unregisterListener(it) }
        registered = null
        thread?.quitSafely()
        thread = null
    }

    private companion object {
        const val TAG = "MotionSensors"
    }
}
