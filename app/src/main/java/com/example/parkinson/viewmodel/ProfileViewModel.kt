package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.ProfileRepository
import com.example.parkinson.medication.InMemoryMedicationCheckStore
import com.example.parkinson.medication.MedicationCheck
import com.example.parkinson.medication.MedicationCheckStore
import com.example.parkinson.medication.MedicationState
import com.example.parkinson.profile.ActivePatient
import com.example.parkinson.profile.ProfileForm
import com.example.parkinson.profile.ProfileValidation
import com.example.parkinson.profile.UserProfile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a save or delete of a registration is. */
sealed interface ProfileWrite {
    data object Idle : ProfileWrite
    data object Working : ProfileWrite
    data object Failed : ProfileWrite

    /** Another registered person already has this national ID; nothing was saved. */
    data object DuplicateNationalId : ProfileWrite
}

/** The registered people and who the app is working for now; written by the registration and patient screens. */
class ProfileViewModel(
    private val repository: ProfileRepository,
    private val activePatient: ActivePatient,
    private val medicationChecks: MedicationCheckStore = InMemoryMedicationCheckStore(),
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** Everyone registered on this phone. Loaded eagerly so the start of the app can tell at once who is there. */
    val profiles: StateFlow<Loadable<List<UserProfile>>> = repository.observeAll()
        .map<List<UserProfile>, Loadable<List<UserProfile>>> { Loadable.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Loadable.Loading)

    /** The person the tests are for, or null when nobody is registered or several are and none is chosen. */
    val profile: StateFlow<Loadable<UserProfile?>> = combine(profiles, activePatient.selectedId) { all, selected ->
        when (all) {
            Loadable.Loading -> Loadable.Loading
            is Loadable.Loaded -> Loadable.Loaded(ActivePatient.resolve(all.value, selected))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, Loadable.Loading)

    /** The medication state the active person reported, while it still applies (see [MedicationCheck.VALID_MS]). */
    val medication: StateFlow<MedicationCheck?> = combine(profile, medicationChecks.checks) { p, checks ->
        (p as? Loadable.Loaded)?.value?.let { checks[it.id] }?.takeIf { it.isCurrent(wallClock()) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _write = MutableStateFlow<ProfileWrite>(ProfileWrite.Idle)
    val write: StateFlow<ProfileWrite> = _write.asStateFlow()

    /** Makes [id] the person the tests are for. */
    fun select(id: Int) = activePatient.select(id)

    /**
     * Whether to ask about medication before the tests: the active person takes Parkinson's medication and has no
     * report that still applies.
     */
    fun needsMedicationCheck(): Boolean {
        val person = (profile.value as? Loadable.Loaded)?.value ?: return false
        return person.takesParkinsonMedication == true && medicationChecks.current(person.id, wallClock()) == null
    }

    /** Records the active person's medication state for the tests that follow. */
    fun recordMedication(state: MedicationState) {
        val person = (profile.value as? Loadable.Loaded)?.value ?: return
        medicationChecks.record(MedicationCheck(person.id, state, wallClock()))
    }

    /**
     * Saves a valid form and calls [onSaved]; does nothing while the form has errors. [existing] is the person being
     * edited, or null for a new person, who then becomes the person the tests are for.
     */
    fun save(form: ProfileForm, existing: UserProfile?, onSaved: () -> Unit) {
        val saved = ProfileValidation.toProfile(form, wallClock(), existing) ?: return
        val others = (profiles.value as? Loadable.Loaded)?.value.orEmpty().filter { it.id != saved.id }
        if (others.any { it.nationalId == saved.nationalId }) {
            _write.value = ProfileWrite.DuplicateNationalId
            return
        }
        perform(onSaved) {
            val id = repository.save(saved)
            if (existing == null) activePatient.select(id)
        }
    }

    /** Removes [person] and all of their results. */
    fun delete(person: UserProfile, onDeleted: () -> Unit) = perform(onDeleted) {
        repository.delete(person.id)
        if (activePatient.selectedId.value == person.id) activePatient.select(null)
    }

    fun clearError() {
        if (_write.value == ProfileWrite.Failed || _write.value == ProfileWrite.DuplicateNationalId) {
            _write.value = ProfileWrite.Idle
        }
    }

    private fun perform(onDone: () -> Unit, block: suspend () -> Unit) {
        if (_write.value == ProfileWrite.Working) return
        _write.value = ProfileWrite.Working
        viewModelScope.launch {
            val ok = try {
                withContext(NonCancellable) { block() }
                true
            } catch (e: Exception) {
                false
            }
            _write.value = if (ok) ProfileWrite.Idle else ProfileWrite.Failed
            if (ok) onDone()
        }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                ProfileViewModel(app.profileRepository, app.activePatient, app.medicationChecks)
            }
        }
    }
}
