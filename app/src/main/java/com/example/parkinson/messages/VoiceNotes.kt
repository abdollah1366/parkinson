package com.example.parkinson.messages

import android.content.Context
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File

/** Records one voice message at a time as a small .m4a (AAC) file, the format the server and browsers accept. */
interface VoiceRecorder {
    /** Starts recording; false when the microphone could not be opened. [onMaxReached] runs at the length limit. */
    fun start(onMaxReached: () -> Unit): Boolean

    /** Stops and returns the recording and its length, or null when nothing usable was recorded. */
    fun stop(): Pair<ByteArray, Int>?

    /** Stops and throws the recording away. */
    fun cancel()
}

class AndroidVoiceRecorder(private val context: Context) : VoiceRecorder {
    private var recorder: MediaRecorder? = null
    private var startedAt = 0L
    private val file get() = File(context.cacheDir, "voice-message.m4a")

    override fun start(onMaxReached: () -> Unit): Boolean {
        cancel()
        @Suppress("DEPRECATION")
        val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(context) else MediaRecorder()
        return try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(22_050)
            r.setAudioEncodingBitRate(32_000)
            r.setMaxDuration(MAX_MS)
            r.setOnInfoListener { _, what, _ -> if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) onMaxReached() }
            r.setOutputFile(file.absolutePath)
            r.prepare()
            r.start()
            recorder = r
            startedAt = SystemClock.elapsedRealtime()
            true
        } catch (e: Exception) {
            r.release()
            false
        }
    }

    override fun stop(): Pair<ByteArray, Int>? {
        val r = recorder ?: return null
        recorder = null
        val ms = (SystemClock.elapsedRealtime() - startedAt).toInt().coerceAtMost(MAX_MS)
        val ok = runCatching { r.stop() }.isSuccess // Throws when stopped right after starting (no sound yet).
        r.release()
        val bytes = if (ok && ms >= MIN_MS) runCatching { file.readBytes() }.getOrNull() else null
        file.delete()
        return bytes?.takeIf { it.isNotEmpty() }?.let { it to ms }
    }

    override fun cancel() {
        recorder?.let { r -> runCatching { r.stop() }; r.release() }
        recorder = null
        file.delete()
    }

    companion object {
        /** Same limit as the server (3 minutes). */
        const val MAX_MS = 3 * 60 * 1000
        const val MIN_MS = 700
    }
}

/** Plays one voice message at a time from its downloaded bytes. */
interface VoicePlayer {
    /** Plays [audio]; [onDone] runs when it ends or fails. */
    fun play(audio: ByteArray, onDone: () -> Unit)

    fun stop()
}

class AndroidVoicePlayer(private val context: Context) : VoicePlayer {
    private var player: MediaPlayer? = null
    private val file get() = File(context.cacheDir, "voice-playing.m4a")

    override fun play(audio: ByteArray, onDone: () -> Unit) {
        stop()
        try {
            file.writeBytes(audio)
            val p = MediaPlayer()
            player = p
            p.setDataSource(file.absolutePath)
            p.setOnCompletionListener { stop(); onDone() }
            p.setOnErrorListener { _, _, _ -> stop(); onDone(); true }
            p.prepare()
            p.start()
        } catch (e: Exception) {
            stop()
            onDone()
        }
    }

    override fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }
}
