package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import com.example.parkinson.model.SelectedHand
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class FingerTappingViewModel : ViewModel() {

    private val _selectedHand = MutableStateFlow<SelectedHand?>(null)
    val selectedHand: StateFlow<SelectedHand?> = _selectedHand.asStateFlow()

    fun selectHand(hand: SelectedHand) {
        _selectedHand.value = hand
    }

    fun resetFlow() {
        _selectedHand.value = null
    }
}
