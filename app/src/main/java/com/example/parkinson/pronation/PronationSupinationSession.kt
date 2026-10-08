package com.example.parkinson.pronation

import android.os.SystemClock
import com.example.parkinson.diagnostics.SensorDiagnostics
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.sensors.MotionSensorType
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

sealed interface PronationState {
    data object Idle : PronationState

    /** Phone held still; the baseline (orientation, gyroscope bias) is estimated. */
    data class Preparing(val secondsLeft: Int) : PronationState

    /** 3, 2, 1. */
    data class Countdown(val count: Int) : PronationState

    /** [showStartCue]: the first second, when the UI shows "شروع". */
    data class Recording(val secondsLeft: Int, val showStartCue: Boolean) : PronationState
    data object Processing : PronationState

    /** A usable result (quality VALID or LOW_QUALITY), already saved. */
    data class Done(val result: PronationSupinationResult) : PronationState

    /** No result: rejected by quality control. */
    data class Invalid(val report: PronationQualityReport) : PronationState

    /** No result: Back, Home, screen lock, app in background or cancel before the end. */
    data object Interrupted : PronationState

    /** No result: technical failure. */
    data class Error(val error: PronationError) : PronationState
}

val PronationState.isActive: Boolean
    get() = this is PronationState.Preparing || this is PronationState.Countdown ||
        this is PronationState.Recording || this is PronationState.Processing

enum class PronationError {
    /** Gyroscope or accelerometer missing, or registration failed. */
    SENSOR_UNAVAILABLE,

    /** No sensor event for too long (sensors disabled, system throttling). */
    SENSOR_STOPPED,
    STORAGE_FAILURE,
    UNEXPECTED
}

/**
 * One Pronation/Supination test:
 * IDLE -> PREPARING (5 s) -> COUNTDOWN (3 s) -> RECORDING (10 s) -> PROCESSING ->
 * DONE / INVALID / INTERRUPTED / ERROR.
 *
 * Sensors start at PREPARING. Samples from PREPARING form the baseline; only samples that arrive
 * during RECORDING are analyzed. Analysis uses the sensor event timestamps; [clock] only paces the
 * countdowns and detects a stalled sensor. A session that does not complete never produces a
 * result, and nothing is processed after it ends (run id guard). [onSample] runs on the sensor
 * thread; everything else on the caller's (main) thread.
 */
class PronationSupinationSession(
    private val scope: CoroutineScope,
    private val source: MotionSensorSource,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newAssessmentId: () -> String = { UUID.randomUUID().toString() },
    private val preparationMs: Long = 5_000L,
    private val countdownSeconds: Int = 3,
    private val recordingMs: Long = 10_000L,
    private val startCueMs: Long = 1_000L,
    private val tickMs: Long = 100L,
    private val sensorStarvationMs: Long = 1_500L,
    /** 100 Hz requested; the real rate is measured. */
    private val samplingPeriodUs: Int = 10_000,
    private val engine: PronationSupinationEngine = PronationSupinationEngine(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onCompleted: suspend (PronationSupinationResult) -> Unit = {}
) {

    private enum class Phase { OFF, BASELINE, IGNORE, RECORD }

    private val _state = MutableStateFlow<PronationState>(PronationState.Idle)
    val state: StateFlow<PronationState> = _state.asStateFlow()

    private val lock = Any()

    // Guarded by lock.
    private var phase = Phase.OFF
    private val baseline = ArrayList<MotionSample>(2048)
    private val samples = ArrayList<MotionSample>(4096)
    private var runId = 0
    private var lastSampleArrivalMs = 0L

    private var job: Job? = null

    /** Called by the sensor source for every event. */
    fun onSample(sample: MotionSample) {
        synchronized(lock) {
            if (phase == Phase.OFF) return
            lastSampleArrivalMs = clock()
            SensorDiagnostics.onSample(sample, phase == Phase.RECORD)
            when (phase) {
                Phase.BASELINE -> baseline += sample
                Phase.RECORD -> samples += sample
                else -> Unit
            }
        }
    }

    fun start(hand: SelectedHand) {
        if (_state.value.isActive) return
        job?.cancel()
        SensorDiagnostics.reset(SensorDiagnostics.PRONATION_TAG)
        val id = synchronized(lock) {
            clearLocked()
            lastSampleArrivalMs = clock()
            ++runId
        }
        val available = MotionSensorType.entries.all { runCatching { source.isAvailable(it) }.getOrDefault(false) }
        if (!available) {
            publish(id, PronationState.Error(PronationError.SENSOR_UNAVAILABLE))
            return
        }
        synchronized(lock) { phase = Phase.BASELINE }
        if (!source.start(samplingPeriodUs, ::onSample)) {
            source.stop()
            publish(id, PronationState.Error(PronationError.SENSOR_UNAVAILABLE))
            return
        }
        job = scope.launch { run(id, hand) }
    }

    /** Stops an active session; it ends as INTERRUPTED, never as a result. */
    fun abort() {
        if (!_state.value.isActive) return
        stop(PronationState.Interrupted)
    }

    /** Back to IDLE, discarding any running session or outcome. */
    fun reset() = stop(PronationState.Idle)

    private fun stop(finalState: PronationState) {
        job?.cancel()
        job = null
        source.stop()
        synchronized(lock) {
            clearLocked()
            runId++
            setStateLocked(finalState)
        }
    }

    private fun clearLocked() {
        phase = Phase.OFF
        baseline.clear()
        samples.clear()
    }

    private suspend fun run(id: Int, hand: SelectedHand) {
        try {
            if (!runTimed(id, preparationMs) { PronationState.Preparing(seconds(it)) }) return
            synchronized(lock) {
                if (id != runId) return
                phase = Phase.IGNORE
            }
            if (!runTimed(id, countdownSeconds * 1_000L) { PronationState.Countdown(seconds(it)) }) return

            synchronized(lock) {
                if (id != runId) return
                samples.clear()
                phase = Phase.RECORD
            }
            if (!runTimed(id, recordingMs) { PronationState.Recording(seconds(it), recordingMs - it < startCueMs) }) return

            val recording = synchronized(lock) {
                if (id != runId) return
                phase = Phase.OFF
                setStateLocked(PronationState.Processing)
                PronationRecording(samples.toList(), baseline.toList(), recordingMs, hand)
            }
            source.stop()

            val analysis = withContext(processingDispatcher) { engine.analyze(recording) }
            if (id != synchronized(lock) { runId }) return
            logAnalysis(analysis)

            if (!analysis.quality.isUsable || analysis.metrics == null) {
                publish(id, PronationState.Invalid(analysis.quality))
                return
            }
            val result = PronationSupinationResult.from(analysis, newAssessmentId(), wallClock(), hand, recordingMs)
            try {
                onCompleted(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SensorDiagnostics.log("ERROR storage ${e::class.simpleName}")
                publish(id, PronationState.Error(PronationError.STORAGE_FAILURE))
                return
            }
            publish(id, PronationState.Done(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SensorDiagnostics.log("ERROR unexpected ${e::class.simpleName}")
            publish(id, PronationState.Error(PronationError.UNEXPECTED))
        }
    }

    private fun seconds(leftMs: Long): Int = ((leftMs + 999) / 1000).toInt()

    /** Runs one timed phase; [stateFor] receives the milliseconds left. */
    private suspend fun runTimed(id: Int, durationMs: Long, stateFor: (Long) -> PronationState): Boolean {
        val end = clock() + durationMs
        while (true) {
            val left = end - clock()
            if (left <= 0) return true
            val stalled = synchronized(lock) { clock() - lastSampleArrivalMs > sensorStarvationMs }
            if (stalled) {
                publish(id, PronationState.Error(PronationError.SENSOR_STOPPED))
                return false
            }
            if (!publish(id, stateFor(left))) return false
            delay(min(tickMs, left))
        }
    }

    private fun publish(id: Int, state: PronationState): Boolean {
        val terminal = state is PronationState.Done || state is PronationState.Invalid ||
            state is PronationState.Error || state is PronationState.Interrupted
        val published = synchronized(lock) {
            if (id != runId) return false
            if (terminal) clearLocked()
            setStateLocked(state)
            true
        }
        if (terminal) source.stop()
        return published
    }

    private fun setStateLocked(state: PronationState) {
        val previous = _state.value
        _state.value = state
        if (previous.label() != state.label()) SensorDiagnostics.log("STATE ${state.label()} t=${clock()}")
    }

    private fun logAnalysis(a: PronationAnalysis) {
        if (!SensorDiagnostics.enabled) return
        val f = SensorDiagnostics::f
        a.halfCycles.forEachIndexed { i, h ->
            SensorDiagnostics.log(
                "CYCLE half=$i startS=${f(h.startS, 3)} durationMs=${f(h.durationS * 1000, 0)} " +
                    "amplitudeDeg=${f(h.amplitudeDeg, 1)} peakDegS=${f(h.peakVelocityDegS, 1)} valid=${h.valid}"
            )
        }
        val m = a.metrics
        SensorDiagnostics.log(
            "RESULT accel(n=${a.accel.validSamples}/${a.accel.receivedSamples} rate=${f(a.accel.samplingRateHz, 1)}Hz " +
                "dropouts=${a.accel.dropoutCount}) gyro(n=${a.gyro.validSamples}/${a.gyro.receivedSamples} " +
                "rate=${f(a.gyro.samplingRateHz, 1)}Hz medianMs=${f(a.gyro.medianIntervalMs, 2)} " +
                "dropouts=${a.gyro.dropoutCount} longestGapMs=${f(a.gyro.longestGapMs, 1)}) " +
                "baseline=${a.baseline.status} windowMs=${f(a.analyzedDurationMs, 0)} hysteresisDeg=${f(a.hysteresisDeg, 1)} " +
                "cycles=${m?.cycleCount} valid=${m?.validCycleCount} rateHz=${m?.let { f(it.cycleRateHz, 2) }} " +
                "medianCycleMs=${m?.medianCycleDurationMs?.let { f(it, 0) }} amplitudeDeg=${m?.movementAmplitudeDeg?.let { f(it, 1) }} " +
                "peakDegS=${m?.angularVelocityPeakDegS?.let { f(it, 1) }} axisShare=${m?.let { f(it.rotationAxisSharePercent, 0) }} " +
                "pauses=${m?.pauseCount} quality=${a.quality.status} issues=${a.quality.issues} " +
                "qScore=${a.quality.qualityScore} score=${a.score?.total}"
        )
    }

    private fun PronationState.label(): String = when (this) {
        PronationState.Idle -> "IDLE"
        is PronationState.Preparing -> "PREPARING($secondsLeft)"
        is PronationState.Countdown -> "COUNTDOWN($count)"
        is PronationState.Recording -> "RECORDING($secondsLeft)"
        PronationState.Processing -> "PROCESSING"
        is PronationState.Done -> "DONE"
        is PronationState.Invalid -> "INVALID(${report.status})"
        PronationState.Interrupted -> "INTERRUPTED"
        is PronationState.Error -> "ERROR($error)"
    }
}
