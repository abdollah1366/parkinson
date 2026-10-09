package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.speech.AndroidSpeechAudioSource
import com.example.parkinson.speech.InputCheckResult
import com.example.parkinson.speech.SpeechAudioFormat
import com.example.parkinson.speech.SpeechAudioSource
import com.example.parkinson.speech.SpeechCaptureException
import com.example.parkinson.speech.SpeechError
import com.example.parkinson.speech.SpeechInputCheck
import com.example.parkinson.speech.SpeechSession
import com.example.parkinson.speech.SpeechTask
import com.example.parkinson.speech.SpeechTaskEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the speech visit: the privacy consent given for this visit, the selected task, and the guided session.
 * The session lives here so a running recording is reported as interrupted after rotation, not restarted. Consent
 * is per visit: leaving the flow clears it.
 */
class SpeechViewModel(
    repository: AssessmentRepository,
    private val audio: SpeechAudioSource,
) : ViewModel() {

    private val _consent = MutableStateFlow(false)

    /** The person accepted the privacy notice and the recording consent for this visit. */
    val consent: StateFlow<Boolean> = _consent.asStateFlow()

    private val _task = MutableStateFlow<SpeechTask?>(null)
    val task: StateFlow<SpeechTask?> = _task.asStateFlow()

    val session = SpeechSession(viewModelScope, audio, onCompleted = repository::saveSpeech)

    private val _input = MutableStateFlow<InputCheckResult?>(null)

    /** Result of the last microphone check; null before a check or while it runs. */
    val input: StateFlow<InputCheckResult?> = _input.asStateFlow()

    private val _inputChecking = MutableStateFlow(false)
    val inputChecking: StateFlow<Boolean> = _inputChecking.asStateFlow()

    private val _inputError = MutableStateFlow<SpeechError?>(null)

    /** Why the microphone check could not run (for example, permission denied). */
    val inputError: StateFlow<SpeechError?> = _inputError.asStateFlow()

    private var checkJob: Job? = null

    /** Records [INPUT_CHECK_MS] of audio in memory, judges only its level and quality, and discards it. */
    fun checkInput() {
        if (checkJob?.isActive == true) return
        checkJob = viewModelScope.launch {
            _inputChecking.value = true
            _input.value = null
            _inputError.value = null
            try {
                val capture = audio.capture(INPUT_CHECK_MS, SpeechAudioFormat())
                val report = SpeechTaskEngine().inputReport(capture, INPUT_CHECK_MS)
                _input.value = SpeechInputCheck.interpret(report)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechCaptureException) {
                _inputError.value = e.error
            } catch (e: Exception) {
                _inputError.value = SpeechError.UNEXPECTED
            } finally {
                _inputChecking.value = false
            }
        }
    }

    fun setConsent(accepted: Boolean) {
        _consent.value = accepted
    }

    fun selectTask(task: SpeechTask) {
        _task.value = task
    }

    /** Ends the visit: no task selected, no consent, nothing running. */
    fun resetFlow() {
        session.reset()
        checkJob?.cancel()
        _input.value = null
        _inputError.value = null
        _task.value = null
        _consent.value = false
    }

    override fun onCleared() {
        session.reset()
    }

    companion object {
        /** Length of the microphone check before a task (ms). */
        const val INPUT_CHECK_MS = 2_000L

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                SpeechViewModel(app.assessmentRepository, AndroidSpeechAudioSource(app))
            }
        }
    }
}
