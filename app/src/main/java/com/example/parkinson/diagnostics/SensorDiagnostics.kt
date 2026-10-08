package com.example.parkinson.diagnostics

import android.util.Log
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import java.util.Locale

/**
 * Diagnostic Logcat output for the IMU tests, debuggable builds only (enabled by
 * ParkinsonApplication). Never shown to patients and never affects the analysis.
 *
 * Tag: [TAG] for Hand Stability, [PRONATION_TAG] for Pronation/Supination (chosen by [reset]).
 * Line prefixes:
 *   RATE   once per second per sensor: samples, mean/max interval (ms), last x/y/z
 *   GAP    an interval between two samples of one sensor above [GAP_WARN_MS]
 *   STATE  session state change
 *   RESULT sampling rates, dropouts, quality, metrics and index after analysis
 *   CYCLE  (pronation) one detected movement: time, duration, amplitude, peak velocity
 */
object SensorDiagnostics {

    const val TAG = "HSDiag"
    const val PRONATION_TAG = "PSDiag"
    const val GAP_WARN_MS = 100.0

    @Volatile
    var enabled = false

    @Volatile
    private var tag = TAG

    fun log(message: String) {
        if (enabled) Log.d(tag, message)
    }

    fun f(value: Double, decimals: Int = 3): String = String.format(Locale.US, "%.${decimals}f", value)

    private class Window {
        var startNs = 0L
        var lastNs = 0L
        var count = 0
        var maxIntervalMs = 0.0
    }

    private val lock = Any()
    private val windows = mapOf(MotionSensorType.ACCELEROMETER to Window(), MotionSensorType.GYROSCOPE to Window())

    /** Starts a new session; [tag] selects the Logcat tag for its lines. */
    fun reset(tag: String = TAG) = synchronized(lock) {
        this.tag = tag
        windows.values.forEach { it.startNs = 0L; it.lastNs = 0L; it.count = 0; it.maxIntervalMs = 0.0 }
    }

    fun onSample(sample: MotionSample, recording: Boolean) {
        if (!enabled) return
        synchronized(lock) {
            val w = windows.getValue(sample.type)
            if (w.lastNs != 0L) {
                val intervalMs = (sample.timestampNs - w.lastNs) / 1e6
                if (intervalMs > w.maxIntervalMs) w.maxIntervalMs = intervalMs
                if (intervalMs > GAP_WARN_MS) {
                    log("GAP ${sample.type} intervalMs=${f(intervalMs, 1)} ts=${sample.timestampNs} recording=$recording")
                }
            }
            if (w.startNs == 0L) w.startNs = sample.timestampNs
            w.lastNs = sample.timestampNs
            w.count++
            val spanNs = sample.timestampNs - w.startNs
            if (spanNs >= 1_000_000_000L) {
                val meanMs = spanNs / 1e6 / (w.count - 1).coerceAtLeast(1)
                log(
                    "RATE ${sample.type} ts=${sample.timestampNs} n=${w.count} meanIntervalMs=${f(meanMs, 2)} maxIntervalMs=${f(w.maxIntervalMs, 1)} " +
                        "xyz=${f(sample.x.toDouble())},${f(sample.y.toDouble())},${f(sample.z.toDouble())} " +
                        "unreliable=${sample.unreliable} recording=$recording"
                )
                w.startNs = sample.timestampNs
                w.count = 1
                w.maxIntervalMs = 0.0
            }
        }
    }
}
