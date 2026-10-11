package com.example.parkinson.speech

/**
 * Speech-activity mask per analysis frame. [noiseFloorDb] is the 10th percentile of the frame levels (a robust
 * estimate of the background, which holds even when the person speaks most of the time). A frame is active when
 * its level is at least [SpeechSignalConfig.activityMarginDb] above it. A 5-frame majority filter removes
 * single-frame flips. Unvoiced speech (for example "s") counts as active: this detector decides speech
 * activity, not voicing.
 */
class ActivityTrack(
    val hopMs: Double,
    val levelDb: DoubleArray,
    val active: BooleanArray,
    val noiseFloorDb: Double?,
    val thresholdDb: Double?,
) {
    /** Median level of the active frames (dBFS), null when no frame is active. */
    val activeLevelDb: Double? get() = SignalFrames.median(levelDb.indices.filter { active[it] }.map { levelDb[it] })
}

object VoiceActivityDetector {

    fun detect(levelDb: DoubleArray, hopMs: Double, config: SpeechSignalConfig = SpeechSignalConfig()): ActivityTrack {
        val noise = SignalFrames.percentile(levelDb.toList(), 10.0)
        if (noise == null) {
            return ActivityTrack(hopMs, levelDb, BooleanArray(levelDb.size), null, null)
        }
        val threshold = noise + config.activityMarginDb
        val raw = BooleanArray(levelDb.size) { levelDb[it] >= threshold }
        return ActivityTrack(hopMs, levelDb, majority(raw, 5), noise, threshold)
    }

    /** Majority vote over a centred window of [window] frames (window odd). */
    internal fun majority(x: BooleanArray, window: Int): BooleanArray {
        val half = window / 2
        return BooleanArray(x.size) { i ->
            var yes = 0
            var total = 0
            for (j in maxOf(0, i - half)..minOf(x.size - 1, i + half)) {
                total++
                if (x[j]) yes++
            }
            yes * 2 > total
        }
    }
}
