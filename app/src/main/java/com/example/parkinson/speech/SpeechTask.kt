package com.example.parkinson.speech

/**
 * The speech tasks. Each task is a protocol: its planned duration, its analysis set and its identifier, which is
 * stored with the result. Tasks are never scored with each other's rules. [standardized] marks the tasks whose
 * wording is fixed for comparison between sessions; spontaneous speech is not standardized.
 */
enum class SpeechTask(
    val id: String,
    val plannedDurationMs: Long,
    val standardized: Boolean,
) {
    /** Sustained /a/ for about 5 s, after a comfortable breath. */
    SUSTAINED_VOWEL("sustained_vowel", 5_000L, standardized = true),

    /** Repetition of a clinician-selected syllable sequence, for about 8 s. */
    REPEATED_SYLLABLE("repeated_syllable", 8_000L, standardized = true),

    /** Reading of a short fixed passage, for at most about 30 s. */
    READING("reading", 30_000L, standardized = true),

    /** Free description of a familiar topic, 20 to 30 s. Optional and not standardized. */
    SPONTANEOUS("spontaneous", 25_000L, standardized = false);

    companion object {
        fun fromId(id: String?): SpeechTask? = entries.firstOrNull { it.id == id }
    }
}
