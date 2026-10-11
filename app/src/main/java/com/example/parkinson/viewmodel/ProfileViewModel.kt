package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.ProfileRepository
import com.example.parkinson.profile.ProfileForm
import com.example.parkinson.profile.ProfileValidation
import com.example.parkinson.profile.UserProfile
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Where a save or delete of the registration is. */
sealed interface ProfileWrite {
    data object Idle : ProfileWrite
    data object Working : ProfileWrite
    data object Failed : ProfileWrite
}

/** The registration: read for Home and the start of the app, written by the registration screen. */
class ProfileViewModel(
    private val repository: ProfileRepository,
    private val wallClock: () -> Long = System::currentTimeMillis,
) : ViewModel() {

    /** Loaded eagerly so the start of the app can tell at once whether anyone is registered. */
    val profile: StateFlow<Loadable<UserProfile?>> = repository.observe()
        .map<UserProfile?, Loadable<UserProfile?>> { Loadable.Loaded(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, Loadable.Loading)

    private val _write = MutableStateFlow<ProfileWrite>(ProfileWrite.Idle)
    val write: StateFlow<ProfileWrite> = _write.asStateFlow()

    /** Saves a valid form and calls [onSaved]; does nothing while the form has errors. */
    fun save(form: ProfileForm, onSaved: () -> Unit) {
        val previous = (profile.value as? Loadable.Loaded)?.value
        val saved = ProfileValidation.toProfile(form, wallClock(), previous) ?: return
        perform(onSaved) { repository.save(saved) }
    }

    fun delete(onDeleted: () -> Unit) = perform(onDeleted) { repository.delete() }

    fun clearError() {
        if (_write.value == ProfileWrite.Failed) _write.value = ProfileWrite.Idle
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
                ProfileViewModel(app.profileRepository)
            }
        }
    }
}
