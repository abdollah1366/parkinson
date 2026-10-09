package com.example.parkinson.tremor

import android.os.SystemClock
import com.example.parkinson.mediapipe.HandTrackingResult
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.raw.FrameStatus
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

sealed interface RestingTremorState {
    data object Idle : RestingTremorState

    /** Preparation: the countdown before the recording. Frames are not recorded yet. */
    data class Countdown(val secondsLeft: Int) : RestingTremorState
    data class Recording(val secondsLeft: Int) : RestingTremorState
    data object Processing : RestingTremorState

    /** A usable result (quality VALID or LOW_QUALITY), already saved. */
    data class Done(val result: RestingTremorResult) : RestingTremorState

    /** No result: interrupted, or the quality control rejected the recording. */
    data class Invalid(val reason: RestingTremorInvalidReason) : RestingTremorState

    /** No result: a technical failure (camera, tracking, storage). */
    data class Error(val error: RestingTremorError) : RestingTremorState
}

val RestingTremorState.isActive: Boolean
    get() = this is RestingTremorState.Countdown || this is RestingTremorState.Recording || this is RestingTremorState.Processing

sealed interface RestingTremorInvalidReason {
    /** Stopped before the recording finished (cancelled, screen left, app backgrounded, rotation). */
    data object Interrupted : RestingTremorInvalidReason

    /** The recording finished but quality control rejected it. */
    data class QualityRejected(val report: RestingTremorQualityReport) : RestingTremorInvalidReason
}

enum class RestingTremorError {
    CAMERA_FAILURE,

    /** MediaPipe kept failing (model missing, initialization or detection errors). */
    TRACKING_FAILURE,

    /** No results arrived for too long: the camera pipeline stalled. */
    FRAME_STARVATION,
    STORAGE_FAILURE,
    UNEXPECTED
}

/** Live figures during RECORDING, from the frames received so far (the final figures come from the full analysis). */
data class RestingTremorLiveStats(
    val framesReceived: Int = 0,
    val validFrames: Int = 0,
)

/**
 * One Resting Hand Tremor recording: IDLE -> COUNTDOWN -> RECORDING -> PROCESSING -> DONE / INVALID / ERROR.
 * A session that does not complete normally never produces a result.
 *
 * Every MediaPipe result is pushed into [onTrackingResult] (synchronous listener, MediaPipe thread). Results
 * whose timestamp lies inside the recording window are kept as raw frames. The analysis runs once, on the
 * complete recording. Shared state is guarded by [lock]; a run id stops a stale run from publishing.
 */
class RestingTremorSession(
    private val scope: CoroutineScope,
    /** Must be the clock used for HandTrackingResult.timestampMs. */
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newAssessmentId: () -> String = { UUID.randomUUID().toString() },
    private val config: RestingTremorSessionConfig = RestingTremorSessionConfig(),
    private val engine: RestingTremorEngine = RestingTremorEngine(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Persists a usable result before DONE is published. */
    private val onCompleted: suspend (RestingTremorResult) -> Unit = {},
) {

    private val _state = MutableStateFlow<RestingTremorState>(RestingTremorState.Idle)
    val state: StateFlow<RestingTremorState> = _state.asStateFlow()

    private val _liveStats = MutableStateFlow(RestingTremorLiveStats())
    val liveStats: StateFlow<RestingTremorLiveStats> = _liveStats.asStateFlow()

    private val lock = Any()

    // Guarded by lock.
    private var acceptFromMs = Long.MAX_VALUE
    private var acceptUntilMs = Long.MIN_VALUE
    private val frames = ArrayList<RestingTremorFrame>(1024)
    private var runId = 0
    private var lastResultArrivalMs = 0L
    private var consecutiveErrors = 0

    private var job: Job? = null

    fun onTrackingResult(result: HandTrackingResult) {
        synchronized(lock) {
            lastResultArrivalMs = clock()
            consecutiveErrors = if (result is HandTrackingResult.Error) consecutiveErrors + 1 else 0
            val ts = result.timestampMs
            if (ts !in acceptFromMs..acceptUntilMs) return
            frames += RestingTremorFrameExtractor.extract(frames.size, result)
        }
    }

    fun start(hand: SelectedHand) {
        if (_state.value.isActive) return
        job?.cancel()
        val id = synchronized(lock) {
            clearRecordingLocked()
            lastResultArrivalMs = clock()
            consecutiveErrors = 0
            ++runId
        }
        job = scope.launch { run(id, hand) }
    }

    /** Stops an active session. It ends as INVALID(Interrupted), never as a result. */
    fun abort() {
        if (!_state.value.isActive) return
        stop(RestingTremorState.Invalid(RestingTremorInvalidReason.Interrupted))
    }

    /** Stops an active session because of a technical failure reported from outside (camera). */
    fun fail(error: RestingTremorError) {
        if (!_state.value.isActive) return
        stop(RestingTremorState.Error(error))
    }

    /** Back to IDLE, discarding any running session or previous outcome. */
    fun reset() {
        stop(RestingTremorState.Idle)
    }

    private fun stop(finalState: RestingTremorState) {
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
            if (!runTimed(id, config.countdownMs) { RestingTremorState.Countdown(it) }) return

            val startMs = clock()
            val startWall = wallClock()
            synchronized(lock) {
                if (id != runId) return
                clearRecordingLocked()
                acceptFromMs = startMs
                acceptUntilMs = Long.MAX_VALUE
            }
            if (!runTimed(id, config.recordingMs, onTick = { updateLive(id, startMs) }) { RestingTremorState.Recording(it) }) return

            val endMs = startMs + config.recordingMs
            synchronized(lock) {
                if (id != runId) return
                // Frames captured before the end that MediaPipe has not delivered yet are still accepted.
                acceptUntilMs = endMs
                _state.value = RestingTremorState.Processing
            }
            delay(config.lateFrameGraceMs)

            val (recorded, endWall) = synchronized(lock) {
                if (id != runId) return
                acceptFromMs = Long.MAX_VALUE
                acceptUntilMs = Long.MIN_VALUE
                Pair(frames.toList(), wallClock())
            }

            val analysis = withContext(processingDispatcher) {
                engine.analyze(RestingTremorRecording(recorded, config.recordingMs))
            }
            val metrics = analysis.metrics
            if (!analysis.quality.isUsable || metrics == null) {
                publish(id, RestingTremorState.Invalid(RestingTremorInvalidReason.QualityRejected(analysis.quality)))
                return
            }

            val result = RestingTremorResult.from(
                analysis = analysis,
                metrics = metrics,
                frames = recorded,
                assessmentId = newAssessmentId(),
                startEpochMs = startWall,
                endEpochMs = endWall,
                hand = hand,
                plannedDurationMs = config.recordingMs,
                signal = engine.signal,
            )
            try {
                onCompleted(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(id, RestingTremorState.Error(RestingTremorError.STORAGE_FAILURE))
                return
            }
            publish(id, RestingTremorState.Done(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never crash on an analysis bug; report it and produce no result.
            publish(id, RestingTremorState.Error(RestingTremorError.UNEXPECTED))
        }
    }

    /** Ticks [durationMs], publishing a state each tick. Returns false if the run must stop. */
    private suspend fun runTimed(
        id: Int,
        durationMs: Long,
        onTick: suspend () -> Unit = {},
        stateFor: (secondsLeft: Int) -> RestingTremorState,
    ): Boolean {
        val end = clock() + durationMs
        while (true) {
            val left = end - clock()
            if (left <= 0) return true
            healthError()?.let { error ->
                publish(id, RestingTremorState.Error(error))
                return false
            }
            if (!publish(id, stateFor(ceilSeconds(left)))) return false
            onTick()
            delay(min(config.tickMs, left))
        }
    }

    private fun updateLive(id: Int, startMs: Long) {
        synchronized(lock) {
            if (id != runId) return
            val inWindow = frames.count { it.timestampMs >= startMs }
            _liveStats.value = RestingTremorLiveStats(
                framesReceived = inWindow,
                validFrames = frames.count { it.isValid && it.timestampMs >= startMs },
            )
        }
    }

    private fun healthError(): RestingTremorError? = synchronized(lock) {
        when {
            consecutiveErrors >= config.maxConsecutiveErrors -> RestingTremorError.TRACKING_FAILURE
            clock() - lastResultArrivalMs > config.frameStarvationMs -> RestingTremorError.FRAME_STARVATION
            else -> null
        }
    }

    private fun publish(id: Int, state: RestingTremorState): Boolean = synchronized(lock) {
        if (id != runId) return false
        if (state !is RestingTremorState.Countdown && state !is RestingTremorState.Recording) {
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
        _liveStats.value = RestingTremorLiveStats()
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()
}
