package com.example.parkinson.speech

/** A stored speech result with the most recent earlier result of the same task, for the result screen. */
data class SpeechComparison(
    val current: SpeechResult,
    val previous: SpeechResult?,
)

/**
 * The most recent result strictly earlier than [current] for the same task. Tasks are never compared with each
 * other. Differences between sessions describe the recordings only; they are not evidence of treatment efficacy.
 */
fun previousSpeech(current: SpeechResult, all: List<SpeechResult>): SpeechResult? =
    all.filter {
        it.assessmentId != current.assessmentId && it.task == current.task && it.timestampEpochMs < current.timestampEpochMs
    }.maxByOrNull { it.timestampEpochMs }
