package com.example.parkinson.speech

import kotlin.math.log2

/** Output of one task analysis. [metrics] has an entry for every [SpeechMetric]. */
data class SpeechAnalysis(
    val task: SpeechTask,
    val quality: SpeechQualityReport,
    val metrics: Map<SpeechMetric, SpeechValue>,
    val pauses: PauseSummary?,
    val events: EventSummary?,
) {
    /** The measured value, or null when the metric is unavailable. Never zero for an unavailable metric. */
    fun measured(metric: SpeechMetric): Double? = (metrics[metric] as? SpeechValue.Measured)?.value
}

/**
 * Runs the acoustic pipeline for one task: integrity and quality, activity, pitch, pauses or energy events, then
 * the metric set of the task. Stages call [checkCancelled] between them so a cancelled session stops early.
 *
 * Every metric the task does not use is reported as NOT_APPLICABLE, and every metric that could not be measured
 * carries its reason. A low-quality recording reports its measurable metrics as LOW_QUALITY_SIGNAL and no values.
 */
class SpeechTaskEngine(
    private val signal: SpeechSignalConfig = SpeechSignalConfig(),
    private val quality: SpeechQualityThresholds = SpeechQualityThresholds(),
    private val format: SpeechAudioFormat = SpeechAudioFormat(),
    private val checkCancelled: () -> Unit = {},
) {

    fun analyze(task: SpeechTask, capture: AudioCapture, plannedDurationMs: Long = task.plannedDurationMs): SpeechAnalysis {
        checkCancelled()
        val x = SignalFrames.toUnit(capture.samples)
        val levels = SignalFrames.frameLevelsDb(x, capture.sampleRateHz, signal.frameMs, signal.hopMs)
        val activity = VoiceActivityDetector.detect(levels, signal.hopMs, signal)
        val report = AudioQualityAnalyzer.analyze(capture, plannedDurationMs, activity, format, quality)
        checkCancelled()

        if (!report.isUsable) {
            return SpeechAnalysis(task, report, unusable(task), pauses = null, events = null)
        }

        val pitch = PitchEstimator(signal).estimate(x, capture.sampleRateHz)
        checkCancelled()
        val frames = pitch.frameCount
        val voicedIndices = (0 until frames).filter { pitch.voiced[it] }
        // A sustained vowel has no speech span or pauses; the other tasks do.
        val pauses = if (task == SpeechTask.SUSTAINED_VOWEL) null else PauseDetector.summarize(activity, signal)
        val events = if (task == SpeechTask.REPEATED_SYLLABLE) SyllableEventDetector.detect(activity, signal) else null
        checkCancelled()

        val metrics = HashMap<SpeechMetric, SpeechValue>()
        val durationMs = capture.durationMs
        metrics[SpeechMetric.DURATION_MS] = measured(durationMs)

        val voicedMs = voicedIndices.size * pitch.hopMs
        val voicedPercent = if (frames == 0) null else 100.0 * voicedIndices.size / frames
        metrics[SpeechMetric.VOICED_DURATION_MS] = measured(voicedMs)
        metrics[SpeechMetric.VOICED_PERCENT] = optional(voicedPercent, UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)

        // Pitch and intensity of the voiced frames (vowel, reading, spontaneous).
        val f0 = voicedIndices.map { pitch.f0Hz[it] }
        val levelsVoiced = voicedIndices.filter { it < levels.size }.map { levels[it] }
        val hnr = voicedIndices.map { pitch.hnrDb[it] }.filter { it.isFinite() }
        val enoughVoice = voicedMs >= MIN_VOICED_MS
        val f0Median = SignalFrames.median(f0)
        val semitones = if (f0Median == null) emptyList() else f0.map { 12.0 * log2(it / f0Median) }

        val usesVoice = task != SpeechTask.REPEATED_SYLLABLE
        if (usesVoice) {
            metrics[SpeechMetric.F0_MEDIAN_HZ] = if (enoughVoice) optional(f0Median, UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)
            else SpeechValue.Unavailable(UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)
            metrics[SpeechMetric.F0_SD_SEMITONES] = if (enoughVoice) optional(SignalFrames.sd(semitones), UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)
            else SpeechValue.Unavailable(UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)
            metrics[SpeechMetric.INTENSITY_MEDIAN_DBFS] = if (enoughVoice) optional(SignalFrames.median(levelsVoiced), UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)
            else SpeechValue.Unavailable(UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)
            metrics[SpeechMetric.INTENSITY_SD_DB] = if (enoughVoice) optional(SignalFrames.sd(levelsVoiced), UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)
            else SpeechValue.Unavailable(UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)
            metrics[SpeechMetric.HNR_DB] = if (enoughVoice) optional(SignalFrames.median(hnr), UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)
            else SpeechValue.Unavailable(UnavailableReason.NOT_ENOUGH_VOICED_FRAMES)
        } else {
            for (m in listOf(SpeechMetric.F0_MEDIAN_HZ, SpeechMetric.F0_SD_SEMITONES, SpeechMetric.INTENSITY_MEDIAN_DBFS,
                SpeechMetric.INTENSITY_SD_DB, SpeechMetric.HNR_DB)) {
                metrics[m] = SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
            }
        }

        // Cycle-level perturbation: not computed (see SpeechMetric docs).
        metrics[SpeechMetric.JITTER_PERCENT] = SpeechValue.Unavailable(UnavailableReason.CYCLE_MEASUREMENT_NOT_RELIABLE)
        metrics[SpeechMetric.SHIMMER_PERCENT] = SpeechValue.Unavailable(UnavailableReason.CYCLE_MEASUREMENT_NOT_RELIABLE)

        // Speech span, pauses, events.
        if (pauses != null) {
            metrics[SpeechMetric.SPEECH_SPAN_MS] = optional(pauses.speechSpanMs, UnavailableReason.NO_SPEECH_DETECTED)
            metrics[SpeechMetric.PAUSE_COUNT] = measured(pauses.pauseCount.toDouble())
            metrics[SpeechMetric.PAUSE_MEAN_MS] = optional(pauses.meanPauseMs, UnavailableReason.NO_SPEECH_DETECTED)
            metrics[SpeechMetric.LONG_PAUSE_RATIO_PERCENT] = optional(pauses.longPauseRatioPercent, UnavailableReason.NO_SPEECH_DETECTED)
            val activeShare = activePercentVoiced(activity, pitch)
            metrics[SpeechMetric.VOICED_OF_ACTIVE_PERCENT] = if (task == SpeechTask.SUSTAINED_VOWEL) SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
            else optional(activeShare, UnavailableReason.NO_SPEECH_DETECTED)
        } else {
            metrics[SpeechMetric.SPEECH_SPAN_MS] = SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
            metrics[SpeechMetric.PAUSE_COUNT] = SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
            metrics[SpeechMetric.PAUSE_MEAN_MS] = SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
            metrics[SpeechMetric.LONG_PAUSE_RATIO_PERCENT] = SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
            metrics[SpeechMetric.VOICED_OF_ACTIVE_PERCENT] = SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
        }
        if (events != null) {
            metrics[SpeechMetric.EVENT_COUNT] = measured(events.count.toDouble())
            metrics[SpeechMetric.EVENT_RATE_HZ] = optional(events.rateHz, UnavailableReason.NOT_ENOUGH_EVENTS)
            metrics[SpeechMetric.EVENT_INTERVAL_CV_PERCENT] = optional(events.intervalCvPercent, UnavailableReason.NOT_ENOUGH_EVENTS)
        } else {
            metrics[SpeechMetric.EVENT_COUNT] = SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
            metrics[SpeechMetric.EVENT_RATE_HZ] = SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
            metrics[SpeechMetric.EVENT_INTERVAL_CV_PERCENT] = SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
        }

        // Rates need a transcription, which this version does not provide.
        val needsWords = task == SpeechTask.READING || task == SpeechTask.SPONTANEOUS
        metrics[SpeechMetric.SPEAKING_RATE_WPM] = if (needsWords) SpeechValue.Unavailable(UnavailableReason.NO_TRANSCRIPTION_SUPPORT)
        else SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)
        metrics[SpeechMetric.ARTICULATION_RATE] = if (needsWords) SpeechValue.Unavailable(UnavailableReason.NO_TRANSCRIPTION_SUPPORT)
        else SpeechValue.Unavailable(UnavailableReason.NOT_APPLICABLE_TO_TASK)

        checkCancelled()
        return SpeechAnalysis(task, report, metrics, pauses, events)
    }

    /** Quality report of a short capture (the pre-recording microphone check). No task metrics are computed. */
    fun inputReport(capture: AudioCapture, plannedDurationMs: Long): SpeechQualityReport {
        val x = SignalFrames.toUnit(capture.samples)
        val levels = SignalFrames.frameLevelsDb(x, capture.sampleRateHz, signal.frameMs, signal.hopMs)
        val activity = VoiceActivityDetector.detect(levels, signal.hopMs, signal)
        return AudioQualityAnalyzer.analyze(capture, plannedDurationMs, activity, format, quality)
    }

    /** Every metric unavailable because the recording is not usable (measurable ones carry LOW_QUALITY_SIGNAL). */
    private fun unusable(task: SpeechTask): Map<SpeechMetric, SpeechValue> =
        SpeechMetric.entries.associateWith { metric ->
            when (metric.kind) {
                MetricKind.NOT_COMPUTED -> SpeechValue.Unavailable(
                    if (metric == SpeechMetric.JITTER_PERCENT || metric == SpeechMetric.SHIMMER_PERCENT)
                        UnavailableReason.CYCLE_MEASUREMENT_NOT_RELIABLE else UnavailableReason.NO_TRANSCRIPTION_SUPPORT
                )
                else -> SpeechValue.Unavailable(UnavailableReason.LOW_QUALITY_SIGNAL)
            }
        }

    private fun activePercentVoiced(activity: ActivityTrack, pitch: PitchTrack): Double? {
        val n = minOf(activity.active.size, pitch.frameCount)
        val active = (0 until n).count { activity.active[it] }
        if (active == 0) return null
        val voicedActive = (0 until n).count { activity.active[it] && pitch.voiced[it] }
        return 100.0 * voicedActive / active
    }

    private fun measured(value: Double): SpeechValue = SpeechValue.Measured(value)

    private fun optional(value: Double?, reason: UnavailableReason): SpeechValue =
        if (value != null && value.isFinite()) SpeechValue.Measured(value) else SpeechValue.Unavailable(reason)

    private companion object {
        /** Minimum voiced duration for F0 and intensity statistics (ms). */
        const val MIN_VOICED_MS = 2_000.0
    }
}
