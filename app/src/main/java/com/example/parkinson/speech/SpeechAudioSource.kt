package com.example.parkinson.speech

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.max

/** Why a recording could not be made. Carried to the UI as a Persian message; never a silent empty result. */
enum class SpeechError {
    /** The person has not accepted the privacy notice and recording consent. Nothing was recorded. */
    CONSENT_NOT_GIVEN,
    MICROPHONE_PERMISSION_DENIED,
    MICROPHONE_UNAVAILABLE,
    RECORDING_INTERRUPTED,
    STORAGE_FAILURE,
    UNEXPECTED,
}

class SpeechCaptureException(val error: SpeechError, cause: Throwable? = null) : Exception(error.name, cause)

/**
 * Source of one in-memory recording. The session depends on this interface, so the analysis and state machine can
 * be tested without a microphone.
 */
interface SpeechAudioSource {
    /**
     * Records [durationMs] of audio in memory. Throws [SpeechCaptureException] on permission, device or
     * interruption failures and [kotlinx.coroutines.CancellationException] when the caller is cancelled.
     */
    suspend fun capture(durationMs: Long, format: SpeechAudioFormat): AudioCapture
}

/**
 * Android microphone capture with [AudioRecord]: 16-bit mono PCM at the requested rate, read on the IO dispatcher.
 * The recorder reports the rate it actually runs at, and that rate is stored with the capture. The recording is
 * never written to disk. The recorder is stopped and released in a finally block on every path, including
 * cancellation.
 *
 * Audio source VOICE_RECOGNITION is used because it applies less processing than VOICE_COMMUNICATION; the
 * processing applied by the device is not known in full and is not corrected here.
 */
class AndroidSpeechAudioSource(private val context: Context) : SpeechAudioSource {

    @SuppressLint("MissingPermission") // The permission is checked explicitly below before the recorder is created.
    override suspend fun capture(durationMs: Long, format: SpeechAudioFormat): AudioCapture = withContext(Dispatchers.IO) {
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) throw SpeechCaptureException(SpeechError.MICROPHONE_PERMISSION_DENIED)

        val rate = format.requestedSampleRateHz
        val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (minBuffer <= 0) throw SpeechCaptureException(SpeechError.MICROPHONE_UNAVAILABLE)

        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                rate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                max(minBuffer, rate / 2 * 2),
            )
        } catch (e: Exception) {
            throw SpeechCaptureException(SpeechError.MICROPHONE_UNAVAILABLE, e)
        }

        try {
            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                throw SpeechCaptureException(SpeechError.MICROPHONE_UNAVAILABLE)
            }
            val total = (rate.toLong() * durationMs / 1000L).toInt()
            val out = ShortArray(total)
            var filled = 0
            try {
                recorder.startRecording()
            } catch (e: IllegalStateException) {
                // Another app holds the microphone, or the device refused it.
                throw SpeechCaptureException(SpeechError.MICROPHONE_UNAVAILABLE, e)
            }
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw SpeechCaptureException(SpeechError.MICROPHONE_UNAVAILABLE)
            }
            val block = ShortArray(rate / 10)
            while (filled < total) {
                currentCoroutineContext().ensureActive()
                val want = minOf(block.size, total - filled)
                val n = recorder.read(block, 0, want)
                when {
                    n < 0 -> throw SpeechCaptureException(SpeechError.RECORDING_INTERRUPTED)
                    n == 0 -> Unit
                    else -> {
                        System.arraycopy(block, 0, out, filled, n)
                        filled += n
                    }
                }
            }
            AudioCapture(
                samples = out.copyOf(filled),
                sampleRateHz = recorder.sampleRate,
                requestedSampleRateHz = rate,
            )
        } finally {
            try {
                if (recorder.recordingState == AudioRecord.RECORDSTATE_RECORDING) recorder.stop()
            } catch (_: IllegalStateException) {
                // Already stopped by the system (interruption); the release below still runs.
            }
            recorder.release()
        }
    }
}
