package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.FingerTappingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FingerTappingViewModel(repository: AssessmentRepository) : ViewModel() {

    private val _selectedHand = MutableStateFlow<SelectedHand?>(null)
    val selectedHand: StateFlow<SelectedHand?> = _selectedHand.asStateFlow()

    /**
     * Lives in the ViewModel so an interrupted session is still reported after rotation.
     * A usable result is saved before the session reports DONE.
     */
    val session = FingerTappingSession(viewModelScope, onCompleted = repository::save)

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
                FingerTappingViewModel(app.assessmentRepository)
            }
        }
    }
}
