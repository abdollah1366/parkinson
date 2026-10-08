package com.example.parkinson.pronation

import android.os.SystemClock
import com.example.parkinson.diagnostics.SensorDiagnostics
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSampleBuffer
import com.example.parkinson.sensors.MotionSampleSink
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

    /** Phone held still; the baseline (orientation, gyroscope bias, noise) is estimated. */
    data class Preparing(val secondsLeft: Int) : PronationState

    /** 3, 2, 1. */
    data class Countdown(val count: Int) : PronationState

    /** [showStartCue]: the first second, when the UI shows "شروع کنید". */
    data class Recording(val secondsLeft: Int, val showStartCue: Boolean) : PronationState
    data object Processing : PronationState

    /** A usable result (quality VALID or LOW_QUALITY), already saved. */
    data class Done(val result: PronationSupinationResult) : PronationState

    /** No result: rejected by quality control (INVALID / INSUFFICIENT_DATA). Nothing is stored. */
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
    /** No gyroscope (the primary sensor), or it could not be registered. */
    SENSOR_UNAVAILABLE,

    /** No gyroscope event for too long (sensors disabled, system throttling). */
    SENSOR_STOPPED,

    /** Preflight: the gyroscope delivers too few samples per second. */
    SAMPLING_RATE_TOO_LOW,

    /** Preflight: gyroscope timestamps are not increasing. */
    TIMESTAMPS_INVALID,
    STORAGE_FAILURE,
    UNEXPECTED
}

/** Sensor check measured during preparation (before anything is recorded). */
data class SensorPreflight(
    val gyroSamples: Int,
    val gyroRateHz: Double,
    val monotonicPercent: Double,
    val accelerometerAvailable: Boolean
)

/**
 * One Pronation/Supination test:
 * IDLE -> PREPARING (5 s) -> COUNTDOWN (3-2-1) -> RECORDING (10 s) -> PROCESSING ->
 * DONE / INVALID / INTERRUPTED / ERROR.
 *
 * Sensors start at PREPARING (gyroscope required, accelerometer used when present). Samples from
 * PREPARING form the baseline and the preflight check; only RECORDING samples are analyzed.
 * Sensor events go through an allocation-free sink into pre-sized primitive buffers on the sensor
 * thread; analysis runs on [processingDispatcher], never on the main thread. A session that does
 * not complete never produces a result, and nothing is processed after it ends (run-id guard).
 */
class PronationSupinationSession(
    private val scope: CoroutineScope,
    private val source: MotionSensorSource,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val config: PronationSessionConfig = PronationSessionConfig(),
    private val engine: PronationSupinationEngine = PronationSupinationEngine(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onCompleted: suspend (PronationSupinationResult) -> Unit = {}
) {

    private enum class Phase { OFF, BASELINE, IGNORE, RECORD }

    private val _state = MutableStateFlow<PronationState>(PronationState.Idle)
    val state: StateFlow<PronationState> = _state.asStateFlow()

    /** Planned recording length, for progress display. */
    val recordingMs: Long get() = config.recordingMs

    private val lock = Any()

    // Guarded by lock.
    private var phase = Phase.OFF
    private val baseline = MotionSampleBuffer(config.bufferCapacity)
    private val recording = MotionSampleBuffer(config.bufferCapacity)
    private var runId = 0
    private var lastGyroArrivalMs = 0L

    private var job: Job? = null

    /** Sensor thread. No allocation; short critical section. */
    private val sink = MotionSampleSink { type, ts, x, y, z, unreliable ->
        val recordingNow = synchronized(lock) {
            if (phase == Phase.OFF) return@MotionSampleSink
            if (type == MotionSensorType.GYROSCOPE) lastGyroArrivalMs = clock()
            when (phase) {
                Phase.BASELINE -> baseline.add(type, ts, x, y, z, unreliable)
                Phase.RECORD -> recording.add(type, ts, x, y, z, unreliable)
                else -> Unit
            }
            phase == Phase.RECORD
        }
        // Debug builds only (allocates); release builds take the allocation-free path above.
        if (SensorDiagnostics.enabled) SensorDiagnostics.onSample(MotionSample(type, ts, x, y, z, unreliable), recordingNow)
    }

    /** Test hook and adapter for sources that deliver [MotionSample] objects. */
    fun onSample(sample: MotionSample) =
        sink.onSample(sample.type, sample.timestampNs, sample.x, sample.y, sample.z, sample.unreliable)

    fun start(hand: SelectedHand) {
        if (_state.value.isActive) return
        job?.cancel()
        SensorDiagnostics.reset(SensorDiagnostics.PRONATION_TAG)
        val id = synchronized(lock) {
            clearLocked()
            lastGyroArrivalMs = clock()
            ++runId
        }
        val gyroAvailable = runCatching { source.isAvailable(MotionSensorType.GYROSCOPE) }.getOrDefault(false)
        if (!gyroAvailable) {
            publish(id, PronationState.Error(PronationError.SENSOR_UNAVAILABLE))
            return
        }
        val accelAvailable = runCatching { source.isAvailable(MotionSensorType.ACCELEROMETER) }.getOrDefault(false)
        synchronized(lock) { phase = Phase.BASELINE }
        val started = source.startRaw(
            config.samplingPeriodUs,
            required = setOf(MotionSensorType.GYROSCOPE),
            optional = if (accelAvailable) setOf(MotionSensorType.ACCELEROMETER) else emptySet(),
            sink = sink
        )
        if (!started) {
            source.stop()
            publish(id, PronationState.Error(PronationError.SENSOR_UNAVAILABLE))
            return
        }
        val sessionId = newId()
        job = scope.launch { run(id, sessionId, hand, accelAvailable) }
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
        recording.clear()
    }

    private suspend fun run(id: Int, sessionId: String, hand: SelectedHand, accelAvailable: Boolean) {
        try {
            if (!runTimed(id, config.preparationMs) { PronationState.Preparing(seconds(it)) }) return
            val preflight = synchronized(lock) {
                if (id != runId) return
                phase = Phase.IGNORE
                preflight(accelAvailable)
            }
            logPreflight(preflight)
            preflightError(preflight)?.let {
                publish(id, PronationState.Error(it))
                return
            }
            if (!runTimed(id, config.countdownSeconds * 1_000L) { PronationState.Countdown(seconds(it)) }) return

            synchronized(lock) {
                if (id != runId) return
                recording.clear()
                phase = Phase.RECORD
            }
            val recordingMs = config.recordingMs
            if (!runTimed(id, recordingMs) { PronationState.Recording(seconds(it), recordingMs - it < config.startCueMs) }) return

            synchronized(lock) {
                if (id != runId) return
                phase = Phase.OFF
                setStateLocked(PronationState.Processing)
            }
            source.stop()

            val analysis = withContext(processingDispatcher) {
                // Copies are made here, off the sensor and main threads; the buffers are not
                // written any more (phase OFF) until the next start.
                val (samples, base) = synchronized(lock) { recording.toSamples() to baseline.toSamples() }
                engine.analyze(PronationRecording(samples, base, recordingMs, hand, accelAvailable))
            }
            if (id != synchronized(lock) { runId }) return
            logAnalysis(analysis)

            if (!analysis.quality.isUsable || analysis.metrics == null) {
                publish(id, PronationState.Invalid(analysis.quality))
                return
            }
            val result = PronationSupinationResult.from(analysis, newId(), sessionId, wallClock(), hand, recordingMs)
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

    /** Gyroscope rate and timestamp validity measured on the preparation samples. Lock held. */
    private fun preflight(accelAvailable: Boolean): SensorPreflight {
        val ts = baseline.timestamps(MotionSensorType.GYROSCOPE)
        val span = if (ts.size >= 2) (ts.last() - ts.first()) / 1e9 else 0.0
        val increasing = (1 until ts.size).count { ts[it] > ts[it - 1] }
        return SensorPreflight(
            gyroSamples = ts.size,
            gyroRateHz = if (span > 0) (ts.size - 1) / span else 0.0,
            monotonicPercent = if (ts.size >= 2) increasing * 100.0 / (ts.size - 1) else 0.0,
            accelerometerAvailable = accelAvailable
        )
    }

    private fun preflightError(p: SensorPreflight): PronationError? = when {
        p.gyroSamples < 2 || p.gyroRateHz < config.preflightMinGyroRateHz -> PronationError.SAMPLING_RATE_TOO_LOW
        p.monotonicPercent < config.preflightMinMonotonicPercent -> PronationError.TIMESTAMPS_INVALID
        else -> null
    }

    private fun seconds(leftMs: Long): Int = ((leftMs + 999) / 1000).toInt()

    /** Runs one timed phase; [stateFor] receives the milliseconds left. */
    private suspend fun runTimed(id: Int, durationMs: Long, stateFor: (Long) -> PronationState): Boolean {
        val end = clock() + durationMs
        while (true) {
            val left = end - clock()
            if (left <= 0) return true
            val stalled = synchronized(lock) { clock() - lastGyroArrivalMs > config.sensorStarvationMs }
            if (stalled) {
                publish(id, PronationState.Error(PronationError.SENSOR_STOPPED))
                return false
            }
            if (!publish(id, stateFor(left))) return false
            delay(min(config.tickMs, left))
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

    private fun logPreflight(p: SensorPreflight) {
        if (!SensorDiagnostics.enabled) return
        SensorDiagnostics.log(
            "PREFLIGHT gyroSamples=${p.gyroSamples} gyroRateHz=${SensorDiagnostics.f(p.gyroRateHz, 1)} " +
                "monotonic=${SensorDiagnostics.f(p.monotonicPercent, 1)}% accelerometer=${p.accelerometerAvailable}"
        )
    }

    private fun logAnalysis(a: PronationAnalysis) {
        if (!SensorDiagnostics.enabled) return
        val f = SensorDiagnostics::f
        a.movements.forEachIndexed { i, m ->
            SensorDiagnostics.log(
                "CYCLE movement=$i startS=${f(m.startS, 3)} durationMs=${f(m.durationS * 1000, 0)} " +
                    "amplitudeDeg=${f(m.amplitudeDeg, 1)} peakDegS=${f(m.peakAngularVelocityDegS, 1)} " +
                    "meanDegS=${f(m.meanAngularVelocityDegS, 1)} direction=${m.direction} rejection=${m.rejection}"
            )
        }
        val m = a.metrics
        val s = a.score
        SensorDiagnostics.log(
            "RESULT gyro(n=${a.gyro.validSamples}/${a.gyro.receivedSamples} rate=${f(a.gyro.samplingRateHz, 1)}Hz " +
                "medianMs=${f(a.gyro.medianIntervalMs, 2)} dropouts=${a.gyro.dropoutCount} longestGapMs=${f(a.gyro.longestGapMs, 1)}) " +
                "accel(used=${a.accelerometerAvailable} n=${a.accel.validSamples} rate=${f(a.accel.samplingRateHz, 1)}Hz) " +
                "baseline=${a.baseline.status} baselineNoise=${a.baseline.noiseDegS?.let { f(it, 2) }} " +
                "windowMs=${f(a.analyzedDurationMs, 0)} hysteresisDeg=${f(a.hysteresisDeg, 1)} " +
                "cycles=${m?.cycleCount} valid=${m?.validCycleCount} cps=${m?.let { f(it.cyclesPerSecond, 2) }} " +
                "medianCycleMs=${m?.medianCycleDurationMs?.let { f(it, 0) }} medianAmplitudeDeg=${m?.medianAmplitudeDeg?.let { f(it, 1) }} " +
                "peakDegS=${m?.peakAngularVelocityDegS?.let { f(it, 1) }} meanDegS=${m?.let { f(it.meanAngularVelocityDegS, 1) }} " +
                "pauses=${m?.pauseCount} pauseMs=${m?.let { f(it.totalPauseMs, 0) }} noiseDegS=${m?.let { f(it.noiseLevelDegS, 2) }} " +
                "axisShare=${m?.let { f(it.rotationAxisSharePercent, 0) }} forearmAxis=${m?.axisAlignedWithForearm} " +
                "quality=${a.quality.status} qualityPct=${a.quality.qualityPercentage} issues=${a.quality.issues} " +
                "score=${s?.total} components=${s?.components} trend=${s?.trend} band=${a.interpretation.band}"
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
