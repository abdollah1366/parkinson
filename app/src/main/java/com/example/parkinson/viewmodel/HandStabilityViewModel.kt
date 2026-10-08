package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSensorSource
import com.example.parkinson.stability.HandStabilitySession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HandStabilityViewModel(
    repository: AssessmentRepository,
    sensorSource: MotionSensorSource
) : ViewModel() {

    private val _selectedHand = MutableStateFlow<SelectedHand?>(null)
    val selectedHand: StateFlow<SelectedHand?> = _selectedHand.asStateFlow()

    /**
     * Lives in the ViewModel: a screen rotation (activity recreation) does not stop the recording,
     * because the sensors do not depend on the UI. Leaving the screen does (see the test screen).
     */
    val session = HandStabilitySession(viewModelScope, sensorSource, onCompleted = repository::saveHandStability)

    fun selectHand(hand: SelectedHand) {
        _selectedHand.value = hand
    }

    fun resetFlow() {
        _selectedHand.value = null
        session.reset()
    }

    override fun onCleared() {
        session.reset()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                HandStabilityViewModel(app.assessmentRepository, app.motionSensorSource)
            }
        }
    }
}
