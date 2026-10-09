package com.example.parkinson.gait

import android.os.SystemClock
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

sealed interface GaitState {
    data object Idle : GaitState

    /** Preparation: the countdown before the walk. Frames are not recorded yet. */
    data class Countdown(val secondsLeft: Int) : GaitState
    data class Recording(val secondsLeft: Int) : GaitState
    data object Processing : GaitState

    /** A usable result (quality VALID or LOW_QUALITY), already saved. */
    data class Done(val result: GaitResult) : GaitState

    /** No result: interrupted, or the quality control rejected the recording. */
    data class Invalid(val reason: GaitInvalidReason) : GaitState

    /** No result: a technical failure (camera, model, storage). */
    data class Error(val error: GaitError) : GaitState
}

val GaitState.isActive: Boolean
    get() = this is GaitState.Countdown || this is GaitState.Recording || this is GaitState.Processing

sealed interface GaitInvalidReason {
    /** Stopped before the walk finished (cancelled, screen left, app backgrounded, rotation). */
    data object Interrupted : GaitInvalidReason

    /** The walk finished but quality control rejected it. */
    data class QualityRejected(val report: GaitQualityReport) : GaitInvalidReason
}

enum class GaitError {
    CAMERA_FAILURE,

    /** The pose model kept failing (missing model, initialisation or detection errors). */
    MODEL_FAILURE,

    /** No results arrived for too long: the camera pipeline stalled. */
    FRAME_STARVATION,
    STORAGE_FAILURE,
    UNEXPECTED
}

/** Live figures during RECORDING, from the frames received so far (the final figures come from the full analysis). */
data class GaitLiveStats(
    val framesReceived: Int = 0,
    val validFrames: Int = 0,
)

/**
 * One walking recording: IDLE -> COUNTDOWN -> RECORDING -> PROCESSING -> DONE / INVALID / ERROR. A session
 * that does not complete normally never produces a result.
 *
 * Every pose result is pushed into [onPoseFrame] (synchronous listener, MediaPipe thread). Frames whose
 * timestamp lies inside the walk window are kept. The analysis runs once, on the complete recording.
 * Shared state is guarded by [lock]; a run id stops a stale run from publishing.
 */
class GaitSession(
    private val scope: CoroutineScope,
    /** Must be the clock used for the pose frame timestamps (see PoseLandmarkerManager). */
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newAssessmentId: () -> String = { UUID.randomUUID().toString() },
    private val config: GaitSessionConfig = GaitSessionConfig(),
    private val engine: GaitEngine = GaitEngine(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Persists a usable result before DONE is published. */
    private val onCompleted: suspend (GaitResult) -> Unit = {},
) {

    private val _state = MutableStateFlow<GaitState>(GaitState.Idle)
    val state: StateFlow<GaitState> = _state.asStateFlow()

    private val _liveStats = MutableStateFlow(GaitLiveStats())
    val liveStats: StateFlow<GaitLiveStats> = _liveStats.asStateFlow()

    private val lock = Any()

    // Guarded by lock.
    private var acceptFromMs = Long.MAX_VALUE
    private var acceptUntilMs = Long.MIN_VALUE
    private val frames = ArrayList<PoseFrame>(1024)
    private var runId = 0
    private var lastResultArrivalMs = 0L
    private var consecutiveErrors = 0

    // Arrival counts of the current walk window (diagnostics only).
    private var resultsInWindow = 0
    private var resultsOutsideWindow = 0

    private var job: Job? = null

    fun onPoseFrame(frame: PoseFrame) {
        synchronized(lock) {
            lastResultArrivalMs = clock()
            consecutiveErrors = if (frame.status == PoseFrameStatus.ERROR) consecutiveErrors + 1 else 0
            val ts = frame.timestampMs
            if (ts !in acceptFromMs..acceptUntilMs) {
                resultsOutsideWindow++
                return
            }
            resultsInWindow++
            frames += frame
            _liveStats.value = GaitLiveStats(
                framesReceived = frames.size,
                validFrames = frames.count { it.status == PoseFrameStatus.VALID },
            )
        }
    }

    fun start() {
        if (_state.value.isActive) return
        job?.cancel()
        val id = synchronized(lock) {
            clearRecordingLocked()
            lastResultArrivalMs = clock()
            consecutiveErrors = 0
            ++runId
        }
        job = scope.launch { run(id) }
    }

    /** Stops an active session. It ends as INVALID(Interrupted), never as a result. */
    fun abort() {
        if (!_state.value.isActive) return
        stop(GaitState.Invalid(GaitInvalidReason.Interrupted))
    }

    /** Stops an active session because of a technical failure reported from outside (camera). */
    fun fail(error: GaitError) {
        if (!_state.value.isActive) return
        stop(GaitState.Error(error))
    }

    /** Back to IDLE, discarding any running session or previous outcome. */
    fun reset() {
        stop(GaitState.Idle)
    }

    private fun stop(finalState: GaitState) {
        job?.cancel()
        job = null
        synchronized(lock) {
            clearRecordingLocked()
            runId++
            _state.value = finalState
        }
    }

    private suspend fun run(id: Int) {
        try {
            if (!runTimed(id, config.countdownMs) { GaitState.Countdown(it) }) return

            val startMs = clock()
            val startWall = wallClock()
            synchronized(lock) {
                if (id != runId) return
                clearRecordingLocked()
                acceptFromMs = startMs
                acceptUntilMs = Long.MAX_VALUE
            }
            if (!runTimed(id, config.recordingMs) { GaitState.Recording(it) }) return

            val endMs = startMs + config.recordingMs
            synchronized(lock) {
                if (id != runId) return
                // Frames captured before the end that the model has not delivered yet are still accepted.
                acceptUntilMs = endMs
                _state.value = GaitState.Processing
            }
            delay(config.lateFrameGraceMs)

            val capture = synchronized(lock) {
                if (id != runId) return
                acceptFromMs = Long.MAX_VALUE
                acceptUntilMs = Long.MIN_VALUE
                Capture(frames.toList(), wallClock(), resultsInWindow, resultsOutsideWindow)
            }
            val recording = GaitRecording(capture.frames, config.recordingMs)
            val analysis = withContext(processingDispatcher) { engine.analyze(recording) }
            GaitDiagnostics.report(capture.resultsInWindow, capture.resultsOutside, recording, analysis)
            val metrics = analysis.metrics
            if (!analysis.quality.isUsable || metrics == null) {
                publish(id, GaitState.Invalid(GaitInvalidReason.QualityRejected(analysis.quality)))
                return
            }

            val result = GaitResult.from(
                analysis = analysis,
                metrics = metrics,
                frames = capture.frames,
                assessmentId = newAssessmentId(),
                startEpochMs = startWall,
                endEpochMs = capture.endWallMs,
                plannedDurationMs = config.recordingMs,
                signal = engine.signal,
            )
            try {
                onCompleted(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(id, GaitState.Error(GaitError.STORAGE_FAILURE))
                return
            }
            publish(id, GaitState.Done(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never crash on an analysis bug; report it and produce no result.
            publish(id, GaitState.Error(GaitError.UNEXPECTED))
        }
    }

    /** Ticks [durationMs], publishing a state each tick. Returns false if the run must stop. */
    private suspend fun runTimed(
        id: Int,
        durationMs: Long,
        stateFor: (secondsLeft: Int) -> GaitState,
    ): Boolean {
        val end = clock() + durationMs
        while (true) {
            val left = end - clock()
            if (left <= 0) return true
            healthError()?.let { error ->
                publish(id, GaitState.Error(error))
                return false
            }
            if (!publish(id, stateFor(ceilSeconds(left)))) return false
            delay(min(config.tickMs, left))
        }
    }

    private fun healthError(): GaitError? = synchronized(lock) {
        when {
            consecutiveErrors >= config.maxConsecutiveErrors -> GaitError.MODEL_FAILURE
            clock() - lastResultArrivalMs > config.frameStarvationMs -> GaitError.FRAME_STARVATION
            else -> null
        }
    }

    private fun publish(id: Int, state: GaitState): Boolean = synchronized(lock) {
        if (id != runId) return false
        if (state !is GaitState.Countdown && state !is GaitState.Recording) {
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
        resultsInWindow = 0
        resultsOutsideWindow = 0
        _liveStats.value = GaitLiveStats()
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

    /** What is taken from the session when a walk window closes. */
    private class Capture(
        val frames: List<PoseFrame>,
        val endWallMs: Long,
        val resultsInWindow: Int,
        val resultsOutside: Int,
    )
}
