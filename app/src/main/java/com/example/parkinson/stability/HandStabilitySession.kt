package com.example.parkinson.stability

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

sealed interface StabilityState {
    data object Idle : StabilityState
    data class Preparation(val secondsLeft: Int) : StabilityState
    data class Recording(val secondsLeft: Int) : StabilityState
    data object Processing : StabilityState

    /** A usable result (quality VALID or LOW_QUALITY), already saved. */
    data class Done(val result: HandStabilityResult) : StabilityState

    /** No result: interrupted, or rejected by quality control. */
    data class Invalid(val reason: StabilityInvalidReason) : StabilityState

    /** No result: technical failure. */
    data class Error(val error: StabilityError) : StabilityState
}

val StabilityState.isActive: Boolean
    get() = this is StabilityState.Preparation || this is StabilityState.Recording || this is StabilityState.Processing

sealed interface StabilityInvalidReason {
    data object Interrupted : StabilityInvalidReason
    data class QualityRejected(val report: StabilityQualityReport) : StabilityInvalidReason
}

enum class StabilityError {
    /** Accelerometer or gyroscope missing, or registration failed. */
    SENSOR_UNAVAILABLE,

    /** No sensor event for too long (sensors disabled, system throttling). */
    SENSOR_STOPPED,
    STORAGE_FAILURE,
    UNEXPECTED
}

/**
 * One Hand Stability test: IDLE -> PREPARATION (5 s) -> RECORDING (15 s) -> PROCESSING ->
 * DONE / INVALID / ERROR.
 *
 * Sensors start at PREPARATION so they are settled when recording begins; only samples that
 * arrive during RECORDING are kept. Analysis uses the sensor event timestamps; [clock] only paces
 * the countdowns and detects a stalled sensor. A session that does not complete never produces a
 * result. [onSample] runs on the sensor thread; everything else on the caller's (main) thread.
 */
class HandStabilitySession(
    private val scope: CoroutineScope,
    private val source: MotionSensorSource,
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newAssessmentId: () -> String = { UUID.randomUUID().toString() },
    private val preparationMs: Long = 5_000L,
    private val recordingMs: Long = 15_000L,
    private val tickMs: Long = 100L,
    private val sensorStarvationMs: Long = 1_500L,
    /** 100 Hz requested; the real rate is measured. */
    private val samplingPeriodUs: Int = 10_000,
    private val engine: HandStabilityEngine = HandStabilityEngine(),
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val onCompleted: suspend (HandStabilityResult) -> Unit = {}
) {

    private val _state = MutableStateFlow<StabilityState>(StabilityState.Idle)
    val state: StateFlow<StabilityState> = _state.asStateFlow()

    private val lock = Any()

    // Guarded by lock.
    private var accepting = false
    private val samples = ArrayList<MotionSample>(4096)
    private var runId = 0
    private var lastSampleArrivalMs = 0L

    private var job: Job? = null

    /** Called by the sensor source for every event. */
    fun onSample(sample: MotionSample) {
        synchronized(lock) {
            lastSampleArrivalMs = clock()
            SensorDiagnostics.onSample(sample, accepting)
            if (accepting) samples += sample
        }
    }

    fun start(hand: SelectedHand) {
        if (_state.value.isActive) return
        job?.cancel()
        val id = synchronized(lock) {
            accepting = false
            samples.clear()
            lastSampleArrivalMs = clock()
            ++runId
        }
        SensorDiagnostics.reset()
        val available = MotionSensorType.entries.all { runCatching { source.isAvailable(it) }.getOrDefault(false) }
        if (!available || !source.start(samplingPeriodUs, ::onSample)) {
            source.stop()
            publish(id, StabilityState.Error(StabilityError.SENSOR_UNAVAILABLE))
            return
        }
        job = scope.launch { run(id, hand) }
    }

    /** Stops an active session; it ends as INVALID(Interrupted), never as a result. */
    fun abort() {
        if (!_state.value.isActive) return
        stop(StabilityState.Invalid(StabilityInvalidReason.Interrupted))
    }

    /** Back to IDLE, discarding any running session or outcome. */
    fun reset() = stop(StabilityState.Idle)

    private fun stop(finalState: StabilityState) {
        job?.cancel()
        job = null
        source.stop()
        synchronized(lock) {
            accepting = false
            samples.clear()
            runId++
            setStateLocked(finalState)
        }
    }

    private suspend fun run(id: Int, hand: SelectedHand) {
        try {
            if (!runTimed(id, preparationMs) { StabilityState.Preparation(it) }) return

            synchronized(lock) {
                if (id != runId) return
                samples.clear()
                accepting = true
            }
            if (!runTimed(id, recordingMs) { StabilityState.Recording(it) }) return

            val recording = synchronized(lock) {
                if (id != runId) return
                accepting = false
                setStateLocked(StabilityState.Processing)
                StabilityRecording(samples.toList(), recordingMs, hand)
            }
            source.stop()

            val analysis = withContext(processingDispatcher) { engine.analyze(recording) }
            logAnalysis(analysis)

            if (!analysis.quality.isUsable || analysis.metrics == null) {
                publish(id, StabilityState.Invalid(StabilityInvalidReason.QualityRejected(analysis.quality)))
                return
            }
            val result = HandStabilityResult.from(analysis, newAssessmentId(), wallClock(), hand, recordingMs)
            try {
                onCompleted(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SensorDiagnostics.log("ERROR storage ${e::class.simpleName}")
                publish(id, StabilityState.Error(StabilityError.STORAGE_FAILURE))
                return
            }
            publish(id, StabilityState.Done(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            SensorDiagnostics.log("ERROR unexpected ${e::class.simpleName}")
            publish(id, StabilityState.Error(StabilityError.UNEXPECTED))
        }
    }

    private suspend fun runTimed(id: Int, durationMs: Long, stateFor: (Int) -> StabilityState): Boolean {
        val end = clock() + durationMs
        while (true) {
            val left = end - clock()
            if (left <= 0) return true
            val stalled = synchronized(lock) { clock() - lastSampleArrivalMs > sensorStarvationMs }
            if (stalled) {
                publish(id, StabilityState.Error(StabilityError.SENSOR_STOPPED))
                return false
            }
            if (!publish(id, stateFor(((left + 999) / 1000).toInt()))) return false
            delay(min(tickMs, left))
        }
    }

    private fun publish(id: Int, state: StabilityState): Boolean {
        val terminal = state is StabilityState.Done || state is StabilityState.Invalid || state is StabilityState.Error
        val published = synchronized(lock) {
            if (id != runId) return false
            if (terminal) {
                accepting = false
                samples.clear()
            }
            setStateLocked(state)
            true
        }
        if (terminal) source.stop()
        return published
    }

    private fun setStateLocked(state: StabilityState) {
        if (_state.value != state) SensorDiagnostics.log("STATE ${state.label()} t=${clock()}")
        _state.value = state
    }

    private fun logAnalysis(a: StabilityAnalysis) {
        if (!SensorDiagnostics.enabled) return
        val f = SensorDiagnostics::f
        val m = a.metrics
        SensorDiagnostics.log(
            "RESULT accel(n=${a.accel.validSamples}/${a.accel.receivedSamples} rate=${f(a.accel.samplingRateHz, 1)}Hz " +
                "medianMs=${f(a.accel.medianIntervalMs, 2)} dropouts=${a.accel.dropoutCount} longestGapMs=${f(a.accel.longestGapMs, 1)}) " +
                "gyro(n=${a.gyro.validSamples}/${a.gyro.receivedSamples} rate=${f(a.gyro.samplingRateHz, 1)}Hz " +
                "medianMs=${f(a.gyro.medianIntervalMs, 2)} dropouts=${a.gyro.dropoutCount} longestGapMs=${f(a.gyro.longestGapMs, 1)}) " +
                "windowMs=${f(a.analyzedDurationMs, 0)} quality=${a.quality.status} issues=${a.quality.issues} " +
                "qScore=${a.quality.qualityScore} accDynRms=${m?.let { f(it.accDynamicRms, 4) }} " +
                "gyroDynRms=${m?.let { f(it.gyroDynamicRms, 3) }} rotRange=${m?.let { f(it.rotationRangeDeg, 2) }} " +
                "tilt=${m?.let { f(it.tiltChangeDeg, 1) }} freq=${m?.dominantFrequencyHz} freqStatus=${m?.frequencyStatus} " +
                "index=${a.index?.total}"
        )
    }

    private fun StabilityState.label(): String = when (this) {
        StabilityState.Idle -> "IDLE"
        is StabilityState.Preparation -> "PREPARATION($secondsLeft)"
        is StabilityState.Recording -> "RECORDING($secondsLeft)"
        StabilityState.Processing -> "PROCESSING"
        is StabilityState.Done -> "DONE"
        is StabilityState.Invalid -> when (val r = reason) {
            StabilityInvalidReason.Interrupted -> "INVALID(INTERRUPTED)"
            is StabilityInvalidReason.QualityRejected -> "INVALID(${r.report.status})"
        }
        is StabilityState.Error -> "ERROR($error)"
    }
}
