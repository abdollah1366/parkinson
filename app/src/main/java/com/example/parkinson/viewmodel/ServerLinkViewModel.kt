package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.data.StoredResult
import com.example.parkinson.profile.UserProfile
import com.example.parkinson.sync.ResultSync
import com.example.parkinson.sync.ServerAddress
import com.example.parkinson.sync.ServerException
import com.example.parkinson.sync.ServerLink
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The active person's connection to a doctor, for Home and the link screen. */
data class ServerLinkState(
    val profile: UserProfile?,
    val link: ServerLink?,
    /** Results of this person the server does not have yet. */
    val pending: Int,
    val sending: Boolean,
)

/** A link, send or unlink the person asked for. */
sealed interface LinkAction {
    data object Idle : LinkAction
    data object Working : LinkAction

    /** [code] is a server error code, "network", or "invalid_address". */
    data class Failed(val code: String) : LinkAction

    /** A send finished; [count] results went out. */
    data class Sent(val count: Int) : LinkAction
}

class ServerLinkViewModel(
    private val sync: ResultSync,
    private val activeProfile: Flow<UserProfile?>,
    private val history: Flow<List<StoredResult>>,
) : ViewModel() {

    val state: StateFlow<ServerLinkState?> =
        combine(activeProfile, history, sync.links, sync.running) { profile, results, links, running ->
            ServerLinkState(
                profile = profile,
                link = profile?.let { links[it.id] },
                pending = profile?.let { sync.pendingCount(it.id, results) } ?: 0,
                sending = profile != null && profile.id in running,
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _action = MutableStateFlow<LinkAction>(LinkAction.Idle)
    val action: StateFlow<LinkAction> = _action.asStateFlow()

    /** The address typed last on this phone, to prefill the form. */
    val lastServerUrl: String? get() = sync.lastServerUrl

    /** Connects the active person with the doctor's [inviteCode], then sends their results. */
    fun link(serverAddress: String, inviteCode: String) {
        val url = ServerAddress.normalize(serverAddress) ?: run {
            _action.value = LinkAction.Failed(INVALID_ADDRESS)
            return
        }
        perform {
            val profile = activeProfile.first() ?: return@perform LinkAction.Idle
            sync.link(url, inviteCode.trim(), profile)
            // Linked: a failed first send is not a failed link; it is shown on the screen and retried later.
            LinkAction.Sent(runCatching { sync.sync(profile, history.first()) }.getOrDefault(0))
        }
    }

    fun sendNow() = perform {
        val profile = activeProfile.first() ?: return@perform LinkAction.Idle
        LinkAction.Sent(sync.sync(profile, history.first()))
    }

    fun unlink() = perform {
        activeProfile.first()?.let { sync.unlink(it.id) }
        LinkAction.Idle
    }

    fun clearAction() {
        if (_action.value != LinkAction.Working) _action.value = LinkAction.Idle
    }

    private fun perform(block: suspend () -> LinkAction) {
        if (_action.value == LinkAction.Working) return
        _action.value = LinkAction.Working
        viewModelScope.launch {
            _action.value = try {
                block()
            } catch (e: ServerException) {
                LinkAction.Failed(e.code)
            } catch (e: Exception) {
                LinkAction.Failed("error")
            }
        }
    }

    companion object {
        const val INVALID_ADDRESS = "invalid_address"

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                ServerLinkViewModel(app.resultSync, app.activePatient.profile, app.assessmentRepository.observeStoredHistory())
            }
        }
    }
}
