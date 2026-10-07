package com.example.parkinson.tapping

import android.os.SystemClock
import com.example.parkinson.diagnostics.TapDiagnostics
import com.example.parkinson.mediapipe.HandTrackingResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.min

sealed interface SessionState {
    data object Idle : SessionState
    data class Countdown(val secondsLeft: Int) : SessionState
    data class Recording(val secondsLeft: Int) : SessionState
    data object Processing : SessionState
    data class Done(val outcome: TapTestOutcome.Valid) : SessionState
    data class Invalid(
        val reason: SessionInvalidReason,
        /** null when the session was interrupted: incomplete data is never turned into metrics. */
        val metrics: FingerTappingMetrics?
    ) : SessionState
}

val SessionState.isActive: Boolean
    get() = this is SessionState.Countdown ||
        this is SessionState.Recording ||
        this is SessionState.Processing

sealed interface SessionInvalidReason {
    /** Stopped before the recording finished (screen left, app backgrounded, rotation). */
    data object Interrupted : SessionInvalidReason

    /** Recording completed but the scorer rejected it. */
    data class Scoring(val reason: InvalidReason) : SessionInvalidReason
}

/**
 * One Finger Tapping test: IDLE -> COUNTDOWN -> RECORDING -> PROCESSING -> DONE / INVALID.
 *
 * Frames are pushed in through [onTrackingResult], which must be called for every MediaPipe
 * result (HandLandmarkerManager.resultListener), not from the conflated result StateFlow.
 * A frame is passed to the engine only when its timestamp falls inside the recording window,
 * so frames captured during the countdown or after the end are never counted.
 *
 * Threading: [onTrackingResult] runs on the MediaPipe thread; [start], [abort] and [reset]
 * are called from the main thread. The recording window and the engine are guarded by [lock].
 */
class FingerTappingSession(
    private val scope: CoroutineScope,
    /** Must be the clock used for HandTrackingResult.timestampMs. */
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val countdownMs: Long = 3_000L,
    private val recordingMs: Long = 10_000L,
    /** Wait for frames captured before the end that MediaPipe has not delivered yet. */
    private val lateFrameGraceMs: Long = 200L,
    private val tickMs: Long = 100L,
    private val engine: FingerTappingEngine = FingerTappingEngine()
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    /** Live tap count, for display only. */
    val tapCount: StateFlow<Int> = engine.tapCount

    private val lock = Any()

    // Guarded by lock. Frames with acceptFromMs <= timestamp <= acceptUntilMs go to the engine.
    private var acceptFromMs = Long.MAX_VALUE
    private var acceptUntilMs = Long.MIN_VALUE

    // Guarded by lock. Incremented on every start/abort/reset so a stale run cannot publish.
    private var runId = 0

    private var job: Job? = null

    fun onTrackingResult(result: HandTrackingResult) {
        synchronized(lock) {
            val ts = result.timestampMs
            val inWindow = ts in acceptFromMs..acceptUntilMs
            TapDiagnostics.onSessionFrame(result, _state.value.label(), inWindow, clock())
            if (!inWindow) return
            engine.onFrame(result)
        }
    }

    fun start() {
        if (_state.value.isActive) return
        job?.cancel()
        val id = synchronized(lock) {
            closeWindowLocked()
            engine.reset()
            ++runId
        }
        job = scope.launch { run(id) }
    }

    /** Stops an active session. It ends as INVALID(Interrupted), never as a result. */
    fun abort() {
        if (!_state.value.isActive) return
        stop(SessionState.Invalid(SessionInvalidReason.Interrupted, metrics = null))
    }

    /** Back to IDLE, discarding any running session or previous result. */
    fun reset() {
        stop(SessionState.Idle)
    }

    private fun stop(finalState: SessionState) {
        job?.cancel()
        job = null
        synchronized(lock) {
            closeWindowLocked()
            engine.reset()
            runId++
            setStateLocked(finalState)
        }
    }

    private suspend fun run(id: Int) {
        val countdownEnd = clock() + countdownMs
        while (true) {
            val left = countdownEnd - clock()
            if (left <= 0) break
            if (!publish(id, SessionState.Countdown(ceilSeconds(left)))) return
            delay(min(tickMs, left))
        }

        val startMs = clock()
        val endMs = startMs + recordingMs
        synchronized(lock) {
            if (id != runId) return
            engine.start(startMs)
            acceptFromMs = startMs
            acceptUntilMs = Long.MAX_VALUE
        }
        while (true) {
            val left = endMs - clock()
            if (left <= 0) break
            if (!publish(id, SessionState.Recording(ceilSeconds(left)))) return
            delay(min(tickMs, left))
        }

        synchronized(lock) {
            if (id != runId) return
            acceptUntilMs = endMs
            setStateLocked(SessionState.Processing)
        }
        delay(lateFrameGraceMs)

        synchronized(lock) {
            if (id != runId) return
            closeWindowLocked()
            val metrics = engine.finish(endMs)
            val finalState = when (val outcome = FingerTappingScorer.score(metrics)) {
                is TapTestOutcome.Valid -> SessionState.Done(outcome)
                is TapTestOutcome.Invalid ->
                    SessionState.Invalid(SessionInvalidReason.Scoring(outcome.reason), metrics)
            }
            TapDiagnostics.log(
                "RESULT outcome=${finalState.label()} taps=${metrics.tapCount} " +
                    "durationS=${TapDiagnostics.f(metrics.testDurationSeconds, 2)} " +
                    "freqHz=${TapDiagnostics.f(metrics.frequencyHz, 2)} " +
                    "meanIntervalMs=${TapDiagnostics.f(metrics.meanIntervalMs, 1)} " +
                    "intervalCvPct=${TapDiagnostics.f(metrics.intervalVariabilityPercent, 1)} " +
                    "meanAmp=${TapDiagnostics.f(metrics.meanAmplitude)} " +
                    "decrementPct=${TapDiagnostics.f(metrics.amplitudeDecrementPercent, 1)} " +
                    "hesitations=${metrics.hesitationCount} " +
                    "trackingQuality=${TapDiagnostics.f(metrics.trackingQuality, 3)}"
            )
            setStateLocked(finalState)
        }
    }

    private fun setStateLocked(state: SessionState) {
        if (_state.value != state) TapDiagnostics.log("STATE ${state.label()} ts=${clock()}")
        _state.value = state
    }

    private fun SessionState.label(): String = when (this) {
        SessionState.Idle -> "IDLE"
        is SessionState.Countdown -> "COUNTDOWN($secondsLeft)"
        is SessionState.Recording -> "RECORDING($secondsLeft)"
        SessionState.Processing -> "PROCESSING"
        is SessionState.Done -> "DONE"
        is SessionState.Invalid -> when (val r = reason) {
            SessionInvalidReason.Interrupted -> "INVALID(INTERRUPTED)"
            is SessionInvalidReason.Scoring -> "INVALID(${r.reason})"
        }
    }

    private fun publish(id: Int, state: SessionState): Boolean = synchronized(lock) {
        if (id != runId) return false
        setStateLocked(state)
        true
    }

    private fun closeWindowLocked() {
        acceptFromMs = Long.MAX_VALUE
        acceptUntilMs = Long.MIN_VALUE
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()
}
