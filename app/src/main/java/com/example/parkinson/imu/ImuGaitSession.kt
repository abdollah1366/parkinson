package com.example.parkinson.imu

import android.os.SystemClock
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorRepository
import com.example.parkinson.sensors.MotionStreams
import com.example.parkinson.sensors.SensorAvailability
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

/** Timing of the walking session (ms). The walking time counts only while the sensors run. */
data class ImuGaitSessionConfig(
    val samplingPeriodUs: Int = ImuSignalConfig().samplingPeriodUs,
    val calibrationMs: Long = 3_000L,
    val countdownMs: Long = 3_000L,
    val liveEveryMs: Long = 2_000L,
    val tickMs: Long = 100L,
    val starvationMs: Long = 1_500L,
    val walkingMs: Long = 30_000L,
)

sealed interface ImuGaitState {
    data object Idle : ImuGaitState

    /** Standing still, for the baseline calibration. */
    data class Calibrating(val elapsedMs: Long) : ImuGaitState

    data class Countdown(val secondsLeft: Int) : ImuGaitState

    /** Walking; [steps] is the live count, [walkedMs] the walking time so far. */
    data class Walking(val steps: Int, val walkedMs: Long, val plannedMs: Long) : ImuGaitState

    data class Paused(val steps: Int, val walkedMs: Long, val plannedMs: Long) : ImuGaitState

    data object Processing : ImuGaitState

    data class Done(val result: ImuGaitResult) : ImuGaitState

    data class Invalid(val reason: ImuGaitFailure) : ImuGaitState

    data class Error(val error: ImuError) : ImuGaitState
}

sealed interface ImuGaitFailure {
    /** Too few steps in plausible walking bouts to report cadence or variability. */
    data class TooFewSteps(val steps: Int) : ImuGaitFailure

    /** Coverage or timestamp quality does not allow a result. */
    data class InvalidData(val coveragePercent: Double, val timestampIssuePercent: Double) : ImuGaitFailure

    data object Interrupted : ImuGaitFailure
}

/**
 * Timed walking trial on the phone's accelerometer and gyroscope (phone in the front trouser pocket).
 * Lifecycle: IDLE -> CALIBRATING (still) -> COUNTDOWN -> WALKING [-> PAUSED -> WALKING] -> PROCESSING ->
 * DONE / INVALID / ERROR. Pausing releases the sensor listeners. Data gaps (for example during a pause) break walking
 * bouts; they lower the coverage but do not invalidate the rest of the trial. The trial's analysis is computed from
 * the sensor timestamps only.
 */
class ImuGaitSession(
    private val scope: CoroutineScope,
    private val repository: MotionSensorRepository,
    private val config: ImuGaitSessionConfig = ImuGaitSessionConfig(),
    private val signal: ImuSignalConfig = ImuSignalConfig(),
    private val calibrationLimits: CalibrationLimits = CalibrationLimits(),
    private val thresholds: GaitImuThresholds = GaitImuThresholds(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val nanoClock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onCompleted: suspend (ImuGaitResult) -> Unit = {},
) {

    private val _state = MutableStateFlow<ImuGaitState>(ImuGaitState.Idle)
    val state: StateFlow<ImuGaitState> = _state.asStateFlow()

    private val _calibrationIssue = MutableStateFlow<CalibrationIssue?>(null)
    val calibrationIssue: StateFlow<CalibrationIssue?> = _calibrationIssue.asStateFlow()

    private val _availability = MutableStateFlow<SensorAvailability?>(null)
    val availability: StateFlow<SensorAvailability?> = _availability.asStateFlow()

    private val lock = Any()
    private var runId = 0
    private var job: Job? = null
    private var sessionId: String = newSessionId()
    private var accumulatedMs = 0L
    private var listenerSinceNs = 0L
    private var runningSinceMs = 0L

    fun start() {
        if (_state.value != ImuGaitState.Idle) return
        val found = repository.availability()
        _availability.value = found
        if (!found.ready) {
            _state.value = ImuGaitState.Error(ImuError.SENSOR_UNAVAILABLE)
            return
        }
        _calibrationIssue.value = null
        sessionId = newSessionId()
        repository.clear()
        listenerSinceNs = nanoClock()
        if (!repository.start(config.samplingPeriodUs)) {
            _state.value = ImuGaitState.Error(ImuError.SENSOR_START_FAILED)
            return
        }
        val id = synchronized(lock) { ++runId }
        job = scope.launch { calibrate(id) }
    }

    /** Pauses the walking. The sensor listeners are released now. */
    fun pause() {
        val s = _state.value as? ImuGaitState.Walking ?: return
        job?.cancel()
        job = null
        repository.stop()
        synchronized(lock) {
            accumulatedMs += clock() - runningSinceMs
            runId++
            _state.value = ImuGaitState.Paused(s.steps, accumulatedMs, s.plannedMs)
        }
    }

    fun resume() {
        if (_state.value !is ImuGaitState.Paused) return
        listenerSinceNs = nanoClock()
        if (!repository.start(config.samplingPeriodUs)) {
            _state.value = ImuGaitState.Error(ImuError.SENSOR_START_FAILED)
            return
        }
        val id = synchronized(lock) { ++runId }
        job = scope.launch { walkLoop(id) }
    }

    fun abort() {
        if (!_state.value.isRunning()) return
        stop(ImuGaitState.Invalid(ImuGaitFailure.Interrupted))
    }

    fun reset() = stop(ImuGaitState.Idle)

    private fun stop(finalState: ImuGaitState) {
        job?.cancel()
        job = null
        repository.stop()
        synchronized(lock) {
            runId++
            accumulatedMs = 0L
            _state.value = finalState
        }
    }

    private suspend fun calibrate(id: Int) {
        try {
            val startedAt = clock()
            while (true) {
                val elapsed = clock() - startedAt
                if (elapsed >= config.calibrationMs) break
                if (!publish(id, ImuGaitState.Calibrating(elapsed))) return
                if (starved()) return fail(id, ImuError.SENSOR_STARVED)
                delay(min(config.tickMs, config.calibrationMs - elapsed))
            }
            val snapshot = repository.snapshot()
            val outcome = withContext(processingDispatcher) {
                val grid = ImuGridBuilder.build(ImuCleaning.clean(snapshot), signal)
                    ?: return@withContext CalibrationOutcome.Failed(CalibrationIssue.INSUFFICIENT_DATA)
                SensorCalibration.calibrate(grid, 0.0, config.calibrationMs.toDouble(), calibrationLimits)
            }
            if (outcome is CalibrationOutcome.Failed) {
                repository.stop()
                publish(id, ImuGaitState.Idle, issue = outcome.issue)
                return
            }
            if (!countdown(id)) return
            repository.clear()
            walkLoop(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(id, ImuError.UNEXPECTED)
        }
    }

    private suspend fun countdown(id: Int): Boolean {
        var left = config.countdownMs
        while (left > 0) {
            if (!publish(id, ImuGaitState.Countdown(ceilSeconds(left)))) return false
            delay(min(config.tickMs, left))
            left -= min(config.tickMs, left)
        }
        return true
    }

    /** Walks until the planned walking time has elapsed (sensor-running time), with a live step count. */
    private suspend fun walkLoop(id: Int) {
        try {
            synchronized(lock) {
                if (id != runId) return
                runningSinceMs = clock()
                listenerSinceNs = nanoClock()
            }
            var steps = (_state.value as? ImuGaitState.Paused)?.steps ?: 0
            var lastLiveAt = -config.liveEveryMs
            while (true) {
                val walked = walkedMs()
                if (walked - lastLiveAt >= config.liveEveryMs) {
                    lastLiveAt = walked
                    steps = liveSteps()
                }
                if (walked >= config.walkingMs) break
                if (starved()) return fail(id, ImuError.SENSOR_STARVED)
                if (!publish(id, ImuGaitState.Walking(steps, walked, config.walkingMs))) return
                delay(config.tickMs)
            }
            finish(id)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(id, ImuError.UNEXPECTED)
        }
    }

    private fun walkedMs(): Long = synchronized(lock) { accumulatedMs + (clock() - runningSinceMs) }

    private suspend fun liveSteps(): Int {
        val snapshot = repository.snapshot()
        return withContext(processingDispatcher) {
            val grid = ImuGridBuilder.build(ImuCleaning.clean(snapshot), signal) ?: return@withContext 0
            GaitStepDetector.analyze(grid, thresholds).steps.size
        }
    }

    private suspend fun finish(id: Int) {
        val snapshot = repository.snapshot()
        repository.stop()
        if (!publish(id, ImuGaitState.Processing)) return
        val outcome = withContext(processingDispatcher) { analyseFinal(snapshot) }
        when (outcome) {
            is FinalOutcome.Failed -> publish(id, ImuGaitState.Invalid(outcome.reason))
            is FinalOutcome.Ready -> {
                try {
                    onCompleted(outcome.result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    publish(id, ImuGaitState.Error(ImuError.STORAGE_FAILURE))
                    return
                }
                publish(id, ImuGaitState.Done(outcome.result))
            }
        }
    }

    private fun analyseFinal(snapshot: List<MotionSample>): FinalOutcome {
        val clean = ImuCleaning.clean(snapshot)
        val data = ImuDataInfo.of(snapshot, clean, config.walkingMs)
        val grid = ImuGridBuilder.build(clean, signal)
            ?: return FinalOutcome.Failed(ImuGaitFailure.InvalidData(0.0, data.timestampIssuePercent))
        val analysis = GaitStepDetector.analyze(grid, thresholds)
        val steps = analysis.steps.size
        val validMs = analysis.bouts.sumOf { it.endMs - it.startMs }
        val usable = steps >= thresholds.minStepsForMetrics && validMs >= MIN_VALID_WALKING_MS
        val status = ImuQuality.status(analysis.coveragePercent, data, usable)

        if (data.timestampIssuePercent > ImuQuality.MAX_TIMESTAMP_ISSUE_PERCENT || status == QualityStatus.INVALID) {
            return FinalOutcome.Failed(ImuGaitFailure.InvalidData(analysis.coveragePercent, data.timestampIssuePercent))
        }
        if (steps < thresholds.minStepsForMetrics) return FinalOutcome.Failed(ImuGaitFailure.TooFewSteps(steps))
        if (status == QualityStatus.INSUFFICIENT_DATA) {
            return FinalOutcome.Failed(ImuGaitFailure.InvalidData(analysis.coveragePercent, data.timestampIssuePercent))
        }

        val intervals = analysis.intervalsMs
        val mean = intervals.takeIf { it.isNotEmpty() }?.average()
        val endWall = wallClock()
        return FinalOutcome.Ready(
            ImuGaitResult(
                assessmentId = newId(),
                timestampEpochMs = endWall,
                startEpochMs = endWall - config.walkingMs,
                endEpochMs = endWall,
                sessionId = sessionId,
                protocolId = PROTOCOL_ID,
                placement = PLACEMENT_FRONT_TROUSER_POCKET,
                plannedWalkingMs = config.walkingMs.toDouble(),
                validWalkingMs = validMs,
                steps = steps,
                bouts = analysis.bouts.size,
                cadenceStepsPerMinute = mean?.let { 60_000.0 / it },
                meanStepIntervalMs = mean,
                stepIntervalCvPercent = ImuStats.cv(intervals),
                turningMs = analysis.turningMs,
                turnRejectedSteps = analysis.turnRejected,
                implausibleRejectedPeaks = analysis.implausibleRejected,
                coveragePercent = analysis.coveragePercent,
                invalidIntervals = analysis.invalidIntervals,
                data = data,
                qualityStatus = status,
                algorithmVersion = ImuVersions.ALGORITHM_VERSION,
                configSummary = "${signal.summary()};${thresholds.summary()}",
                scoringVersion = ImuVersions.SCORING_VERSION,
            )
        )
    }

    private fun starved(): Boolean {
        if (!repository.isRunning) return false
        // Measured from the last (re)registration: a pause must not look like starvation after resuming.
        val lastNs = maxOf(repository.lastTimestampNs() ?: 0L, listenerSinceNs)
        return (nanoClock() - lastNs) / 1_000_000L > config.starvationMs
    }

    private fun fail(id: Int, error: ImuError) {
        repository.stop()
        publish(id, ImuGaitState.Error(error))
    }

    private fun publish(id: Int, state: ImuGaitState, issue: CalibrationIssue? = null): Boolean = synchronized(lock) {
        if (id != runId) return false
        if (issue != null) _calibrationIssue.value = issue
        _state.value = state
        true
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

    private sealed interface FinalOutcome {
        data class Ready(val result: ImuGaitResult) : FinalOutcome
        data class Failed(val reason: ImuGaitFailure) : FinalOutcome
    }

    private fun ImuGaitState.isRunning(): Boolean =
        this is ImuGaitState.Calibrating || this is ImuGaitState.Countdown ||
            this is ImuGaitState.Walking || this is ImuGaitState.Paused || this is ImuGaitState.Processing

    companion object {
        const val PROTOCOL_ID = "timed_walk_imu"

        /** A result needs at least this much walking in plausible bouts (ms). */
        const val MIN_VALID_WALKING_MS = 10_000.0
    }
}

/** Shared variability helper for the IMU results. */
object ImuStats {
    fun cv(values: List<Double>): Double? {
        if (values.size < 2) return null
        val mean = values.average()
        if (mean <= 0.0) return null
        val sd = kotlin.math.sqrt(values.sumOf { (it - mean) * (it - mean) } / values.size)
        return 100.0 * sd / mean
    }
}
