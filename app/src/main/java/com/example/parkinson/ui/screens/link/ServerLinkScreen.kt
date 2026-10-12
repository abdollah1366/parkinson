package com.example.parkinson.ui.screens.link

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import com.example.parkinson.R
import com.example.parkinson.ui.format.PersianFormat
import com.example.parkinson.ui.format.toPersianDigits
import com.example.parkinson.viewmodel.LinkAction
import com.example.parkinson.viewmodel.ServerLinkState

/**
 * Connects the active person to their doctor with the invite code the doctor gave, or, once connected, shows what is
 * waiting to be sent. Nothing is sent before the person agrees here.
 */
@Composable
fun ServerLinkScreen(
    state: ServerLinkState,
    action: LinkAction,
    lastServerUrl: String?,
    onLink: (serverAddress: String, inviteCode: String) -> Unit,
    onSendNow: () -> Unit,
    onUnlink: () -> Unit,
    onSetWebPassword: (String) -> Unit = {},
) {
    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text(
            stringResource(R.string.link_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() }
        )
        val name = state.profile?.fullName.orEmpty()
        if (state.link == null) {
            LinkForm(name, action, lastServerUrl, onLink)
        } else {
            Linked(state, action, onSendNow, onUnlink, onSetWebPassword)
        }
    }
}

@Composable
private fun LinkForm(name: String, action: LinkAction, lastServerUrl: String?, onLink: (String, String) -> Unit) {
    var server by rememberSaveable { mutableStateOf(lastServerUrl.orEmpty()) }
    var code by rememberSaveable { mutableStateOf("") }
    var agreed by rememberSaveable { mutableStateOf(false) }
    val working = action == LinkAction.Working

    Text(stringResource(R.string.link_intro, name), style = MaterialTheme.typography.bodyLarge)
    OutlinedTextField(
        value = server,
        onValueChange = { server = it },
        label = { Text(stringResource(R.string.link_server)) },
        placeholder = { Text("panel.example.ir") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth()
    )
    OutlinedTextField(
        value = code,
        onValueChange = { code = it },
        label = { Text(stringResource(R.string.link_code)) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, keyboardType = KeyboardType.Ascii),
        modifier = Modifier.fillMaxWidth()
    )
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.link_what_is_sent, name), style = MaterialTheme.typography.bodyMedium)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
                    .toggleable(value = agreed, role = Role.Checkbox, onValueChange = { agreed = it })
            ) {
                Checkbox(checked = agreed, onCheckedChange = null)
                Text(stringResource(R.string.link_consent), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    ActionMessage(action)
    Button(
        onClick = { onLink(server, code) },
        enabled = agreed && server.isNotBlank() && code.isNotBlank() && !working,
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)
    ) {
        Text(stringResource(if (working) R.string.link_working else R.string.link_connect))
    }
}

@Composable
private fun Linked(
    state: ServerLinkState,
    action: LinkAction,
    onSendNow: () -> Unit,
    onUnlink: () -> Unit,
    onSetWebPassword: (String) -> Unit,
) {
    val link = state.link ?: return
    var confirm by rememberSaveable { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                stringResource(R.string.link_connected, state.profile?.fullName.orEmpty(), link.doctorName),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(link.serverUrl, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (state.pending == 0) stringResource(R.string.link_all_sent)
                else stringResource(R.string.link_pending, state.pending.toPersianDigits()),
                style = MaterialTheme.typography.bodyLarge
            )
            link.lastSyncEpochMs?.let {
                Text(stringResource(R.string.link_last_sync, PersianFormat.dateTime(it)), style = MaterialTheme.typography.bodyMedium)
            }
            link.lastError?.let {
                Text(errorText(it), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            }
        }
    }
    ActionMessage(action)
    val busy = action == LinkAction.Working || state.sending
    Button(onClick = onSendNow, enabled = !busy, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(stringResource(if (busy) R.string.link_sending else R.string.link_send_now))
    }
    Text(stringResource(R.string.link_auto), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    WebPanelCard(link.serverUrl, busy, onSetWebPassword)
    TextButton(onClick = { confirm = true }, enabled = !busy) { Text(stringResource(R.string.link_unlink)) }

    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.link_unlink_title)) },
            text = { Text(stringResource(R.string.link_unlink_text, link.doctorName)) },
            confirmButton = {
                TextButton(onClick = { confirm = false; onUnlink() }) { Text(stringResource(R.string.link_unlink_confirm)) }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.link_keep)) } },
        )
    }
}

/** The patient's own web panel: where it is, and a password to log in with (with the national ID). */
@Composable
private fun WebPanelCard(serverUrl: String, busy: Boolean, onSetWebPassword: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    var password by rememberSaveable { mutableStateOf("") }
    var repeat by rememberSaveable { mutableStateOf("") }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        shape = MaterialTheme.shapes.large
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.panel_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.panel_text, serverUrl), style = MaterialTheme.typography.bodyMedium)
            if (!open) {
                TextButton(onClick = { open = true }, enabled = !busy) { Text(stringResource(R.string.panel_set_password)) }
            } else {
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.panel_password)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = { Text(stringResource(R.string.panel_password_repeat)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    isError = repeat.isNotEmpty() && repeat != password,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    onClick = {
                        onSetWebPassword(password)
                        open = false
                        password = ""
                        repeat = ""
                    },
                    enabled = !busy && password.isNotEmpty() && password == repeat,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(R.string.panel_save_password)) }
            }
        }
    }
}

@Composable
private fun ActionMessage(action: LinkAction) {
    when (action) {
        is LinkAction.Failed -> Text(errorText(action.code), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        LinkAction.WebPasswordSet -> Text(stringResource(R.string.panel_password_set), style = MaterialTheme.typography.bodyMedium)
        is LinkAction.Sent -> Text(
            if (action.count == 0) stringResource(R.string.link_sent_none) else stringResource(R.string.link_sent, action.count.toPersianDigits()),
            style = MaterialTheme.typography.bodyMedium
        )
        else -> Unit
    }
}

@Composable
private fun errorText(code: String): String = stringResource(
    when (code) {
        "invalid_address" -> R.string.link_err_address
        "invalid_invite" -> R.string.link_err_invite
        "invite_used" -> R.string.link_err_used
        "invite_expired" -> R.string.link_err_expired
        "network" -> R.string.link_err_network
        "unauthorized" -> R.string.link_err_unauthorized
        "weak_password" -> R.string.panel_err_weak
        else -> R.string.link_err_other
    }
)
