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

    /** A usable camera result (quality VALID or LOW_QUALITY), already saved. */
    data class Done(val result: RestingTremorResult) : RestingTremorState

    /** A usable phone-motion result without a camera result, already saved. */
    data class DoneImu(val result: RestingTremorImuResult) : RestingTremorState

    /** No result: interrupted, or the quality control rejected the camera recording. */
    data class Invalid(val reason: RestingTremorInvalidReason) : RestingTremorState

    /** No result: a technical failure that left no usable data from either sensor path. */
    data class Error(val error: RestingTremorError) : RestingTremorState
}

val RestingTremorState.isActive: Boolean
    get() = this is RestingTremorState.Countdown || this is RestingTremorState.Recording || this is RestingTremorState.Processing

sealed interface RestingTremorInvalidReason {
    /** Stopped before the recording finished (cancelled, screen left, app backgrounded, rotation). */
    data object Interrupted : RestingTremorInvalidReason

    /** The recording finished but quality control rejected the camera data. */
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
 * One Resting Hand Tremor recording: IDLE -> COUNTDOWN -> RECORDING -> PROCESSING -> DONE / DONE_IMU / INVALID /
 * ERROR. Two acquisition paths run side by side and are judged separately:
 *
 *  - Camera: every MediaPipe result inside the recording window is kept as a raw frame ([onTrackingResult]).
 *    The camera result is stored as before, and only when the camera data is usable.
 *  - Phone motion ([motion], optional): the accelerometer (required) and gyroscope (optional) are started before
 *    the timed recording begins and stopped after it. The phone-motion result is stored whenever the accelerometer
 *    stream was registered, including when its data is insufficient (explicit INSUFFICIENT_DATA status).
 *
 * A camera failure during a recording that the phone-motion path is still capturing does not end the recording:
 * the camera result is then unavailable and the phone-motion path decides the outcome. Without an active
 * phone-motion capture, a camera failure ends the recording as before. Shared state is guarded by [lock]; a run
 * id stops a stale run from publishing.
 */
class RestingTremorSession(
    private val scope: CoroutineScope,
    /** Must be the clock used for HandTrackingResult.timestampMs. */
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
    /** Must be the clock used for the sensor event timestamps (elapsed realtime, ns). */
    private val elapsedNs: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val newAssessmentId: () -> String = { UUID.randomUUID().toString() },
    private val config: RestingTremorSessionConfig = RestingTremorSessionConfig(),
    private val engine: RestingTremorEngine = RestingTremorEngine(),
    private val imuEngine: RestingTremorImuEngine = RestingTremorImuEngine(),
    /** Phone-motion capture; null = camera-only session. */
    private val motion: RestingTremorMotionRecorder? = null,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Persists a usable camera result before DONE is published. */
    private val onCompleted: suspend (RestingTremorResult) -> Unit = {},
    /** Persists a phone-motion result (usable or insufficient) before DONE_IMU or INVALID is published. */
    private val onImuCompleted: suspend (RestingTremorImuResult) -> Unit = {},
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

    // Arrival counts of the current recording window (diagnostics only).
    private var resultsInWindow = 0
    private var resultsOutsideWindow = 0

    /** True while the phone-motion capture of the current recording is running. */
    private var imuRecording = false

    /** The first camera failure of the current recording; null while the camera path is healthy. */
    private var cameraFailure: RestingTremorError? = null

    private var job: Job? = null

    fun onTrackingResult(result: HandTrackingResult) {
        synchronized(lock) {
            lastResultArrivalMs = clock()
            consecutiveErrors = if (result is HandTrackingResult.Error) consecutiveErrors + 1 else 0
            val ts = result.timestampMs
            if (ts !in acceptFromMs..acceptUntilMs) {
                resultsOutsideWindow++
                return
            }
            resultsInWindow++
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

    /**
     * Reports a technical failure from outside (camera). During a phone-motion recording it only marks the camera
     * path as failed; otherwise it ends the session with an error.
     */
    fun fail(error: RestingTremorError) {
        if (!_state.value.isActive) return
        if (degradeCamera(error)) return
        stop(RestingTremorState.Error(error))
    }

    /** Back to IDLE, discarding any running session or previous outcome. */
    fun reset() {
        stop(RestingTremorState.Idle)
    }

    private fun stop(finalState: RestingTremorState) {
        job?.cancel()
        job = null
        motion?.discard()
        synchronized(lock) {
            clearRecordingLocked()
            runId++
            _state.value = finalState
        }
    }

    private suspend fun run(id: Int, hand: SelectedHand) {
        var motionStopped = false
        try {
            // The phone-motion capture starts before the countdown, so a camera stall during the countdown can be
            // absorbed by the motion path, and the sensors are already recording when timing begins.
            val imuStarted = motion?.start(IMU_SAMPLING_PERIOD_US) == MotionStartOutcome.STARTED
            synchronized(lock) {
                if (id != runId) return
                imuRecording = imuStarted
            }
            if (!runTimed(id, config.countdownMs) { RestingTremorState.Countdown(it) }) return

            val startMs = clock()
            val startWall = wallClock()
            synchronized(lock) {
                if (id != runId) return
                acceptFromMs = startMs
                acceptUntilMs = Long.MAX_VALUE
            }
            if (imuStarted) motion?.markRecordingStart()
            if (!runTimed(id, config.recordingMs, onTick = { updateLive(id, startMs) }) { RestingTremorState.Recording(it) }) return

            val endMs = startMs + config.recordingMs
            synchronized(lock) {
                if (id != runId) return
                // Frames captured before the end that MediaPipe has not delivered yet are still accepted.
                acceptUntilMs = endMs
                _state.value = RestingTremorState.Processing
            }
            delay(config.lateFrameGraceMs)

            val capture = synchronized(lock) {
                if (id != runId) return
                acceptFromMs = Long.MAX_VALUE
                acceptUntilMs = Long.MIN_VALUE
                imuRecording = false
                CameraCapture(frames.toList(), wallClock(), resultsInWindow, resultsOutsideWindow, cameraFailure)
            }
            val motionCapture = if (imuStarted) motion?.stop() else null
            motionStopped = true
            val endWall = capture.endWallMs

            val recording = RestingTremorRecording(capture.frames, config.recordingMs)
            val cameraAnalysis = if (capture.cameraFailure == null) {
                withContext(processingDispatcher) { engine.analyze(recording) }
            } else {
                null
            }
            cameraAnalysis?.let { RestingTremorDiagnostics.report(capture.resultsInWindow, capture.resultsOutside, recording, it) }
            val cameraResult = cameraAnalysis?.let { analysis ->
                val metrics = analysis.metrics
                if (analysis.quality.isUsable && metrics != null) {
                    RestingTremorResult.from(
                        analysis = analysis,
                        metrics = metrics,
                        frames = capture.frames,
                        assessmentId = newAssessmentId(),
                        startEpochMs = startWall,
                        endEpochMs = endWall,
                        hand = hand,
                        plannedDurationMs = config.recordingMs,
                        signal = engine.signal,
                    )
                } else {
                    null
                }
            }

            val imuResult = motionCapture?.let { capturedMotion ->
                val windowEnd = capturedMotion.windowStartNs + config.recordingMs * NS_PER_MS
                val accel = capturedMotion.accelerometer.filter { it.timestampNs in capturedMotion.windowStartNs..windowEnd }
                val gyro = capturedMotion.gyroscope.filter { it.timestampNs in capturedMotion.windowStartNs..windowEnd }
                val analysis = withContext(processingDispatcher) {
                    imuEngine.analyze(accel, gyro, config.recordingMs)
                }
                RestingTremorImuResult.from(
                    analysis = analysis,
                    assessmentId = newAssessmentId(),
                    startEpochMs = startWall,
                    endEpochMs = endWall,
                    hand = hand,
                    plannedDurationMs = config.recordingMs,
                    gyroscopePresent = motion?.gyroscopeAvailable() == true,
                    config = imuEngine.config,
                )
            }

            // Save what was measured, then decide the outcome. Each save failure is a storage error.
            if (cameraResult != null) {
                if (!save(id) { onCompleted(cameraResult) }) return
            }
            if (imuResult != null) {
                if (!save(id) { onImuCompleted(imuResult) }) return
            }

            when {
                cameraResult != null -> publish(id, RestingTremorState.Done(cameraResult))
                imuResult?.isUsable == true -> publish(id, RestingTremorState.DoneImu(imuResult))
                capture.cameraFailure != null -> publish(id, RestingTremorState.Error(capture.cameraFailure))
                cameraAnalysis != null ->
                    publish(id, RestingTremorState.Invalid(RestingTremorInvalidReason.QualityRejected(cameraAnalysis.quality)))
                else -> publish(id, RestingTremorState.Error(RestingTremorError.UNEXPECTED))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never crash on an analysis bug; report it and produce no result.
            publish(id, RestingTremorState.Error(RestingTremorError.UNEXPECTED))
        } finally {
            if (!motionStopped) motion?.discard()
        }
    }

    /** Runs [action] (a save). Returns false, after publishing a storage error, when it fails. */
    private suspend fun save(id: Int, action: suspend () -> Unit): Boolean {
        return try {
            action()
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            publish(id, RestingTremorState.Error(RestingTremorError.STORAGE_FAILURE))
            false
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
                if (!degradeCamera(error)) {
                    publish(id, RestingTremorState.Error(error))
                    return false
                }
            }
            if (!publish(id, stateFor(ceilSeconds(left)))) return false
            onTick()
            delay(min(config.tickMs, left))
        }
    }

    /**
     * Marks the camera path as failed while the phone-motion capture is running, so the recording continues on
     * the motion data alone. Returns false when no phone-motion capture is running (the caller must then stop).
     */
    private fun degradeCamera(error: RestingTremorError): Boolean = synchronized(lock) {
        if (!imuRecording) return@synchronized false
        if (cameraFailure == null) cameraFailure = error
        true
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
        resultsInWindow = 0
        resultsOutsideWindow = 0
        imuRecording = false
        cameraFailure = null
        _liveStats.value = RestingTremorLiveStats()
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

    /** What is taken from the camera path when its recording window closes. */
    private class CameraCapture(
        val frames: List<RestingTremorFrame>,
        val endWallMs: Long,
        val resultsInWindow: Int,
        val resultsOutside: Int,
        val cameraFailure: RestingTremorError?,
    )

    private companion object {
        /** 100 Hz requested; the measured rate is always computed from the event timestamps. */
        const val IMU_SAMPLING_PERIOD_US = 10_000
        const val NS_PER_MS = 1_000_000L
    }
}
