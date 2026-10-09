package com.example.parkinson.speech

/**
 * Every acoustic value the speech module can report. [kind] separates what the recording directly measured
 * from values derived from those measurements, and from values that are not computed at all in this version.
 */
enum class SpeechMetric(val kind: MetricKind) {
    /** Duration of the captured signal (ms). */
    DURATION_MS(MetricKind.MEASURED),
    /** Frames classified as voiced, as a duration (ms). */
    VOICED_DURATION_MS(MetricKind.MEASURED),
    /** Voiced frames / all frames (percent). */
    VOICED_PERCENT(MetricKind.MEASURED),
    /** Median fundamental frequency of the voiced frames (Hz). */
    F0_MEDIAN_HZ(MetricKind.DERIVED),
    /** Standard deviation of the F0 of the voiced frames, in semitones re the median (semitones). */
    F0_SD_SEMITONES(MetricKind.DERIVED),
    /** Median frame level of the voiced frames (dBFS, not calibrated SPL). */
    INTENSITY_MEDIAN_DBFS(MetricKind.MEASURED),
    /** Standard deviation of the frame level of the voiced frames (dB). */
    INTENSITY_SD_DB(MetricKind.DERIVED),
    /** Median harmonic-to-noise ratio of the voiced frames (dB, Boersma autocorrelation estimate). */
    HNR_DB(MetricKind.DERIVED),
    /** Cycle-to-cycle F0 perturbation (percent). Not computed: cycle-level periods are not reliable at 16 kHz here. */
    JITTER_PERCENT(MetricKind.NOT_COMPUTED),
    /** Cycle-to-cycle amplitude perturbation (percent). Not computed: needs reliable cycle extraction. */
    SHIMMER_PERCENT(MetricKind.NOT_COMPUTED),
    /** From the first to the last speech-active frame (ms). */
    SPEECH_SPAN_MS(MetricKind.MEASURED),
    /** Share of the speech-active frames that are voiced (percent). Reading and spontaneous speech. */
    VOICED_OF_ACTIVE_PERCENT(MetricKind.DERIVED),
    /** Pauses of at least the minimum pause length inside the speech span. */
    PAUSE_COUNT(MetricKind.MEASURED),
    /** Mean length of those pauses (ms). */
    PAUSE_MEAN_MS(MetricKind.DERIVED),
    /** Long pauses as a share of the speech span (percent). */
    LONG_PAUSE_RATIO_PERCENT(MetricKind.DERIVED),
    /** Acoustic energy events (peaks), not syllable identities. Repeated-syllable task. */
    EVENT_COUNT(MetricKind.MEASURED),
    /** Acoustic energy events per second (Hz). */
    EVENT_RATE_HZ(MetricKind.DERIVED),
    /** Variability of the intervals between energy events (percent). */
    EVENT_INTERVAL_CV_PERCENT(MetricKind.DERIVED),
    /** Words per minute. Not computed: no reliable transcription is available for Persian in this version. */
    SPEAKING_RATE_WPM(MetricKind.NOT_COMPUTED),
    /** Articulation rate (syllables per second). Not computed for the same reason. */
    ARTICULATION_RATE(MetricKind.NOT_COMPUTED),
}

enum class MetricKind {
    /** Read directly from the signal (durations, counts, levels). */
    MEASURED,

    /** Computed from measured quantities by a documented formula. */
    DERIVED,

    /** Not computed in this version; always reported as unavailable with a reason. */
    NOT_COMPUTED,
}

/** Why a value is unavailable. Used instead of a fake value or zero. */
enum class UnavailableReason {
    /** Too little voiced signal for a stable estimate. */
    NOT_ENOUGH_VOICED_FRAMES,
    /** No speech-active span was found. */
    NO_SPEECH_DETECTED,
    /** Fewer energy events than needed for an interval estimate. */
    NOT_ENOUGH_EVENTS,
    /** The recording failed a quality check that these values depend on. */
    LOW_QUALITY_SIGNAL,
    /** The value needs a transcription, which this version does not provide. */
    NO_TRANSCRIPTION_SUPPORT,
    /** Cycle-level measurement is not reliable with this pipeline. */
    CYCLE_MEASUREMENT_NOT_RELIABLE,
    /** The value does not apply to this task. */
    NOT_APPLICABLE_TO_TASK,
}

/** A reported value: either measured or an explicit reason it is unavailable. Never a placeholder zero. */
sealed interface SpeechValue {
    data class Measured(val value: Double) : SpeechValue
    data class Unavailable(val reason: UnavailableReason) : SpeechValue
}
