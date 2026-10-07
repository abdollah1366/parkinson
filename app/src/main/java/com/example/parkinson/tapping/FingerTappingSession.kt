package com.example.parkinson.tapping

import android.os.SystemClock
import com.example.parkinson.diagnostics.TapDiagnostics
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.detection.LiveTapCounter
import com.example.parkinson.tapping.quality.QualityReport
import com.example.parkinson.tapping.raw.TapFrame
import com.example.parkinson.tapping.raw.TapFrameExtractor
import com.example.parkinson.tapping.result.FingerTappingAssessment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.min

sealed interface SessionState {
    data object Idle : SessionState
    data class Countdown(val secondsLeft: Int) : SessionState
    data class Recording(val secondsLeft: Int) : SessionState
    data object Processing : SessionState

    /** A usable result (quality VALID or LOW_QUALITY), already saved. */
    data class Done(val assessment: FingerTappingAssessment) : SessionState

    /** No result: the recording was interrupted or its quality was not sufficient. */
    data class Invalid(val reason: SessionInvalidReason) : SessionState

    /** No result: a technical failure (camera, tracking, storage). */
    data class Error(val error: SessionError) : SessionState
}

val SessionState.isActive: Boolean
    get() = this is SessionState.Countdown ||
        this is SessionState.Recording ||
        this is SessionState.Processing

sealed interface SessionInvalidReason {
    /** Stopped before the recording finished (screen left, app backgrounded, rotation, lock). */
    data object Interrupted : SessionInvalidReason

    /** Recording finished but QUALITY CONTROL rejected it (INVALID or INSUFFICIENT_DATA). */
    data class QualityRejected(val report: QualityReport) : SessionInvalidReason
}

enum class SessionError {
    CAMERA_FAILURE,

    /** MediaPipe kept failing (model missing, initialization or detection errors). */
    TRACKING_FAILURE,

    /** No frames arrived for too long: the camera pipeline stalled. */
    FRAME_STARVATION,
    STORAGE_FAILURE,
    UNEXPECTED
}

/**
 * One Finger Tapping test: IDLE -> COUNTDOWN -> RECORDING -> PROCESSING -> DONE / INVALID / ERROR.
 *
 * Data path: every MediaPipe result is pushed synchronously into [onTrackingResult]
 * (HandLandmarkerManager.resultListener, not the conflated StateFlow). Results whose camera
 * timestamp lies inside the recording window are stored as raw [TapFrame]s; the live counter is
 * updated for display. After the recording, [FingerTappingAnalyzer] processes the complete
 * recording. A session that does not complete normally never produces a result.
 *
 * Threading: [onTrackingResult] runs on the MediaPipe thread; [start], [abort], [fail] and
 * [reset] run on the main thread. Frames, window and live counter are guarded by [lock], and a
 * run id prevents a stopped run from publishing anything.
 */
class FingerTappingSession(
    private val scope: CoroutineScope,
    /** Must be the clock used for HandTrackingResult.timestampMs. */
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newAssessmentId: () -> String = { UUID.randomUUID().toString() },
    private val countdownMs: Long = 3_000L,
    private val recordingMs: Long = 10_000L,
    /** Wait for frames captured before the end that MediaPipe has not delivered yet. */
    private val lateFrameGraceMs: Long = 200L,
    private val tickMs: Long = 100L,
    /** No result at all for this long during COUNTDOWN/RECORDING = camera pipeline stalled. */
    private val frameStarvationMs: Long = 2_000L,
    /** This many MediaPipe errors in a row = tracking failure. */
    private val maxConsecutiveErrors: Int = 5,
    private val analyzer: FingerTappingAnalyzer = FingerTappingAnalyzer(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Persists a usable result before DONE is published. */
    private val onCompleted: suspend (FingerTappingAssessment) -> Unit = {}
) {

    private val _state = MutableStateFlow<SessionState>(SessionState.Idle)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _liveTapCount = MutableStateFlow(0)

    /** Approximate tap count during RECORDING. Display only; the result is computed offline. */
    val liveTapCount: StateFlow<Int> = _liveTapCount.asStateFlow()

    private val lock = Any()

    // Guarded by lock.
    private var acceptFromMs = Long.MAX_VALUE
    private var acceptUntilMs = Long.MIN_VALUE
    private val frames = ArrayList<TapFrame>(1024)
    private val liveCounter = LiveTapCounter()
    private var runId = 0
    private var lastFrameArrivalMs = 0L
    private var consecutiveErrors = 0

    private var job: Job? = null

    fun onTrackingResult(result: HandTrackingResult) {
        synchronized(lock) {
            lastFrameArrivalMs = clock()
            consecutiveErrors = if (result is HandTrackingResult.Error) consecutiveErrors + 1 else 0

            val ts = result.timestampMs
            val inWindow = ts in acceptFromMs..acceptUntilMs
            TapDiagnostics.onSessionFrame(result, _state.value.label(), inWindow, lastFrameArrivalMs)
            if (!inWindow) return

            val frame = TapFrameExtractor.extract(frames.size, result)
            frames += frame
            _liveTapCount.value = liveCounter.onFrame(frame)
        }
    }

    fun start(hand: SelectedHand) {
        if (_state.value.isActive) return
        job?.cancel()
        val id = synchronized(lock) {
            clearRecordingLocked()
            lastFrameArrivalMs = clock()
            consecutiveErrors = 0
            ++runId
        }
        job = scope.launch { run(id, hand) }
    }

    /** Stops an active session. It ends as INVALID(Interrupted), never as a result. */
    fun abort() {
        if (!_state.value.isActive) return
        stop(SessionState.Invalid(SessionInvalidReason.Interrupted))
    }

    /** Stops an active session because of a technical failure reported from outside (camera). */
    fun fail(error: SessionError) {
        if (!_state.value.isActive) return
        stop(SessionState.Error(error))
    }

    /** Back to IDLE, discarding any running session or previous outcome. */
    fun reset() {
        stop(SessionState.Idle)
    }

    private fun stop(finalState: SessionState) {
        job?.cancel()
        job = null
        synchronized(lock) {
            clearRecordingLocked()
            runId++
            setStateLocked(finalState)
        }
    }

    private suspend fun run(id: Int, hand: SelectedHand) {
        try {
            if (!runTimed(id, countdownMs) { SessionState.Countdown(it) }) return

            val startMs = clock()
            val endMs = startMs + recordingMs
            synchronized(lock) {
                if (id != runId) return
                clearRecordingLocked()
                acceptFromMs = startMs
                acceptUntilMs = Long.MAX_VALUE
            }
            if (!runTimed(id, recordingMs) { SessionState.Recording(it) }) return

            synchronized(lock) {
                if (id != runId) return
                acceptUntilMs = endMs
                setStateLocked(SessionState.Processing)
            }
            delay(lateFrameGraceMs)

            val recording = synchronized(lock) {
                if (id != runId) return
                acceptFromMs = Long.MAX_VALUE
                acceptUntilMs = Long.MIN_VALUE
                TapRecording(frames.toList(), startMs, endMs, recordingMs, hand)
            }

            val analysis = withContext(processingDispatcher) { analyzer.analyze(recording) }
            logAnalysis(analysis)

            if (!analysis.quality.isUsable) {
                publish(id, SessionState.Invalid(SessionInvalidReason.QualityRejected(analysis.quality)))
                return
            }

            val assessment = FingerTappingAnalyzer.toAssessment(analysis, newAssessmentId(), wallClock(), hand)
            try {
                onCompleted(assessment)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                TapDiagnostics.log("ERROR storage ${e::class.simpleName}")
                publish(id, SessionState.Error(SessionError.STORAGE_FAILURE))
                return
            }
            publish(id, SessionState.Done(assessment))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never crash on an analysis bug; report it and produce no result.
            TapDiagnostics.log("ERROR unexpected ${e::class.simpleName}")
            publish(id, SessionState.Error(SessionError.UNEXPECTED))
        }
    }

    /** Ticks [durationMs], publishing a state each tick. Returns false if the run must stop. */
    private suspend fun runTimed(id: Int, durationMs: Long, stateFor: (secondsLeft: Int) -> SessionState): Boolean {
        val end = clock() + durationMs
        while (true) {
            val left = end - clock()
            if (left <= 0) return true
            healthError()?.let { error ->
                publish(id, SessionState.Error(error))
                return false
            }
            if (!publish(id, stateFor(ceilSeconds(left)))) return false
            delay(min(tickMs, left))
        }
    }

    private fun healthError(): SessionError? = synchronized(lock) {
        when {
            consecutiveErrors >= maxConsecutiveErrors -> SessionError.TRACKING_FAILURE
            clock() - lastFrameArrivalMs > frameStarvationMs -> SessionError.FRAME_STARVATION
            else -> null
        }
    }

    private fun publish(id: Int, state: SessionState): Boolean = synchronized(lock) {
        if (id != runId) return false
        if (state !is SessionState.Countdown && state !is SessionState.Recording) {
            // Terminal or processing state of this run: stop accepting frames.
            acceptFromMs = Long.MAX_VALUE
            acceptUntilMs = Long.MIN_VALUE
        }
        setStateLocked(state)
        true
    }

    private fun clearRecordingLocked() {
        acceptFromMs = Long.MAX_VALUE
        acceptUntilMs = Long.MIN_VALUE
        frames.clear()
        liveCounter.reset()
        _liveTapCount.value = 0
    }

    private fun setStateLocked(state: SessionState) {
        if (_state.value != state) TapDiagnostics.log("STATE ${state.label()} ts=${clock()}")
        _state.value = state
    }

    private fun logAnalysis(analysis: FingerTappingAnalysis) {
        if (!TapDiagnostics.enabled) return
        analysis.detection.events.forEach { e ->
            TapDiagnostics.log(
                "TAP n=${e.index + 1} ts=${e.timestampMs} startMs=${e.startMs} peakMs=${e.peakMs} " +
                    "amp=${TapDiagnostics.f(e.amplitude.toFloat())} conf=${TapDiagnostics.f(e.confidence.toFloat(), 2)}"
            )
        }
        val d = analysis.detection
        val m = analysis.metrics
        val q = analysis.quality
        TapDiagnostics.log(
            "RESULT taps=${m.tapCount} rejected(debounce=${d.rejectedDebounce} short=${d.rejectedTooShort} " +
                "small=${d.rejectedTooSmall} dropout=${d.discardedByDropout} inactive=${d.discardedInactive}) " +
                "incompleteLast=${d.incompleteFinalCycle} frames=${m.frames.totalFrames} valid=${m.frames.validFrames} " +
                "fps=${TapDiagnostics.f(m.frames.fps.toFloat(), 1)} dropouts=${m.frames.dropoutCount} " +
                "dropoutMs=${m.frames.dropoutTotalMs} noise=${TapDiagnostics.f(analysis.signal.noiseSigma.toFloat())} " +
                "floor=${TapDiagnostics.f(analysis.signal.movementFloor.toFloat())} " +
                "quality=${q.status} issues=${q.issues} score=${analysis.score?.total}"
        )
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

    private fun SessionState.label(): String = when (this) {
        SessionState.Idle -> "IDLE"
        is SessionState.Countdown -> "COUNTDOWN($secondsLeft)"
        is SessionState.Recording -> "RECORDING($secondsLeft)"
        SessionState.Processing -> "PROCESSING"
        is SessionState.Done -> "DONE"
        is SessionState.Invalid -> when (val r = reason) {
            SessionInvalidReason.Interrupted -> "INVALID(INTERRUPTED)"
            is SessionInvalidReason.QualityRejected -> "INVALID(${r.report.status})"
        }
        is SessionState.Error -> "ERROR($error)"
    }
}
