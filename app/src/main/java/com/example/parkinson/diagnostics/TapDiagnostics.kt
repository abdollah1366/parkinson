package com.example.parkinson.diagnostics

import android.util.Log
import com.example.parkinson.mediapipe.HandLandmark
import com.example.parkinson.mediapipe.HandLandmarkIndex
import com.example.parkinson.mediapipe.HandTrackingResult
import java.util.Locale
import kotlin.math.hypot

/**
 * Temporary diagnostic logging for real-device validation (Phase 5A).
 * Everything goes to one Logcat tag: [TAG]. Read-only: nothing here affects detection or scoring.
 *
 * Line prefixes (key=value pairs, timestamps are SystemClock.uptimeMillis()):
 *   FPS    once per second: frames sent to MediaPipe / results received
 *   HAND   raw MediaPipe handedness for every single-hand result
 *   FRAME  every result seen by the session (state, timestamp, gap, thumb/index, distance)
 *   GAP    a gap between consecutive results larger than [GAP_WARN_MS]
 *   STATE  session state change
 *   ENGINE engine start/finish and per-frame smoothed value while recording
 *   TAP    tap detected by the engine
 *   RESULT session outcome and metrics
 */
object TapDiagnostics {

    const val TAG = "FTDiag"
    const val GAP_WARN_MS = 100L

    /** Off by default; ParkinsonApplication enables it only in debuggable builds. */
    @Volatile
    var enabled = false

    fun log(message: String) {
        if (enabled) Log.d(TAG, message)
    }

    fun f(value: Float, decimals: Int = 4): String = String.format(Locale.US, "%.${decimals}f", value)

    // --- FPS -------------------------------------------------------------------------------

    private val fpsLock = Any()
    private var fpsWindowStartMs = 0L
    private var framesAnalyzed = 0
    private var resultsReceived = 0
    private var latencySumMs = 0L

    /** A camera frame was handed to MediaPipe. */
    fun onFrameAnalyzed(nowMs: Long) {
        if (!enabled) return
        synchronized(fpsLock) {
            framesAnalyzed++
            flushFpsLocked(nowMs)
        }
    }

    /** MediaPipe returned a result for a frame sent at [frameTimestampMs]. */
    fun onResultReceived(nowMs: Long, frameTimestampMs: Long) {
        if (!enabled) return
        synchronized(fpsLock) {
            resultsReceived++
            latencySumMs += nowMs - frameTimestampMs
            flushFpsLocked(nowMs)
        }
    }

    private fun flushFpsLocked(nowMs: Long) {
        if (fpsWindowStartMs == 0L) {
            fpsWindowStartMs = nowMs
            return
        }
        val elapsed = nowMs - fpsWindowStartMs
        if (elapsed < 1000L) return
        val seconds = elapsed / 1000f
        val avgLatency = if (resultsReceived > 0) latencySumMs / resultsReceived else -1L
        log(
            "FPS analyzed=${f(framesAnalyzed / seconds, 1)} results=${f(resultsReceived / seconds, 1)} " +
                "avgLatencyMs=$avgLatency windowMs=$elapsed"
        )
        fpsWindowStartMs = nowMs
        framesAnalyzed = 0
        resultsReceived = 0
        latencySumMs = 0L
    }

    // --- FRAME / GAP -----------------------------------------------------------------------

    // Only touched from the MediaPipe result thread.
    private var lastResultTsMs = -1L

    fun onSessionFrame(result: HandTrackingResult, stateName: String, inWindow: Boolean, nowMs: Long) {
        if (!enabled) return
        val ts = result.timestampMs
        val dt = if (lastResultTsMs >= 0) ts - lastResultTsMs else -1L
        lastResultTsMs = ts
        if (dt > GAP_WARN_MS) log("GAP ts=$ts gapMs=$dt state=$stateName")

        val base = "FRAME ts=$ts dtMs=$dt latMs=${nowMs - ts} state=$stateName inWindow=$inWindow " +
            "type=${result::class.simpleName}"
        val hand = result as? HandTrackingResult.HandDetected
        if (hand == null || hand.landmarks.size < HandLandmarkIndex.COUNT) {
            log(base)
            return
        }

        val lm = hand.landmarks
        val w = hand.imageWidth.toFloat()
        val h = hand.imageHeight.toFloat()
        fun dist(a: HandLandmark, b: HandLandmark) = hypot((a.x - b.x) * w, (a.y - b.y) * h)
        val thumb = lm[HandLandmarkIndex.THUMB_TIP]
        val index = lm[HandLandmarkIndex.INDEX_FINGER_TIP]
        val distPx = dist(thumb, index)
        val handPx = dist(lm[HandLandmarkIndex.WRIST], lm[HandLandmarkIndex.MIDDLE_FINGER_MCP])
        val ratio = if (handPx >= 1e-3f) distPx / handPx else Float.NaN

        log(
            "$base side=${hand.handSide} conf=${f(hand.confidence, 3)} img=${hand.imageWidth}x${hand.imageHeight} " +
                "thumb=(${f(thumb.x)},${f(thumb.y)},${f(thumb.z)}) index=(${f(index.x)},${f(index.y)},${f(index.z)}) " +
                "distPx=${f(distPx, 1)} handPx=${f(handPx, 1)} ratio=${f(ratio)}"
        )
    }
}
