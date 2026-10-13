package com.example.parkinson.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.parkinson.ParkinsonApplication
import com.example.parkinson.messages.AndroidVoicePlayer
import com.example.parkinson.messages.AndroidVoiceRecorder
import com.example.parkinson.messages.VoicePlayer
import com.example.parkinson.messages.VoiceRecorder
import com.example.parkinson.profile.UserProfile
import com.example.parkinson.sync.ChatMessage
import com.example.parkinson.sync.Conversation
import com.example.parkinson.sync.MessagesApi
import com.example.parkinson.sync.ServerException
import com.example.parkinson.sync.ServerLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The active person's messages with their doctor, for the chat screen and the badge on Home. */
data class ChatState(
    /** Null until the doctors are loaded. */
    val conversations: List<Conversation>? = null,
    val doctorId: Long? = null,
    val messages: List<ChatMessage> = emptyList(),
    val sending: Boolean = false,
    /** A voice message is being recorded. */
    val recording: Boolean = false,
    /** The voice message being played. */
    val playingId: Long? = null,
    /** Server error code of the last failure ("network", "unauthorized", "mic", ...); null after a success. */
    val error: String? = null,
) {
    val doctor: Conversation? get() = conversations?.firstOrNull { it.doctorId == doctorId }
}

class ChatViewModel(
    private val api: MessagesApi,
    private val links: StateFlow<Map<Int, ServerLink>>,
    private val activeProfile: Flow<UserProfile?>,
    private val recorder: VoiceRecorder,
    private val player: VoicePlayer,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    /** Unread messages of the active person from all their doctors, for Home. */
    private val _unread = MutableStateFlow(0)
    val unread: StateFlow<Int> = _unread.asStateFlow()

    private var linkedProfileId: Int? = null

    private suspend fun currentLink(): ServerLink? {
        val profile = activeProfile.first()
        val link = profile?.let { links.value[it.id] }
        if (profile?.id != linkedProfileId) {
            // Another person was chosen: never show one person's messages to another.
            linkedProfileId = profile?.id
            _state.value = ChatState()
        }
        return link
    }

    /** Refreshes the Home badge every [periodMs] while Home is shown and the person is connected. */
    suspend fun watchUnread(periodMs: Long = UNREAD_PERIOD_MS) {
        while (true) {
            val link = currentLink()
            _unread.value = if (link == null) 0 else try {
                api.conversations(link.serverUrl, link.token).sumOf { it.unread }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _unread.value // Never crash Home over the badge; it is tried again.
            }
            delay(periodMs)
        }
    }

    /** Loads the conversation, then fetches it again every [periodMs] while the chat screen is shown. */
    suspend fun watchChat(periodMs: Long = CHAT_PERIOD_MS) {
        while (true) {
            refresh()
            delay(periodMs)
        }
    }

    /** Opens the conversation with another of the person's doctors. */
    fun selectDoctor(doctorId: Long) {
        if (doctorId == _state.value.doctorId) return
        player.stop()
        _state.update { it.copy(doctorId = doctorId, messages = emptyList(), playingId = null) }
        viewModelScope.launch { refresh() }
    }

    private suspend fun refresh() {
        val link = currentLink() ?: return
        try {
            val conversations = api.conversations(link.serverUrl, link.token)
            val doctorId = _state.value.doctorId?.takeIf { id -> conversations.any { it.doctorId == id } }
                ?: conversations.firstOrNull { it.unread > 0 }?.doctorId
                ?: conversations.firstOrNull()?.doctorId
            // The whole conversation each time (text only, the sound stays on the server), so "read" marks update too.
            val messages = if (doctorId == null) emptyList() else api.messages(link.serverUrl, link.token, doctorId)
            val unreadHere = conversations.firstOrNull { it.doctorId == doctorId }?.unread ?: 0
            if (doctorId != null && unreadHere > 0) api.markRead(link.serverUrl, link.token, doctorId)
            val shown = conversations.map { if (it.doctorId == doctorId) it.copy(unread = 0) else it }
            _unread.value = shown.sumOf { it.unread }
            _state.update {
                // Keep a message sent while this was loading.
                val sentMeanwhile = if (it.doctorId == doctorId) it.messages.filter { m -> m.id > (messages.lastOrNull()?.id ?: 0) } else emptyList()
                it.copy(conversations = shown, doctorId = doctorId, messages = merge(messages, sentMeanwhile), error = null)
            }
        } catch (e: ServerException) {
            _state.update { it.copy(conversations = it.conversations ?: emptyList(), error = e.code) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(conversations = it.conversations ?: emptyList(), error = "error") }
        }
    }

    fun sendText(text: String, onSent: () -> Unit) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed.length > MAX_TEXT) return
        send { link, doctorId -> api.sendText(link.serverUrl, link.token, doctorId, trimmed).also { onSent() } }
    }

    /** Starts recording; returns false (and shows why) when the microphone could not be opened. */
    fun startRecording(): Boolean {
        if (_state.value.recording || _state.value.doctorId == null) return false
        player.stop()
        val started = recorder.start(onMaxReached = { stopRecording(send = true) })
        _state.update { it.copy(recording = started, playingId = null, error = if (started) null else MIC_ERROR) }
        return started
    }

    /** Stops recording and sends it, or throws it away when [send] is false. */
    fun stopRecording(send: Boolean) {
        if (!_state.value.recording) return
        _state.update { it.copy(recording = false) }
        if (!send) return recorder.cancel()
        val (audio, ms) = recorder.stop() ?: return
        send { link, doctorId -> api.sendVoice(link.serverUrl, link.token, doctorId, audio, ms) }
    }

    fun play(message: ChatMessage) {
        if (_state.value.playingId == message.id) {
            player.stop()
            _state.update { it.copy(playingId = null) }
            return
        }
        val doctorId = _state.value.doctorId ?: return
        _state.update { it.copy(playingId = message.id) }
        viewModelScope.launch {
            val link = currentLink() ?: return@launch
            try {
                val audio = api.audio(link.serverUrl, link.token, doctorId, message.id)
                if (_state.value.playingId != message.id) return@launch
                player.play(audio) { _state.update { if (it.playingId == message.id) it.copy(playingId = null) else it } }
            } catch (e: ServerException) {
                _state.update { it.copy(playingId = null, error = e.code) }
            }
        }
    }

    private fun send(block: suspend (ServerLink, Long) -> ChatMessage) {
        if (_state.value.sending) return
        val doctorId = _state.value.doctorId ?: return
        _state.update { it.copy(sending = true, error = null) }
        viewModelScope.launch {
            try {
                val link = currentLink() ?: return@launch
                val sent = block(link, doctorId)
                _state.update { it.copy(messages = if (it.doctorId == doctorId) merge(it.messages, listOf(sent)) else it.messages) }
            } catch (e: ServerException) {
                _state.update { it.copy(error = e.code) }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _state.update { it.copy(sending = false) }
            }
        }
    }

    /** Stops sound and recording when the chat screen is left. */
    fun leave() {
        stopRecording(send = false)
        player.stop()
        _state.update { it.copy(playingId = null) }
    }

    override fun onCleared() {
        recorder.cancel()
        player.stop()
    }

    companion object {
        const val MAX_TEXT = 4000
        const val MIC_ERROR = "mic"
        const val CHAT_PERIOD_MS = 10_000L
        const val UNREAD_PERIOD_MS = 60_000L

        /** [known] and [newer] by id, without repeats, oldest first. */
        fun merge(known: List<ChatMessage>, newer: List<ChatMessage>): List<ChatMessage> =
            (known + newer).associateBy { it.id }.values.sortedBy { it.id }

        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as ParkinsonApplication
                ChatViewModel(
                    app.messagesApi, app.resultSync.links, app.activePatient.profile,
                    AndroidVoiceRecorder(app), AndroidVoicePlayer(app),
                )
            }
        }
    }
}
