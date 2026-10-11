package com.example.parkinson.dualtask

import com.example.parkinson.imu.ImuGaitResult
import com.example.parkinson.imu.ImuGaitSession
import com.example.parkinson.imu.ImuGaitState
import com.example.parkinson.speech.AudioCapture
import com.example.parkinson.speech.SpeechAudioFormat
import com.example.parkinson.speech.SpeechAudioSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import kotlin.random.Random

/** Where the dual-task flow is. The two walks run in their own [ImuGaitSession]s. */
sealed interface DualTaskStage {
    /** The usual walk. */
    data object SingleWalk : DualTaskStage

    /** Between the walks: the start number is shown and spoken. */
    data class Interlude(val startNumber: Int) : DualTaskStage

    /** The walk while subtracting threes aloud. */
    data class DualWalk(val startNumber: Int) : DualTaskStage

    /** Standing still after the walk: the last number said is entered. */
    data class Answer(val startNumber: Int) : DualTaskStage

    data object Saving : DualTaskStage

    data class Done(val result: DualTaskResult) : DualTaskStage

    data object SaveFailed : DualTaskStage
}

/**
 * Runs the dual-task test: a usual walk ([single]), then a walk while counting backwards by three ([dual]), then the
 * entry of the last number said. When [useMicrophone] is on, the microphone records in memory during the second walk
 * and only the count of spoken answers and pauses is kept ([SpokenAnswerCounter]); the audio is dropped at once.
 * The walks themselves are not stored as walking-test results.
 */
class DualTaskController(
    private val scope: CoroutineScope,
    val single: ImuGaitSession,
    val dual: ImuGaitSession,
    private val audio: SpeechAudioSource?,
    private val onSave: suspend (DualTaskResult) -> Unit,
    private val random: Random = Random.Default,
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    private val walkingMs: Long = 30_000L,
) {
    private val _stage = MutableStateFlow<DualTaskStage>(DualTaskStage.SingleWalk)
    val stage: StateFlow<DualTaskStage> = _stage.asStateFlow()

    /** Whether the microphone may count the answers (the person's choice, with the permission granted). */
    var useMicrophone: Boolean = false

    private var singleResult: ImuGaitResult? = null
    private var dualResult: ImuGaitResult? = null
    private var capture: Deferred<SpokenAnswers?>? = null
    private var watcher: Job? = null

    fun reset() {
        watcher?.cancel()
        watcher = null
        capture?.cancel()
        capture = null
        single.reset()
        dual.reset()
        singleResult = null
        dualResult = null
        _stage.value = DualTaskStage.SingleWalk
    }

    /** The usual walk finished with a result. */
    fun singleWalkDone(result: ImuGaitResult) {
        if (_stage.value != DualTaskStage.SingleWalk) return
        singleResult = result
        _stage.value = DualTaskStage.Interlude(SerialSubtraction.START_NUMBERS[random.nextInt(SerialSubtraction.START_NUMBERS.size)])
    }

    /** Starts the second walk; the microphone starts with the walking itself. */
    fun startDualWalk() {
        val s = _stage.value as? DualTaskStage.Interlude ?: return
        _stage.value = DualTaskStage.DualWalk(s.startNumber)
        watcher?.cancel()
        watcher = scope.launch {
            dual.state.collect { state ->
                if (state is ImuGaitState.Walking && capture == null && useMicrophone && audio != null) {
                    capture = scope.async { listen() }
                }
            }
        }
    }

    private suspend fun listen(): SpokenAnswers? = try {
        val recording: AudioCapture = audio!!.capture(walkingMs, SpeechAudioFormat())
        SpokenAnswerCounter.count(recording, skipMs = INSTRUCTION_MS)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // No microphone, no permission or an interruption: the test goes on with the reported number only.
        null
    }

    /** The second walk finished with a result. */
    fun dualWalkDone(result: ImuGaitResult) {
        val s = _stage.value as? DualTaskStage.DualWalk ?: return
        watcher?.cancel()
        watcher = null
        dualResult = result
        _stage.value = DualTaskStage.Answer(s.startNumber)
    }

    /** Saves the result with the reported last number (null: not entered). */
    fun submit(lastNumber: Int?) {
        val s = _stage.value as? DualTaskStage.Answer ?: return
        val first = singleResult ?: return
        val second = dualResult ?: return
        _stage.value = DualTaskStage.Saving
        scope.launch {
            val spoken = capture?.let { job -> withTimeoutOrNull(MIC_WAIT_MS) { job.await() }.also { job.cancel() } }
            capture = null
            val result = DualTaskResult.combine(newId(), wallClock(), first, second, s.startNumber, lastNumber, spoken)
            val saved = try {
                withContext(NonCancellable) { onSave(result) }
                true
            } catch (e: Exception) {
                false
            }
            _stage.value = if (saved) DualTaskStage.Done(result) else DualTaskStage.SaveFailed
        }
    }

    private companion object {
        /** The recording ends with the walk; this is the most the save waits for it. */
        const val MIC_WAIT_MS = 4_000L

        /** The spoken instruction at the start of the walk lasts about this long; the microphone ignores it. */
        const val INSTRUCTION_MS = 5_000.0
    }
}
