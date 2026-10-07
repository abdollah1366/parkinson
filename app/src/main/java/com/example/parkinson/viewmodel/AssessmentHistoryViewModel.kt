package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.tapping.result.FingerTappingAssessment
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Loading state of stored data, so the UI can tell "loading" from "empty". */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
}

/** Read access to stored assessments for Home, History and Result screens. */
class AssessmentHistoryViewModel(private val repository: AssessmentRepository) : ViewModel() {

    val latest: StateFlow<Loadable<FingerTappingAssessment?>> = repository.observeLatest()
        .map<FingerTappingAssessment?, Loadable<FingerTappingAssessment?>> { Loadable.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable.Loading)

    val all: StateFlow<Loadable<List<FingerTappingAssessment>>> = repository.observeAll()
        .map<List<FingerTappingAssessment>, Loadable<List<FingerTappingAssessment>>> { Loadable.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable.Loading)

    fun assessment(id: String): Flow<Loadable<FingerTappingAssessment?>> =
        repository.observe(id).map { Loadable.Loaded(it) }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                AssessmentHistoryViewModel(app.assessmentRepository)
            }
        }
    }
}
