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

/** Timing of the sit-to-stand session (ms). */
data class ImuSitToStandSessionConfig(
    val samplingPeriodUs: Int = ImuSignalConfig().samplingPeriodUs,
    val calibrationMs: Long = 2_500L,
    val countdownMs: Long = 3_000L,
    /** The live count is recomputed on the buffered data this often (ms of active time). */
    val liveEveryMs: Long = 1_000L,
    val tickMs: Long = 100L,
    /** No sensor event for this long while running = the sensors stopped delivering. */
    val starvationMs: Long = 1_500L,
    /** Protocol time limit for the transfers, counted only while the sensors run (ms). */
    val timeLimitMs: Long = 60_000L,
)

sealed interface ImuSitToStandState {
    data object Idle : ImuSitToStandState

    /** Seated and still, for the baseline calibration. */
    data class Calibrating(val elapsedMs: Long) : ImuSitToStandState

    data class Countdown(val secondsLeft: Int) : ImuSitToStandState

    /** Transfers in progress; [repetitions] is the live count from the sensor data. */
    data class Active(val repetitions: Int, val elapsedMs: Long, val target: Int) : ImuSitToStandState

    /** Sensors released; resume registers them again. */
    data class Paused(val repetitions: Int, val target: Int) : ImuSitToStandState

    data object Processing : ImuSitToStandState

    data class Done(val result: ImuSitToStandResult) : ImuSitToStandState

    data class Invalid(val reason: ImuFailure) : ImuSitToStandState

    data class Error(val error: ImuError) : ImuSitToStandState
}

/** Why an attempt produced no result. None of these is a score. */
sealed interface ImuFailure {
    /** No posture change above the standing threshold was found: the phone did not register a transfer. */
    data class NoPostureChange(val maxPostureDeg: Double?) : ImuFailure

    /** The time limit passed before the repetitions were complete. */
    data class TimeLimit(val repetitions: Int) : ImuFailure

    /** Fewer repetitions than the protocol needs were complete at the end of the data. */
    data class Incomplete(val repetitions: Int, val target: Int) : ImuFailure

    /** A pause or sensor gap lies inside the timed span, so the total time is not a valid measurement. */
    data object GapInsideTimedSpan : ImuFailure

    /** The data quality does not allow a result (low coverage or unreliable timestamps). */
    data class InvalidData(val coveragePercent: Double, val timestampIssuePercent: Double) : ImuFailure

    data object Interrupted : ImuFailure
}

enum class ImuError {
    SENSOR_UNAVAILABLE,
    SENSOR_START_FAILED,

    /** Sensor events stopped arriving while the assessment was running. */
    SENSOR_STARVED,
    STORAGE_FAILURE,
    UNEXPECTED,
}

/**
 * Sit-to-stand assessment on the phone's accelerometer and gyroscope (phone in the front trouser pocket).
 *
 * Lifecycle: IDLE -> CALIBRATING (seated, still) -> COUNTDOWN -> ACTIVE (live count) [-> PAUSED -> ACTIVE] ->
 * PROCESSING -> DONE / INVALID / ERROR. Pausing releases the sensor listeners and resuming registers them again.
 * A result is stored only when the analysis passes and the timed span contains no gap. A run-id guard keeps a
 * stale run from publishing. The analysis runs on [processingDispatcher], never on the main thread.
 */
class ImuSitToStandSession(
    private val scope: CoroutineScope,
    private val repository: MotionSensorRepository,
    private val config: ImuSitToStandSessionConfig = ImuSitToStandSessionConfig(),
    private val signal: ImuSignalConfig = ImuSignalConfig(),
    private val calibrationLimits: CalibrationLimits = CalibrationLimits(),
    private val thresholds: SitToStandImuThresholds = SitToStandImuThresholds(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val nanoClock: () -> Long = SystemClock::elapsedRealtimeNanos,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Persists a completed result before DONE is published. */
    private val onCompleted: suspend (ImuSitToStandResult) -> Unit = {},
) {

    private val _state = MutableStateFlow<ImuSitToStandState>(ImuSitToStandState.Idle)
    val state: StateFlow<ImuSitToStandState> = _state.asStateFlow()

    private val _calibrationIssue = MutableStateFlow<CalibrationIssue?>(null)

    /** Why the last calibration did not pass (null when none failed). */
    val calibrationIssue: StateFlow<CalibrationIssue?> = _calibrationIssue.asStateFlow()

    private val _availability = MutableStateFlow<SensorAvailability?>(null)

    /** Sensor availability found by the last start. */
    val availability: StateFlow<SensorAvailability?> = _availability.asStateFlow()

    private val lock = Any()
    private var runId = 0
    private var job: Job? = null
    private var reference: Vec3? = null
    private var sessionId: String = newSessionId()

    // Active time: counted only while the sensors run (pauses excluded).
    private var accumulatedMs = 0L
    private var listenerSinceNs = 0L
    private var runningSinceMs = 0L

    /** Begins the calibration after checking the sensors. Nothing is recorded without both sensors. */
    fun start() {
        if (_state.value != ImuSitToStandState.Idle) return
        val found = repository.availability()
        _availability.value = found
        if (!found.ready) {
            _state.value = ImuSitToStandState.Error(ImuError.SENSOR_UNAVAILABLE)
            return
        }
        _calibrationIssue.value = null
        sessionId = newSessionId()
        repository.clear()
        listenerSinceNs = nanoClock()
        if (!repository.start(config.samplingPeriodUs)) {
            _state.value = ImuSitToStandState.Error(ImuError.SENSOR_START_FAILED)
            return
        }
        val id = synchronized(lock) { ++runId }
        job = scope.launch { calibrate(id) }
    }

    /** Pauses an active attempt. The sensor listeners are released now. */
    fun pause() {
        val s = _state.value as? ImuSitToStandState.Active ?: return
        job?.cancel()
        job = null
        repository.stop()
        synchronized(lock) {
            accumulatedMs += clock() - runningSinceMs
            runId++
            _state.value = ImuSitToStandState.Paused(s.repetitions, s.target)
        }
    }

    /** Resumes a paused attempt: the sensor listeners are registered again. */
    fun resume() {
        val paused = _state.value as? ImuSitToStandState.Paused ?: return
        listenerSinceNs = nanoClock()
        if (!repository.start(config.samplingPeriodUs)) {
            _state.value = ImuSitToStandState.Error(ImuError.SENSOR_START_FAILED)
            return
        }
        val id = synchronized(lock) {
            ++runId
        }
        job = scope.launch { activeLoop(id, paused.repetitions) }
    }

    /** Stops the attempt as interrupted: no result is produced. */
    fun abort() {
        if (!_state.value.isRunning()) return
        stop(ImuSitToStandState.Invalid(ImuFailure.Interrupted))
    }

    /** Cancels everything without a result, and returns to IDLE. */
    fun reset() = stop(ImuSitToStandState.Idle)

    private fun stop(finalState: ImuSitToStandState) {
        job?.cancel()
        job = null
        repository.stop()
        synchronized(lock) {
            runId++
            reference = null
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
                if (!publish(id, ImuSitToStandState.Calibrating(elapsed))) return
                if (starved()) return fail(id, ImuError.SENSOR_STARVED)
                delay(min(config.tickMs, config.calibrationMs - elapsed))
            }
            val snapshot = repository.snapshot()
            val outcome = withContext(processingDispatcher) {
                val grid = ImuGridBuilder.build(ImuCleaning.clean(snapshot), signal)
                    ?: return@withContext CalibrationOutcome.Failed(CalibrationIssue.INSUFFICIENT_DATA)
                SensorCalibration.calibrate(grid, 0.0, config.calibrationMs.toDouble(), calibrationLimits)
            }
            when (outcome) {
                is CalibrationOutcome.Failed -> {
                    repository.stop()
                    publish(id, ImuSitToStandState.Idle, issue = outcome.issue)
                    return
                }
                is CalibrationOutcome.Ready -> synchronized(lock) {
                    if (id != runId) return
                    reference = outcome.reference
                }
            }
            if (!countdown(id)) return
            repository.clear()
            activeLoop(id, 0)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(id, ImuError.UNEXPECTED)
        }
    }

    private suspend fun countdown(id: Int): Boolean {
        var left = config.countdownMs
        while (left > 0) {
            if (!publish(id, ImuSitToStandState.Countdown(ceilSeconds(left)))) return false
            delay(min(config.tickMs, left))
            left -= min(config.tickMs, left)
        }
        return true
    }

    /**
     * Live counting on the buffered data, one analysis every [ImuSitToStandSessionConfig.liveEveryMs] of active time.
     * Ends with the final analysis when the target is reached, or with a time-limit failure.
     */
    private suspend fun activeLoop(id: Int, startingRepetitions: Int) {
        try {
            synchronized(lock) {
                if (id != runId) return
                runningSinceMs = clock()
                listenerSinceNs = nanoClock()
            }
            var reps = startingRepetitions
            var lastLiveAt = -config.liveEveryMs
            while (true) {
                val elapsed = activeElapsedMs()
                if (elapsed - lastLiveAt >= config.liveEveryMs) {
                    lastLiveAt = elapsed
                    reps = liveCount()
                    if (reps >= thresholds.targetRepetitions) break
                }
                if (elapsed >= config.timeLimitMs) {
                    finish(id, ImuFailure.TimeLimit(reps))
                    return
                }
                if (starved()) return fail(id, ImuError.SENSOR_STARVED)
                if (!publish(id, ImuSitToStandState.Active(reps, elapsed, thresholds.targetRepetitions))) return
                delay(config.tickMs)
            }
            finish(id, null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            fail(id, ImuError.UNEXPECTED)
        }
    }

    private fun activeElapsedMs(): Long = synchronized(lock) { accumulatedMs + (clock() - runningSinceMs) }

    private suspend fun liveCount(): Int {
        val snapshot = repository.snapshot()
        val ref = synchronized(lock) { reference } ?: return 0
        return withContext(processingDispatcher) {
            val grid = ImuGridBuilder.build(ImuCleaning.clean(snapshot), signal) ?: return@withContext 0
            SitToStandImuDetector.analyze(grid, ref, thresholds).repetitions.size
        }
    }

    private suspend fun finish(id: Int, forced: ImuFailure?) {
        val snapshot = repository.snapshot()
        repository.stop()
        if (!publish(id, ImuSitToStandState.Processing)) return
        val ref = synchronized(lock) { reference }
        val outcome = withContext(processingDispatcher) { analyseFinal(snapshot, ref, forced) }
        when (outcome) {
            is FinalOutcome.Failed -> publish(id, ImuSitToStandState.Invalid(outcome.reason))
            is FinalOutcome.Ready -> {
                try {
                    onCompleted(outcome.result)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    publish(id, ImuSitToStandState.Error(ImuError.STORAGE_FAILURE))
                    return
                }
                publish(id, ImuSitToStandState.Done(outcome.result))
            }
        }
    }

    /** The complete analysis of the recording. Pure with respect to the session state. */
    private fun analyseFinal(snapshot: List<MotionSample>, ref: Vec3?, forced: ImuFailure?): FinalOutcome {
        if (ref == null) return FinalOutcome.Failed(ImuFailure.Interrupted)
        val target = thresholds.targetRepetitions
        val clean = ImuCleaning.clean(snapshot)
        val data = ImuDataInfo.of(snapshot, clean, config.timeLimitMs)
        val grid = ImuGridBuilder.build(clean, signal)
            ?: return FinalOutcome.Failed(forced ?: ImuFailure.InvalidData(0.0, data.timestampIssuePercent))
        val analysis = SitToStandImuDetector.analyze(grid, ref, thresholds)
        val usable = analysis.repetitions.size >= target
        val status = ImuQuality.status(analysis.coveragePercent, data, usable)

        val failure: ImuFailure? = when {
            forced != null -> forced
            data.timestampIssuePercent > ImuQuality.MAX_TIMESTAMP_ISSUE_PERCENT ->
                ImuFailure.InvalidData(analysis.coveragePercent, data.timestampIssuePercent)
            !usable && (analysis.maxPostureDeg ?: 0.0) < thresholds.standDeg ->
                ImuFailure.NoPostureChange(analysis.maxPostureDeg)
            !usable -> ImuFailure.Incomplete(analysis.repetitions.size, target)
            gapWithinSpan(analysis) -> ImuFailure.GapInsideTimedSpan
            status == QualityStatus.INSUFFICIENT_DATA || status == QualityStatus.INVALID ->
                ImuFailure.InvalidData(analysis.coveragePercent, data.timestampIssuePercent)
            else -> null
        }
        if (failure != null) return FinalOutcome.Failed(failure)

        val reps = analysis.repetitions
        val origin = reps.first().standOnsetMs
        val relative = reps.map {
            it.copy(
                standOnsetMs = it.standOnsetMs - origin,
                standReachedMs = it.standReachedMs - origin,
                sitOnsetMs = it.sitOnsetMs - origin,
                seatedReachedMs = it.seatedReachedMs - origin,
            )
        }
        val endWall = wallClock()
        val spanMs = (reps.last().seatedReachedMs - reps.first().standOnsetMs).toLong()
        return FinalOutcome.Ready(
            ImuSitToStandResult(
                assessmentId = newId(),
                timestampEpochMs = endWall,
                startEpochMs = endWall - spanMs,
                endEpochMs = endWall,
                sessionId = sessionId,
                protocolId = PROTOCOL_ID,
                placement = PLACEMENT_FRONT_TROUSER_POCKET,
                targetRepetitions = target,
                repetitions = relative,
                rejections = analysis.rejections,
                invalidIntervals = analysis.invalidIntervals,
                coveragePercent = analysis.coveragePercent,
                data = data,
                qualityStatus = status,
                algorithmVersion = ImuVersions.ALGORITHM_VERSION,
                configSummary = "${signal.summary()};${thresholds.summary()}",
                scoringVersion = ImuVersions.SCORING_VERSION,
            )
        )
    }

    /** True when an invalid interval overlaps the span from the first repetition's start to the last one's end. */
    private fun gapWithinSpan(analysis: SitToStandImuAnalysis): Boolean {
        val first = analysis.repetitions.first().standOnsetMs
        val last = analysis.repetitions.last().seatedReachedMs
        return analysis.invalidIntervals.any { it.endMs > first && it.startMs < last }
    }

    private fun starved(): Boolean {
        if (!repository.isRunning) return false
        // Measured from the last (re)registration: a pause must not look like starvation after resuming.
        val lastNs = maxOf(repository.lastTimestampNs() ?: 0L, listenerSinceNs)
        return (nanoClock() - lastNs) / 1_000_000L > config.starvationMs
    }

    private fun fail(id: Int, error: ImuError) {
        repository.stop()
        publish(id, ImuSitToStandState.Error(error))
    }

    private fun publish(id: Int, state: ImuSitToStandState, issue: CalibrationIssue? = null): Boolean = synchronized(lock) {
        if (id != runId) return false
        if (issue != null) _calibrationIssue.value = issue
        _state.value = state
        true
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

    private sealed interface FinalOutcome {
        data class Ready(val result: ImuSitToStandResult) : FinalOutcome
        data class Failed(val reason: ImuFailure) : FinalOutcome
    }

    private fun ImuSitToStandState.isRunning(): Boolean =
        this is ImuSitToStandState.Calibrating || this is ImuSitToStandState.Countdown ||
            this is ImuSitToStandState.Active || this is ImuSitToStandState.Paused || this is ImuSitToStandState.Processing

    companion object {
        const val PROTOCOL_ID = "five_times_sit_to_stand_imu"
    }
}

/** Version tags shared by both IMU assessments (see docs/imu-assessments-algorithm.md). */
object ImuVersions {
    const val ALGORITHM_VERSION = "imu-algo-1.0.0"
    const val SCORING_VERSION = "not-scored"
}
