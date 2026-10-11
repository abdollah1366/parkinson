package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.dualtask.DualTaskController
import com.example.parkinson.imu.ImuGaitSession
import com.example.parkinson.sensors.MotionSensorRepository
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.speech.AndroidSpeechAudioSource
import com.example.parkinson.speech.SpeechAudioSource

/**
 * Holds the dual-task flow so a running walk survives rotation. The two walks are not saved as walking-test results;
 * only the combined dual-task result is stored.
 */
class DualTaskViewModel(
    repository: AssessmentRepository,
    source: MotionSensorSource,
    audio: SpeechAudioSource,
) : ViewModel() {

    val controller = DualTaskController(
        scope = viewModelScope,
        single = ImuGaitSession(scope = viewModelScope, repository = MotionSensorRepository(source)),
        dual = ImuGaitSession(scope = viewModelScope, repository = MotionSensorRepository(source)),
        audio = audio,
        onSave = repository::saveDualTask,
    )

    fun resetFlow() = controller.reset()

    override fun onCleared() = controller.reset()

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                DualTaskViewModel(app.assessmentRepository, app.motionSensorSource, AndroidSpeechAudioSource(app))
            }
        }
    }
}
