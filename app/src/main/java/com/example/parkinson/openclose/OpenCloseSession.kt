package com.example.parkinson.openclose

import android.os.SystemClock
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.model.SelectedHand
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
import kotlin.math.max
import kotlin.math.min

sealed interface OpenCloseState {
    data object Idle : OpenCloseState

    /** Preparation: the countdown before the measurement starts. Frames are not recorded yet. */
    data class Countdown(val secondsLeft: Int) : OpenCloseState
    data class Recording(val secondsLeft: Int) : OpenCloseState
    data object Processing : OpenCloseState

    /** A usable result (quality VALID or LOW_QUALITY), already saved. */
    data class Done(val result: HandOpenCloseResult) : OpenCloseState

    /** No result: interrupted, or the quality control rejected the recording. */
    data class Invalid(val reason: OpenCloseInvalidReason) : OpenCloseState

    /** No result: a technical failure (camera, tracking, storage). */
    data class Error(val error: OpenCloseError) : OpenCloseState
}

val OpenCloseState.isActive: Boolean
    get() = this is OpenCloseState.Countdown || this is OpenCloseState.Recording || this is OpenCloseState.Processing

sealed interface OpenCloseInvalidReason {
    /** Stopped before the recording finished (screen left, app backgrounded, rotation). */
    data object Interrupted : OpenCloseInvalidReason

    /** The recording finished but quality control rejected it. */
    data class QualityRejected(val report: OpenCloseQualityReport) : OpenCloseInvalidReason
}

enum class OpenCloseError {
    CAMERA_FAILURE,

    /** MediaPipe kept failing (model missing, initialization or detection errors). */
    TRACKING_FAILURE,

    /** No results arrived for too long: the camera pipeline stalled. */
    FRAME_STARVATION,
    STORAGE_FAILURE,
    UNEXPECTED
}

/** Live figures during RECORDING, from the frames received so far (same engine as the final result). */
data class OpenCloseLiveStats(
    val framesAnalyzed: Int = 0,
    val validLandmarkFrames: Int = 0,
    val cycles: Int = 0,
    val rejectedCandidates: Int = 0
)

/**
 * One Hand Opening/Closing test: IDLE -> COUNTDOWN (preparation) -> RECORDING -> PROCESSING ->
 * DONE / INVALID / ERROR. A session that does not complete normally never produces a result.
 *
 * Every MediaPipe result is pushed into [onTrackingResult] (the synchronous result listener). Results
 * whose timestamp lies inside the recording window are kept as raw frames; the analysis runs once,
 * on the complete recording. Threading: [onTrackingResult] runs on the MediaPipe thread; the other
 * functions on the main thread. Shared state is guarded by [lock]; a run id stops a stale run from
 * publishing anything.
 */
class HandOpenCloseSession(
    private val scope: CoroutineScope,
    /** Must be the clock used for HandTrackingResult.timestampMs. */
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newAssessmentId: () -> String = { UUID.randomUUID().toString() },
    private val config: OpenCloseSessionConfig = OpenCloseSessionConfig(),
    private val engine: HandOpenCloseEngine = HandOpenCloseEngine(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Persists a usable result before DONE is published. */
    private val onCompleted: suspend (HandOpenCloseResult) -> Unit = {}
) {

    private val _state = MutableStateFlow<OpenCloseState>(OpenCloseState.Idle)
    val state: StateFlow<OpenCloseState> = _state.asStateFlow()

    private val _liveCycles = MutableStateFlow(0)

    /** Completed cycles so far during RECORDING (the final figure comes from the complete analysis). */
    val liveCycles: StateFlow<Int> = _liveCycles.asStateFlow()

    private val _liveStats = MutableStateFlow(OpenCloseLiveStats())
    val liveStats: StateFlow<OpenCloseLiveStats> = _liveStats.asStateFlow()

    private val lock = Any()

    // Guarded by lock.
    private var acceptFromMs = Long.MAX_VALUE
    private var acceptUntilMs = Long.MIN_VALUE
    private val frames = ArrayList<OpenCloseFrame>(1024)
    private var runId = 0
    private var lastResultArrivalMs = 0L
    private var consecutiveErrors = 0
    private var lastLiveUpdateMs: Long? = null

    private var job: Job? = null

    fun onTrackingResult(result: HandTrackingResult) {
        synchronized(lock) {
            lastResultArrivalMs = clock()
            consecutiveErrors = if (result is HandTrackingResult.Error) consecutiveErrors + 1 else 0
            val ts = result.timestampMs
            if (ts !in acceptFromMs..acceptUntilMs) return
            frames += OpenCloseFrameExtractor.extract(frames.size, result)
        }
    }

    fun start(hand: SelectedHand) {
        if (_state.value.isActive) return
        job?.cancel()
        val id = synchronized(lock) {
            clearRecordingLocked()
            lastResultArrivalMs = clock()
            consecutiveErrors = 0
            lastLiveUpdateMs = null
            ++runId
        }
        job = scope.launch { run(id, hand) }
    }

    /** Stops an active session. It ends as INVALID(Interrupted), never as a result. */
    fun abort() {
        if (!_state.value.isActive) return
        stop(OpenCloseState.Invalid(OpenCloseInvalidReason.Interrupted))
    }

    /** Stops an active session because of a technical failure reported from outside (camera). */
    fun fail(error: OpenCloseError) {
        if (!_state.value.isActive) return
        stop(OpenCloseState.Error(error))
    }

    /** Back to IDLE, discarding any running session or previous outcome. */
    fun reset() {
        stop(OpenCloseState.Idle)
    }

    private fun stop(finalState: OpenCloseState) {
        job?.cancel()
        job = null
        synchronized(lock) {
            clearRecordingLocked()
            runId++
            _state.value = finalState
        }
    }

    private suspend fun run(id: Int, hand: SelectedHand) {
        try {
            if (!runTimed(id, config.countdownMs) { OpenCloseState.Countdown(it) }) return

            val startMs = clock()
            val endMs = startMs + config.recordingMs
            synchronized(lock) {
                if (id != runId) return
                clearRecordingLocked()
                acceptFromMs = startMs
                acceptUntilMs = Long.MAX_VALUE
            }
            if (!runTimed(id, config.recordingMs, onTick = { updateLive(id, startMs) }) { OpenCloseState.Recording(it) }) return

            synchronized(lock) {
                if (id != runId) return
                // Frames captured before the end that MediaPipe has not delivered yet are still accepted.
                acceptUntilMs = endMs
                _state.value = OpenCloseState.Processing
            }
            delay(config.lateFrameGraceMs)

            val recording = synchronized(lock) {
                if (id != runId) return
                acceptFromMs = Long.MAX_VALUE
                acceptUntilMs = Long.MIN_VALUE
                OpenCloseRecording(frames.toList(), startMs, endMs, config.recordingMs, hand)
            }

            val analysis = withContext(processingDispatcher) { engine.analyze(recording) }
            if (!analysis.quality.isUsable) {
                publish(id, OpenCloseState.Invalid(OpenCloseInvalidReason.QualityRejected(analysis.quality)))
                return
            }

            val result = HandOpenCloseResult.from(analysis, newAssessmentId(), wallClock(), hand, config.recordingMs)
            try {
                onCompleted(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(id, OpenCloseState.Error(OpenCloseError.STORAGE_FAILURE))
                return
            }
            publish(id, OpenCloseState.Done(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never crash on an analysis bug; report it and produce no result.
            publish(id, OpenCloseState.Error(OpenCloseError.UNEXPECTED))
        }
    }

    /** Ticks [durationMs], publishing a state each tick. Returns false if the run must stop. */
    private suspend fun runTimed(
        id: Int,
        durationMs: Long,
        onTick: suspend () -> Unit = {},
        stateFor: (secondsLeft: Int) -> OpenCloseState
    ): Boolean {
        val end = clock() + durationMs
        while (true) {
            val left = end - clock()
            if (left <= 0) return true
            healthError()?.let { error ->
                publish(id, OpenCloseState.Error(error))
                return false
            }
            if (!publish(id, stateFor(ceilSeconds(left)))) return false
            onTick()
            delay(min(config.tickMs, left))
        }
    }

    /** Live cycle count from the frames received so far. Throttled; runs off the main thread. */
    private suspend fun updateLive(id: Int, startMs: Long) {
        val now = clock()
        val last = lastLiveUpdateMs
        if (last != null && now - last < config.liveUpdateIntervalMs) return
        lastLiveUpdateMs = now
        val snapshot = synchronized(lock) { if (id != runId) return else frames.toList() }
        val cycles = withContext(processingDispatcher) {
            engine.countCycles(snapshot, startMs, max(now, startMs + 1))
        }
        val inWindow = snapshot.count { it.timestampMs in startMs..now }
        val valid = snapshot.count { it.isValid && it.timestampMs in startMs..now }
        synchronized(lock) {
            if (id != runId) return
            _liveCycles.value = cycles.size
            _liveStats.value = OpenCloseLiveStats(framesAnalyzed = inWindow, validLandmarkFrames = valid, cycles = cycles.size)
        }
    }

    private fun healthError(): OpenCloseError? = synchronized(lock) {
        when {
            consecutiveErrors >= config.maxConsecutiveErrors -> OpenCloseError.TRACKING_FAILURE
            clock() - lastResultArrivalMs > config.frameStarvationMs -> OpenCloseError.FRAME_STARVATION
            else -> null
        }
    }

    private fun publish(id: Int, state: OpenCloseState): Boolean = synchronized(lock) {
        if (id != runId) return false
        if (state !is OpenCloseState.Countdown && state !is OpenCloseState.Recording) {
            // Terminal or processing state of this run: stop accepting frames.
            acceptFromMs = Long.MAX_VALUE
            acceptUntilMs = Long.MIN_VALUE
        }
        _state.value = state
        true
    }

    private fun clearRecordingLocked() {
        acceptFromMs = Long.MAX_VALUE
        acceptUntilMs = Long.MIN_VALUE
        frames.clear()
        _liveCycles.value = 0
        _liveStats.value = OpenCloseLiveStats()
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()
}
