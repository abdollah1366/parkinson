package com.example.parkinson.sts

import android.os.SystemClock
import com.example.parkinson.gait.PoseSample
import com.example.parkinson.gait.PoseFrameStatus
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

sealed interface SitToStandState {
    data object Idle : SitToStandState

    /** Seated calibration window: the person sits still for the calibration time. */
    data object Calibrating : SitToStandState

    data class Countdown(val secondsLeft: Int) : SitToStandState

    /** The attempt is running; live figures are in [SitToStandSession.live]. */
    data object Active : SitToStandState

    data object Processing : SitToStandState

    /** A complete, usable result, already saved. */
    data class Done(val result: SitToStandResult) : SitToStandState

    /** No result: interrupted, incomplete, or the quality control rejected it. */
    data class Invalid(val reason: SitToStandFailure) : SitToStandState

    /** No result: a technical failure (camera, model, storage). */
    data class Error(val error: SitToStandError) : SitToStandState
}

/** Why an attempt produced no result. None of them is a score. */
sealed interface SitToStandFailure {
    /** The state machine ended the attempt (interrupted, tracking lost, timeout, timestamps). */
    data class Engine(val reason: SitToStandInvalidReason) : SitToStandFailure

    /** The attempt finished but too few frames were valid for a usable result. */
    data class TooFewValidFrames(val validFramePercent: Double) : SitToStandFailure
}

enum class SitToStandError {
    CAMERA_FAILURE,

    /** The pose model kept failing (missing model, initialisation or detection errors). */
    MODEL_FAILURE,

    /** No pose results arrived for too long: the camera pipeline stalled. */
    FRAME_STARVATION,
    STORAGE_FAILURE,
    UNEXPECTED,
}

/** Live figures of the attempt, for the screen. */
data class SitToStandLive(
    val phase: SitToStandPhase = SitToStandPhase.READY,
    val repetitions: Int = 0,
    val targetRepetitions: Int = 0,
    val elapsedMs: Long = 0L,
    val validFramePercent: Double? = null,
)

/**
 * One Five Times Sit-to-Stand attempt: calibration -> countdown -> attempt -> processing -> DONE / INVALID /
 * ERROR. The calibration gives the seated baseline; the attempt cannot start without it.
 *
 * Every pose result is pushed into [onPoseSample] (MediaPipe thread). Shared state is guarded by [lock]; a run
 * id stops a stale run from publishing. The result is persisted by [onCompleted] before DONE is published.
 */
class SitToStandSession(
    private val scope: CoroutineScope,
    /** Must be the clock used for the pose sample timestamps (see PoseLandmarkerManager). */
    private val clock: () -> Long = SystemClock::uptimeMillis,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newAssessmentId: () -> String = { UUID.randomUUID().toString() },
    private val protocol: SitToStandProtocol = SitToStandProtocol.FiveTimesSitToStand,
    private val config: SitToStandSessionConfig = SitToStandSessionConfig(),
    private val thresholds: SitToStandThresholds = SitToStandThresholds(),
    private val qualityThresholds: SitToStandQualityThresholds = SitToStandQualityThresholds(),
    private val extractor: SitToStandPoseExtractor = SitToStandPoseExtractor(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Persists a usable result before DONE is published. */
    private val onCompleted: suspend (SitToStandResult) -> Unit = {},
) {

    private val _state = MutableStateFlow<SitToStandState>(SitToStandState.Idle)
    val state: StateFlow<SitToStandState> = _state.asStateFlow()

    private val _baseline = MutableStateFlow<SeatedBaseline?>(null)

    /** The seated baseline once calibration passed; null before. */
    val baseline: StateFlow<SeatedBaseline?> = _baseline.asStateFlow()

    private val _calibrationIssue = MutableStateFlow<CalibrationIssue?>(null)

    /** Why the last calibration failed (null when none failed or it passed). */
    val calibrationIssue: StateFlow<CalibrationIssue?> = _calibrationIssue.asStateFlow()

    private val _live = MutableStateFlow(SitToStandLive(targetRepetitions = protocol.targetRepetitions))

    /** Live figures of the attempt. */
    val live: StateFlow<SitToStandLive> = _live.asStateFlow()

    private val _guideValidPercent = MutableStateFlow<Double?>(null)

    /** Share of the most recent frames with a usable body (any state), for the positioning guide. */
    val guideValidPercent: StateFlow<Double?> = _guideValidPercent.asStateFlow()

    private val lock = Any()

    // Guarded by lock.
    private var runId = 0
    private var job: Job? = null
    private var calibrator = SeatedCalibrator(qualityThresholds)
    private var engine: SitToStandEngine? = null
    private val accepted = ArrayList<SitToStandSample>()
    private var lastResultArrivalMs = 0L
    private var consecutiveErrors = 0
    private var activeStartMs = 0L
    private val guideWindow = ArrayDeque<Boolean>()
    private var attemptSide: SitToStandSide? = null

    /** Pose result from the MediaPipe thread: positions, calibration, the attempt and the guide. */
    fun onPoseSample(pose: PoseSample) {
        synchronized(lock) {
            lastResultArrivalMs = clock()
            consecutiveErrors = if (pose.status == PoseFrameStatus.ERROR) consecutiveErrors + 1 else 0
            val ts = pose.timestampMs
            val points = pose.points
            val currentState = _state.value
            val side = attemptSide ?: points?.let { extractor.chooseSide(it) } ?: SitToStandSide.RIGHT
            val sample = when {
                pose.status == PoseFrameStatus.ERROR -> SitToStandSample.invalid(ts, SitToStandIssue.ERROR, side)
                points == null -> SitToStandSample.invalid(ts, SitToStandIssue.NO_POSE, side)
                else -> extractor.extract(ts, points, pose.imageWidth, pose.imageHeight, side)
            }
            updateGuide(sample.isValid)

            when (currentState) {
                SitToStandState.Calibrating -> calibrator.add(sample)
                SitToStandState.Active -> processAttemptSample(sample)
                else -> Unit
            }
        }
    }

    /** Starts the seated calibration window. Requires a stable seated position; the attempt waits for it. */
    fun startCalibration() {
        if (_state.value != SitToStandState.Idle) return
        job?.cancel()
        val id = synchronized(lock) {
            calibrator = SeatedCalibrator(qualityThresholds)
            attemptSide = null
            _baseline.value = null
            _calibrationIssue.value = null
            _state.value = SitToStandState.Calibrating
            ++runId
        }
        job = scope.launch { runCalibration(id) }
    }

    /** Starts the countdown and the attempt. Only possible after a successful calibration. */
    fun start() {
        if (_state.value != SitToStandState.Idle) return
        val base = _baseline.value ?: return
        job?.cancel()
        val id = synchronized(lock) {
            engine = SitToStandEngine(base, protocol, thresholds)
            accepted.clear()
            attemptSide = base.side
            lastResultArrivalMs = clock()
            consecutiveErrors = 0
            _live.value = SitToStandLive(targetRepetitions = protocol.targetRepetitions)
            ++runId
        }
        job = scope.launch { run(id) }
    }

    /** Stops an active attempt. It ends as INVALID(Interrupted), never as a result. */
    fun abort() {
        val s = _state.value
        if (s != SitToStandState.Active && s !is SitToStandState.Countdown) return
        stop(SitToStandState.Invalid(SitToStandFailure.Engine(SitToStandInvalidReason.INTERRUPTED)))
    }

    /** Stops the session because of a technical failure reported from outside (camera). */
    fun fail(error: SitToStandError) {
        val s = _state.value
        if (s == SitToStandState.Idle || s is SitToStandState.Done || s is SitToStandState.Invalid || s is SitToStandState.Error) return
        stop(SitToStandState.Error(error))
    }

    /** Back to IDLE: discards any calibration, running attempt or previous outcome. */
    fun reset() {
        stop(SitToStandState.Idle)
        synchronized(lock) {
            _baseline.value = null
            _calibrationIssue.value = null
            guideWindow.clear()
            _guideValidPercent.value = null
        }
    }

    private fun stop(finalState: SitToStandState) {
        job?.cancel()
        job = null
        synchronized(lock) {
            runId++
            engine = null
            accepted.clear()
            attemptSide = if (finalState == SitToStandState.Idle) null else attemptSide
            _live.value = SitToStandLive(targetRepetitions = protocol.targetRepetitions)
            _state.value = finalState
        }
    }

    private suspend fun runCalibration(id: Int) {
        delay(config.calibrationMs)
        synchronized(lock) {
            if (id != runId) return
            when (val outcome = calibrator.result()) {
                is CalibrationOutcome.Ready -> {
                    _baseline.value = outcome.baseline
                    attemptSide = outcome.baseline.side
                }
                is CalibrationOutcome.Failed -> _calibrationIssue.value = outcome.issue
            }
            _state.value = SitToStandState.Idle
        }
    }

    private suspend fun run(id: Int) {
        try {
            if (!runCountdown(id)) return
            synchronized(lock) {
                if (id != runId) return
                activeStartMs = clock()
                _state.value = SitToStandState.Active
            }
            if (!runAttempt(id)) return
            finish(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Never crash on an analysis bug; report it and produce no result.
            publish(id, SitToStandState.Error(SitToStandError.UNEXPECTED))
        }
    }

    private suspend fun runCountdown(id: Int): Boolean {
        val end = clock() + config.countdownMs
        while (true) {
            val left = end - clock()
            if (left <= 0) return true
            healthError()?.let { publish(id, SitToStandState.Error(it)); return false }
            if (!publish(id, SitToStandState.Countdown(ceilSeconds(left)))) return false
            delay(min(config.tickMs, left))
        }
    }

    /** Ticks while the attempt runs: checks health and the time limit. Returns false when the run must stop. */
    private suspend fun runAttempt(id: Int): Boolean {
        while (true) {
            val stop = synchronized(lock) {
                if (id != runId) return false
                val e = engine ?: return false
                val now = clock()
                e.checkTimeout(now)
                _live.value = liveSnapshot(e, now)
                e.isComplete || e.phase == SitToStandPhase.PAUSED_OR_INVALID || e.phase == SitToStandPhase.ERROR
            }
            if (stop) return true
            healthError()?.let { publish(id, SitToStandState.Error(it)); return false }
            delay(config.tickMs)
        }
    }

    /** Builds the result (or the reason there is none), saves it, then publishes the outcome. */
    private suspend fun finish(id: Int) {
        publish(id, SitToStandState.Processing)
        val (e, samples) = synchronized(lock) {
            if (id != runId) return
            val current = engine ?: return
            current to accepted.toList()
        }
        if (e.phase == SitToStandPhase.ERROR) {
            publish(id, SitToStandState.Error(SitToStandError.MODEL_FAILURE))
            return
        }
        val reason = e.invalidReason
        if (!e.isComplete || reason != null) {
            publish(id, SitToStandState.Invalid(SitToStandFailure.Engine(reason ?: SitToStandInvalidReason.TIMEOUT_INCOMPLETE)))
            return
        }
        val now = wallClock()
        val startWall = now - (samples.last().timestampMs - samples.first().timestampMs)
        val result = withContext(processingDispatcher) {
            SitToStandResult.from(
                engine = e,
                samples = samples,
                assessmentId = newAssessmentId(),
                startEpochMs = startWall,
                endEpochMs = now,
                qualityThresholds = qualityThresholds,
            )
        }
        if (result.qualityStatus == com.example.parkinson.assessment.QualityStatus.INSUFFICIENT_DATA) {
            publish(id, SitToStandState.Invalid(SitToStandFailure.TooFewValidFrames(result.validFramePercent)))
            return
        }
        try {
            onCompleted(result)
        } catch (e2: CancellationException) {
            throw e2
        } catch (e2: Exception) {
            publish(id, SitToStandState.Error(SitToStandError.STORAGE_FAILURE))
            return
        }
        publish(id, SitToStandState.Done(result))
    }

    private fun processAttemptSample(sample: SitToStandSample) {
        val e = engine ?: return
        val before = e.validFrameCount + e.invalidFrameCount
        e.process(sample)
        if (e.validFrameCount + e.invalidFrameCount > before) accepted += sample
        _live.value = liveSnapshot(e, clock())
    }

    private fun liveSnapshot(e: SitToStandEngine, now: Long): SitToStandLive {
        val total = e.validFrameCount + e.invalidFrameCount
        return SitToStandLive(
            phase = e.phase,
            repetitions = e.repetitions.size,
            targetRepetitions = protocol.targetRepetitions,
            elapsedMs = now - activeStartMs,
            validFramePercent = if (total == 0) null else 100.0 * e.validFrameCount / total,
        )
    }

    private fun updateGuide(valid: Boolean) {
        guideWindow.addLast(valid)
        while (guideWindow.size > GUIDE_WINDOW) guideWindow.removeFirst()
        _guideValidPercent.value = 100.0 * guideWindow.count { it } / guideWindow.size
    }

    private fun healthError(): SitToStandError? = synchronized(lock) {
        when {
            consecutiveErrors >= config.maxConsecutiveErrors -> SitToStandError.MODEL_FAILURE
            clock() - lastResultArrivalMs > config.frameStarvationMs -> SitToStandError.FRAME_STARVATION
            else -> null
        }
    }

    private fun publish(id: Int, state: SitToStandState): Boolean = synchronized(lock) {
        if (id != runId) return false
        _state.value = state
        true
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

    private companion object {
        /** Frames in the positioning guide window. */
        const val GUIDE_WINDOW = 20
    }
}
