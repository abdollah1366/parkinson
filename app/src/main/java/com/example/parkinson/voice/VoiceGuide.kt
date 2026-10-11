package com.example.parkinson.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import androidx.annotation.RawRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.example.parkinson.R

@RawRes
private fun VoiceClip.raw(): Int = when (this) {
    VoiceClip.COUNT_1 -> R.raw.voice_count_1
    VoiceClip.COUNT_2 -> R.raw.voice_count_2
    VoiceClip.COUNT_3 -> R.raw.voice_count_3
    VoiceClip.COUNT_4 -> R.raw.voice_count_4
    VoiceClip.COUNT_5 -> R.raw.voice_count_5
    VoiceClip.DONE -> R.raw.voice_done
    VoiceClip.STAY_STILL -> R.raw.voice_stay_still
    VoiceClip.PAUSED -> R.raw.voice_paused
    VoiceClip.GAIT_STAND -> R.raw.voice_gait_stand
    VoiceClip.GAIT_WALK -> R.raw.voice_gait_walk
    VoiceClip.STS_SIT -> R.raw.voice_sts_sit
    VoiceClip.STS_GO -> R.raw.voice_sts_go
    VoiceClip.HAND_HOLD -> R.raw.voice_hand_hold
    VoiceClip.HAND_REST -> R.raw.voice_hand_rest
    VoiceClip.TAP -> R.raw.voice_tap
    VoiceClip.OPEN_CLOSE -> R.raw.voice_open_close
    VoiceClip.ROTATE -> R.raw.voice_rotate
    VoiceClip.DUAL_START_99 -> R.raw.voice_dual_start_99
    VoiceClip.DUAL_START_98 -> R.raw.voice_dual_start_98
    VoiceClip.DUAL_START_96 -> R.raw.voice_dual_start_96
    VoiceClip.DUAL_START_94 -> R.raw.voice_dual_start_94
    VoiceClip.DUAL_START_92 -> R.raw.voice_dual_start_92
    VoiceClip.DUAL_START_91 -> R.raw.voice_dual_start_91
    VoiceClip.DUAL_STOP -> R.raw.voice_dual_stop
}

/**
 * Plays the short voice clips. One clip at a time: a new clip stops the one still playing. Playback problems are
 * logged and ignored; the guide never interrupts a test.
 */
class VoiceGuide(context: Context) {
    private val appContext = context.applicationContext
    private var player: MediaPlayer? = null

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    fun play(clip: VoiceClip) {
        stop()
        try {
            val afd = appContext.resources.openRawResourceFd(clip.raw()) ?: return
            val next = MediaPlayer()
            afd.use { next.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            next.setAudioAttributes(attributes)
            next.setOnCompletionListener { finished ->
                finished.release()
                if (player === finished) player = null
            }
            next.prepare()
            next.start()
            player = next
        } catch (e: Exception) {
            Log.w(TAG, "voice clip $clip not played: ${e.javaClass.simpleName}")
        }
    }

    fun stop() {
        player?.let {
            try {
                it.stop()
            } catch (_: IllegalStateException) {
            }
            it.release()
        }
        player = null
    }

    private companion object {
        const val TAG = "VoiceGuide"
    }
}

/** A [VoiceGuide] tied to the composition; released when the screen leaves. */
@Composable
fun rememberVoiceGuide(): VoiceGuide {
    val context = LocalContext.current
    val guide = remember { VoiceGuide(context) }
    DisposableEffect(guide) { onDispose { guide.stop() } }
    return guide
}

/** Speaks [cue] once each time its key changes. */
@Composable
fun SpeakCue(guide: VoiceGuide, cue: VoiceCue?) {
    LaunchedEffect(cue?.key) { cue?.let { guide.play(it.clip) } }
}
