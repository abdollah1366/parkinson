package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.FingerTappingSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FingerTappingViewModel : ViewModel() {

    private val _selectedHand = MutableStateFlow<SelectedHand?>(null)
    val selectedHand: StateFlow<SelectedHand?> = _selectedHand.asStateFlow()

    /** Lives in the ViewModel so an interrupted session is still reported after rotation. */
    val session = FingerTappingSession(viewModelScope)

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
}
