package com.example.parkinson.dualtask

import com.example.parkinson.speech.AudioCapture
import com.example.parkinson.speech.SignalFrames
import com.example.parkinson.speech.SpeechSignalConfig
import com.example.parkinson.speech.VoiceActivityDetector

/** What the microphone heard while the person counted. No audio is kept; only these numbers. */
data class SpokenAnswers(
    /** Bursts of speech separated by a silence: about one per spoken number. */
    val utterances: Int,
    /** Silences of at least [DualTaskReference.LONG_PAUSE_MS] between the first and the last utterance. */
    val longPauses: Int,
    val longestPauseMs: Double?,
)

/**
 * Counts the spoken answers in a recording with the speech test's voice-activity detector. A number such as
 * "هشتاد و چهار" has short gaps between its words; gaps shorter than [mergeGapMs] are joined, so each burst is about
 * one answer. Bursts shorter than [minBurstMs] (a step, a breath) are dropped. This is an estimate: the words are not
 * recognised, so a wrong number counts like a right one.
 */
object SpokenAnswerCounter {

    fun count(
        capture: AudioCapture,
        /** The start of the recording that is ignored: the spoken instruction plays then. */
        skipMs: Double = 0.0,
        mergeGapMs: Double = 450.0,
        minBurstMs: Double = 200.0,
        config: SpeechSignalConfig = SpeechSignalConfig(),
    ): SpokenAnswers? {
        if (capture.samples.isEmpty() || capture.sampleRateHz <= 0) return null
        val x = SignalFrames.toUnit(capture.samples)
        val levels = SignalFrames.frameLevelsDb(x, capture.sampleRateHz, config.frameMs, config.hopMs)
        val active = VoiceActivityDetector.detect(levels, config.hopMs, config).active
        for (i in 0 until minOf(active.size, (skipMs / config.hopMs).toInt())) active[i] = false
        return fromActivity(active, config.hopMs, mergeGapMs, minBurstMs)
    }

    /** The counting on an activity mask (one flag per [hopMs] frame). */
    fun fromActivity(active: BooleanArray, hopMs: Double, mergeGapMs: Double = 450.0, minBurstMs: Double = 200.0): SpokenAnswers {
        val bursts = ArrayList<Pair<Int, Int>>()
        var i = 0
        while (i < active.size) {
            if (!active[i]) { i++; continue }
            var j = i
            while (j + 1 < active.size && active[j + 1]) j++
            val last = bursts.lastOrNull()
            if (last != null && (i - last.second - 1) * hopMs < mergeGapMs) bursts[bursts.size - 1] = last.first to j
            else bursts += i to j
            i = j + 1
        }
        val kept = bursts.filter { (it.second - it.first + 1) * hopMs >= minBurstMs }
        val pauses = kept.zipWithNext { a, b -> (b.first - a.second - 1) * hopMs }
        return SpokenAnswers(
            utterances = kept.size,
            longPauses = pauses.count { it >= DualTaskReference.LONG_PAUSE_MS },
            longestPauseMs = pauses.maxOrNull(),
        )
    }
}
