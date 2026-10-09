package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.openclose.HandOpenCloseSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class HandOpenCloseViewModel(repository: AssessmentRepository) : ViewModel() {

    private val _selectedHand = MutableStateFlow<SelectedHand?>(null)
    val selectedHand: StateFlow<SelectedHand?> = _selectedHand.asStateFlow()

    /** Lives in the ViewModel so an interrupted session is still reported after rotation. */
    val session = HandOpenCloseSession(viewModelScope, onCompleted = repository::saveHandOpenClose)

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
                HandOpenCloseViewModel(app.assessmentRepository)
            }
        }
    }
}
