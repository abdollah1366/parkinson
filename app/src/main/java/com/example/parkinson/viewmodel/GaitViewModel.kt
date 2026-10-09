package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.gait.GaitSession

class GaitViewModel(repository: AssessmentRepository) : ViewModel() {

    /** Lives in the ViewModel so an interrupted walk is still reported after rotation. */
    val session = GaitSession(viewModelScope, onCompleted = repository::saveGait)

    fun resetFlow() {
        session.reset()
    }

    override fun onCleared() {
        session.reset()
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                GaitViewModel(app.assessmentRepository)
            }
        }
    }
}
