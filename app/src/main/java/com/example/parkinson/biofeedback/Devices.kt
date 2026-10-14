package com.example.parkinson.biofeedback

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.SystemClock
import android.util.Log

/**
 * The microphone's level, live: one value (dBFS) every 50 ms on a background thread. Nothing is recorded or kept; the
 * caller must hold the RECORD_AUDIO permission.
 */
class LiveMic {
    @Volatile private var running = false
    private var thread: Thread? = null

    @SuppressLint("MissingPermission")
    fun start(onLevel: (timeMs: Long, db: Double) -> Unit): Boolean {
        stop()
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) return false
        val record = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, FRAME * 4))
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }
        try {
            record.startRecording()
        } catch (e: Exception) {
            record.release()
            return false
        }
        running = true
        thread = Thread({
            val frame = ShortArray(FRAME)
            try {
                while (running) {
                    var filled = 0
                    while (running && filled < FRAME) {
                        val n = record.read(frame, filled, FRAME - filled)
                        if (n <= 0) break
                        filled += n
                    }
                    if (filled < FRAME) break
                    onLevel(SystemClock.elapsedRealtime(), LoudVoiceEngine.levelDb(frame, filled))
                }
            } catch (e: Exception) {
                Log.w("LiveMic", "stopped: ${e.javaClass.simpleName}")
            } finally {
                runCatching { record.stop() }
                record.release()
            }
        }, "biofeedback-mic").apply { start() }
        return true
    }

    fun stop() {
        running = false
        // Never wait for itself: the level callback may be the one stopping.
        thread?.takeIf { it !== Thread.currentThread() }?.join(500)
        thread = null
    }

    private companion object {
        const val RATE = 16_000
        const val FRAME = RATE / 20 // 50 ms.
    }
}

/** Short beeps: the metronome's beat and a different note for each memory cell. */
class Tones {
    private val generator: ToneGenerator? = try {
        ToneGenerator(AudioManager.STREAM_MUSIC, 90)
    } catch (e: RuntimeException) {
        null // No audio output: the exercise still runs, with the visual beat.
    }

    fun beat() {
        generator?.startTone(ToneGenerator.TONE_PROP_BEEP, 80)
    }

    fun cell(index: Int) {
        generator?.startTone(CELL_TONES[index.coerceIn(0, CELL_TONES.size - 1)], 250)
    }

    fun release() {
        generator?.release()
    }

    private companion object {
        val CELL_TONES = intArrayOf(
            ToneGenerator.TONE_DTMF_1, ToneGenerator.TONE_DTMF_2, ToneGenerator.TONE_DTMF_3,
            ToneGenerator.TONE_DTMF_4, ToneGenerator.TONE_DTMF_5, ToneGenerator.TONE_DTMF_6,
            ToneGenerator.TONE_DTMF_7, ToneGenerator.TONE_DTMF_8, ToneGenerator.TONE_DTMF_9,
        )
    }
}
