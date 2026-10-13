package com.example.parkinson.ui.screens.chat

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.parkinson.R
import com.example.parkinson.sync.ChatMessage
import com.example.parkinson.sync.ServerException
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.viewmodel.ChatState
import com.example.parkinson.viewmodel.ChatViewModel
import kotlinx.coroutines.delay

/** Text and voice messages between the active person and their doctor. */
@Composable
fun ChatScreen(viewModel: ChatViewModel, state: ChatState) {
    LaunchedEffect(viewModel) { viewModel.watchChat() }
    DisposableEffect(viewModel) { onDispose { viewModel.leave() } }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).imePadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            state.doctor?.let { stringResource(R.string.chat_title_with, it.doctorName) } ?: stringResource(R.string.chat_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        val conversations = state.conversations
        if (conversations != null && conversations.size > 1) {
            Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                conversations.forEach { c ->
                    FilterChip(
                        selected = c.doctorId == state.doctorId,
                        onClick = { viewModel.selectDoctor(c.doctorId) },
                        label = { Text(if (c.unread > 0) "${c.doctorName} (${c.unread.toPersianDigits()})" else c.doctorName) },
                    )
                }
            }
        }
        state.error?.let {
            Text(
                errorText(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite }
            )
        }
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            when {
                conversations == null -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                conversations.isEmpty() -> Text(stringResource(R.string.chat_no_doctor), modifier = Modifier.align(Alignment.Center))
                state.messages.isEmpty() -> Text(
                    stringResource(R.string.chat_empty),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> MessageList(state, viewModel::play)
            }
        }
        if (state.doctorId != null) Composer(viewModel, state)
    }
}

@Composable
private fun MessageList(state: ChatState, onPlay: (ChatMessage) -> Unit) {
    val listState = rememberLazyListState()
    LaunchedEffect(state.messages.size) { if (state.messages.isNotEmpty()) listState.animateScrollToItem(state.messages.size - 1) }
    LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        items(state.messages, key = { it.id }) { m -> Bubble(m, playing = state.playingId == m.id, onPlay = { onPlay(m) }) }
    }
}

@Composable
private fun Bubble(m: ChatMessage, playing: Boolean, onPlay: () -> Unit) {
    val mine = !m.fromDoctor
    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterStart else Alignment.CenterEnd) {
        Surface(
            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
            shape = MaterialTheme.shapes.large,
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (!mine) Text(stringResource(R.string.chat_from_doctor), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                if (m.text != null) {
                    Text(m.text, style = MaterialTheme.typography.bodyLarge)
                } else {
                    OutlinedButton(onClick = onPlay) {
                        Text(
                            stringResource(
                                if (playing) R.string.chat_voice_stop else R.string.chat_voice_play,
                                duration(m.audioMs ?: 0),
                            )
                        )
                    }
                }
                Text(
                    PersianFormat.dateTime(m.createdAtEpochMs) +
                        if (mine && m.readAtEpochMs != null) " — " + stringResource(R.string.chat_read) else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun Composer(viewModel: ChatViewModel, state: ChatState) {
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) viewModel.startRecording()
    }

    if (state.recording) {
        var startedAt by remember { mutableLongStateOf(System.currentTimeMillis()) }
        var elapsed by remember { mutableLongStateOf(0L) }
        LaunchedEffect(Unit) {
            startedAt = System.currentTimeMillis()
            while (true) {
                elapsed = System.currentTimeMillis() - startedAt
                delay(500)
            }
        }
        Text(
            stringResource(R.string.chat_recording, duration(elapsed.toInt())),
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { viewModel.stopRecording(send = true) }, modifier = Modifier.weight(1f)) {
                Text(stringResource(R.string.chat_record_send))
            }
            OutlinedButton(onClick = { viewModel.stopRecording(send = false) }) { Text(stringResource(R.string.chat_record_cancel)) }
        }
        return
    }

    OutlinedTextField(
        value = text,
        onValueChange = { if (it.length <= ChatViewModel.MAX_TEXT) text = it },
        label = { Text(stringResource(R.string.chat_hint)) },
        maxLines = 4,
        modifier = Modifier.fillMaxWidth()
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { viewModel.sendText(text) { text = "" } },
            enabled = text.isNotBlank() && !state.sending,
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(if (state.sending) R.string.chat_sending else R.string.chat_send)) }
        OutlinedButton(
            onClick = {
                val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED
                if (granted) viewModel.startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO)
            },
            enabled = !state.sending,
        ) { Text(stringResource(R.string.chat_record)) }
    }
}

/** m:ss in Persian digits. */
private fun duration(ms: Int): String {
    val s = ms / 1000
    return PersianFormat.digits("%d:%02d".format(s / 60, s % 60))
}

@Composable
private fun errorText(code: String): String = stringResource(
    when (code) {
        ServerException.NETWORK -> R.string.link_err_network
        ServerException.UNAUTHORIZED -> R.string.link_err_unauthorized
        ChatViewModel.MIC_ERROR -> R.string.chat_err_mic
        "too_large" -> R.string.chat_err_too_large
        "invalid_voice" -> R.string.chat_err_voice
        "rate_limited", "http_429" -> R.string.chat_err_rate
        else -> R.string.link_err_other
    }
)
