package com.example.parkinson.ui.screens.call

import android.media.RingtoneManager
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.example.parkinson.R
import com.example.parkinson.sync.IncomingCall

/** A doctor is calling: answer or turn down, with the phone's ringtone while it shows. */
@Composable
fun IncomingCallDialog(call: IncomingCall, onAnswer: () -> Unit, onDecline: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(call.callId) {
        val ringtone = runCatching {
            RingtoneManager.getRingtone(context, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))?.apply { play() }
        }.getOrNull()
        onDispose { runCatching { ringtone?.stop() } }
    }
    AlertDialog(
        onDismissRequest = {},
        title = {
            Text(stringResource(if (call.video) R.string.call_incoming_video else R.string.call_incoming_voice, call.doctorName))
        },
        confirmButton = { Button(onClick = onAnswer) { Text(stringResource(R.string.call_answer)) } },
        dismissButton = { TextButton(onClick = onDecline) { Text(stringResource(R.string.call_decline)) } },
    )
}
