package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.assessment.AssessmentResult
import com.example.parkinson.data.AssessmentRepository
import com.example.parkinson.gait.GaitResult
import com.example.parkinson.imu.ImuComparison
import com.example.parkinson.imu.ImuGaitResult
import com.example.parkinson.imu.ImuSitToStandResult
import com.example.parkinson.imu.previousImuGait
import com.example.parkinson.imu.previousImuSitToStand
import com.example.parkinson.openclose.HandOpenCloseResult
import com.example.parkinson.speech.SpeechComparison
import com.example.parkinson.speech.previousSpeech
import com.example.parkinson.sts.SitToStandComparison
import com.example.parkinson.sts.previousSitToStand
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.tapping.result.FingerTappingAssessment
import com.example.parkinson.tremor.RestingTremorResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/** Loading state of stored data, so the UI can tell "loading" from "empty". */
sealed interface Loadable<out T> {
    data object Loading : Loadable<Nothing>
    data class Loaded<T>(val value: T) : Loadable<T>
}

/** Read access to stored assessments (all types) for Home, History and the result screens. */
class AssessmentHistoryViewModel(private val repository: AssessmentRepository) : ViewModel() {

    val all: StateFlow<Loadable<List<AssessmentResult>>> = repository.observeHistory()
        .map<List<AssessmentResult>, Loadable<List<AssessmentResult>>> { Loadable.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable.Loading)

    val latest: StateFlow<Loadable<AssessmentResult?>> = repository.observeHistory()
        .map<List<AssessmentResult>, Loadable<AssessmentResult?>> { Loadable.Loaded(it.firstOrNull()) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), Loadable.Loading)

    fun assessment(id: String): Flow<Loadable<FingerTappingAssessment?>> =
        repository.observe(id).map { Loadable.Loaded(it) }

    fun handStability(id: String): Flow<Loadable<HandStabilityResult?>> =
        repository.observeHandStability(id).map { Loadable.Loaded(it) }

    fun pronationSupination(id: String): Flow<Loadable<PronationSupinationResult?>> =
        repository.observePronationSupination(id).map { Loadable.Loaded(it) }

    fun handOpenClose(id: String): Flow<Loadable<HandOpenCloseResult?>> =
        repository.observeHandOpenClose(id).map { Loadable.Loaded(it) }

    fun restingTremor(id: String): Flow<Loadable<RestingTremorResult?>> =
        repository.observeRestingTremor(id).map { Loadable.Loaded(it) }

    fun gait(id: String): Flow<Loadable<GaitResult?>> =
        repository.observeGait(id).map { Loadable.Loaded(it) }

    /** A speech result with the most recent earlier result of the same task, for the comparison section. */
    fun speech(id: String): Flow<Loadable<SpeechComparison?>> =
        combine(repository.observeSpeech(id), repository.observeAllSpeech()) { current, all ->
            current?.let { SpeechComparison(it, previousSpeech(it, all)) }
        }.map { Loadable.Loaded(it) }

    fun imuSitToStand(id: String): Flow<Loadable<ImuComparison<ImuSitToStandResult>?>> =
        combine(repository.observeImuSitToStand(id), repository.observeAllImuSitToStand()) { current, all ->
            current?.let { ImuComparison(it, previousImuSitToStand(it, all)) }
        }.map { Loadable.Loaded(it) }

    fun imuGait(id: String): Flow<Loadable<ImuComparison<ImuGaitResult>?>> =
        combine(repository.observeImuGait(id), repository.observeAllImuGait()) { current, all ->
            current?.let { ImuComparison(it, previousImuGait(it, all)) }
        }.map { Loadable.Loaded(it) }

    /** A Sit-to-Stand result with the most recent earlier attempt, for the comparison section. */
    fun sitToStand(id: String): Flow<Loadable<SitToStandComparison?>> =
        combine(repository.observeSitToStand(id), repository.observeAllSitToStand()) { current, all ->
            current?.let { SitToStandComparison(it, previousSitToStand(it, all)) }
        }.map { Loadable.Loaded(it) }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                AssessmentHistoryViewModel(app.assessmentRepository)
            }
        }
    }
}
