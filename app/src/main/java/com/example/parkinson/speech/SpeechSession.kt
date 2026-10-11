package com.example.parkinson.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.coroutines.coroutineContext
import kotlin.math.min

sealed interface SpeechState {
    data object Idle : SpeechState

    /** Preparation before the recording. Nothing is captured yet. */
    data class Countdown(val secondsLeft: Int) : SpeechState

    /** Recording in progress; [elapsedMs] is the time since the recording started. */
    data class Recording(val elapsedMs: Long, val plannedMs: Long) : SpeechState

    data object Processing : SpeechState

    /** A usable result, already saved. */
    data class Done(val result: SpeechResult) : SpeechState

    /** No result: the recording was interrupted or failed the quality check. */
    data class Invalid(val reason: SpeechFailure) : SpeechState

    /** No result: a technical failure (permission, microphone, storage, unexpected). */
    data class Error(val error: SpeechError) : SpeechState
}

sealed interface SpeechFailure {
    /** The recording finished but its quality does not allow a reliable analysis. */
    data class Quality(val report: SpeechQualityReport) : SpeechFailure

    /** Stopped by the person, by leaving the screen, or by backgrounding the app. */
    data object Interrupted : SpeechFailure
}

/**
 * One guided speech task: countdown, recording, processing, DONE / INVALID / ERROR. Audio stays in memory: the
 * buffer is referenced only inside [run] and is dropped when the analysis finishes. The result is persisted by
 * [onCompleted] before DONE is published, and only once per run (a run id guards against stale publication).
 *
 * Consent is checked before anything is captured: a session cannot start without it.
 */
class SpeechSession(
    private val scope: CoroutineScope,
    private val source: SpeechAudioSource,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newAssessmentId: () -> String = { UUID.randomUUID().toString() },
    private val newSessionId: () -> String = { UUID.randomUUID().toString() },
    private val signal: SpeechSignalConfig = SpeechSignalConfig(),
    private val quality: SpeechQualityThresholds = SpeechQualityThresholds(),
    private val format: SpeechAudioFormat = SpeechAudioFormat(),
    private val countdownMs: Long = 3_000L,
    private val tickMs: Long = 100L,
    private val processingDispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Persists a usable result before DONE is published. */
    private val onCompleted: suspend (SpeechResult) -> Unit = {},
) {

    private val _state = MutableStateFlow<SpeechState>(SpeechState.Idle)
    val state: StateFlow<SpeechState> = _state.asStateFlow()

    private val lock = Any()
    private var runId = 0
    private var job: Job? = null
    private var sessionId: String = newSessionId()

    /** Starts one task run. Requires [consentAccepted]; without consent nothing is recorded. */
    fun start(task: SpeechTask, consentAccepted: Boolean) {
        if (_state.value.isActive()) return
        job?.cancel()
        if (!consentAccepted) {
            _state.value = SpeechState.Error(SpeechError.CONSENT_NOT_GIVEN)
            return
        }
        val id = synchronized(lock) {
            sessionId = newSessionId()
            ++runId
        }
        job = scope.launch { run(id, task) }
    }

    /** Cancels the run without a result (the person stopped it on purpose). */
    fun cancel() {
        stop(SpeechState.Idle)
    }

    /** Ends an active run as interrupted (screen left, app backgrounded, rotation). */
    fun abort() {
        if (!_state.value.isActive()) return
        stop(SpeechState.Invalid(SpeechFailure.Interrupted))
    }

    /** Back to IDLE, discarding any run or previous outcome. */
    fun reset() {
        stop(SpeechState.Idle)
    }

    private fun stop(finalState: SpeechState) {
        job?.cancel()
        job = null
        synchronized(lock) {
            runId++
            _state.value = finalState
        }
    }

    private suspend fun run(id: Int, task: SpeechTask) {
        try {
            if (!countdown(id)) return
            publish(id, SpeechState.Recording(0L, task.plannedDurationMs))
            val startWall = wallClock()
            val capture = withTicker(id, task.plannedDurationMs) {
                source.capture(task.plannedDurationMs, format)
            }

            publish(id, SpeechState.Processing)
            // Stages of the analysis check this job between steps, so a cancelled run stops early.
            val runJob = coroutineContext[Job]
            val engine = SpeechTaskEngine(signal, quality, format) { runJob?.ensureActive() }
            val analysis = withContext(processingDispatcher) { engine.analyze(task, capture, task.plannedDurationMs) }
            val endWall = wallClock()

            if (!analysis.quality.isUsable) {
                publish(id, SpeechState.Invalid(SpeechFailure.Quality(analysis.quality)))
                return
            }
            val result = SpeechResult.from(
                analysis = analysis,
                capture = capture,
                assessmentId = newAssessmentId(),
                sessionId = sessionId,
                startEpochMs = startWall,
                endEpochMs = endWall,
                consentAccepted = true,
                signal = signal,
                plannedDurationMs = task.plannedDurationMs,
            )
            try {
                onCompleted(result)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                publish(id, SpeechState.Error(SpeechError.STORAGE_FAILURE))
                return
            }
            publish(id, SpeechState.Done(result))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SpeechCaptureException) {
            publish(id, SpeechState.Error(e.error))
        } catch (e: Exception) {
            publish(id, SpeechState.Error(SpeechError.UNEXPECTED))
        }
    }

    /** Counts down [countdownMs]; returns false when this run is no longer current. */
    private suspend fun countdown(id: Int): Boolean {
        var left = countdownMs
        while (left > 0) {
            if (!publish(id, SpeechState.Countdown(ceilSeconds(left)))) return false
            delay(min(tickMs, left))
            left -= min(tickMs, left)
        }
        return true
    }

    /** Runs [block] while publishing elapsed time every tick; the ticker is stopped when the block returns. */
    private suspend fun <T> withTicker(id: Int, plannedMs: Long, block: suspend () -> T): T = coroutineScope {
        val ticker = launch {
            var elapsed = 0L
            while (elapsed < plannedMs) {
                publish(id, SpeechState.Recording(elapsed, plannedMs))
                delay(tickMs)
                elapsed += tickMs
            }
        }
        try {
            block()
        } finally {
            ticker.cancel()
        }
    }

    private fun publish(id: Int, state: SpeechState): Boolean = synchronized(lock) {
        if (id != runId) return false
        _state.value = state
        true
    }

    private fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()

    private fun SpeechState.isActive(): Boolean =
        this is SpeechState.Countdown || this is SpeechState.Recording || this is SpeechState.Processing
}
