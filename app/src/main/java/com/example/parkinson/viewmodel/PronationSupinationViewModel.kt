package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.pronation.PronationSupinationSession
import com.example.parkinson.sensors.AndroidMotionSensorSource
import com.example.parkinson.sensors.MotionSensorSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class PronationSupinationViewModel(
    repository: AssessmentRepository,
    sensorSource: MotionSensorSource
) : ViewModel() {

    /** Explicit user selection: sensor data cannot tell the left hand from the right one. */
    private val _selectedHand = MutableStateFlow<SelectedHand?>(null)
    val selectedHand: StateFlow<SelectedHand?> = _selectedHand.asStateFlow()

    /** Lives in the ViewModel: the session and the sensors do not depend on the UI. */
    val session = PronationSupinationSession(viewModelScope, sensorSource, onCompleted = repository::savePronationSupination)

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
                // Own sensor source: stopping another test's session never stops this one.
                PronationSupinationViewModel(app.assessmentRepository, AndroidMotionSensorSource(app))
            }
        }
    }
}
