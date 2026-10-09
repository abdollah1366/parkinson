package com.example.parkinson.speech

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Per-frame pitch track. [f0Hz] is NaN for frames that are not voiced; [periodicity] is the normalized
 * autocorrelation at the chosen lag (0..1); [hnrDb] is NaN for unvoiced frames. Frame i starts at sample
 * i x hop and is centred at [centreMs] (ms from the start of the recording).
 */
class PitchTrack(
    val hopMs: Double,
    val centreMs: DoubleArray,
    val f0Hz: DoubleArray,
    val periodicity: DoubleArray,
    val hnrDb: DoubleArray,
    val voiced: BooleanArray,
) {
    val frameCount: Int get() = centreMs.size
}

/**
 * Fundamental frequency by normalized autocorrelation (the autocorrelation family used by Praat's
 * "cross-correlation" method, without its path optimisation):
 *
 *   r(tau) = sum_n a[n] b[n + tau] / sqrt( sum_n a[n]^2 * sum_n b[n + tau]^2 ),  a = frame, b = frame shifted
 *
 * The lag is searched over [sampleRate / maxF0, sampleRate / minF0]. The first local maximum that reaches 85 %
 * of the global maximum is taken, which avoids octave errors (a multiple of the true period also correlates).
 * The lag is refined by parabolic interpolation.
 *
 * HNR follows Boersma (1993): HNR = 10 log10( r / (1 - r) ), with r the periodicity of a voiced frame.
 * Assumptions: stationary voicing within the frame; it is an estimate that depends on the periodicity, not a
 * measurement of noise power.
 *
 * Limitations: at 16 kHz the lag resolution is one sample, so F0 is refined by interpolation only; jitter and
 * shimmer are not derived from this track (see [SpeechTaskEngine]).
 */
class PitchEstimator(private val config: SpeechSignalConfig = SpeechSignalConfig()) {

    fun estimate(x: DoubleArray, sampleRateHz: Int): PitchTrack {
        val w = SignalFrames.frameLength(sampleRateHz, config.frameMs)
        val hop = SignalFrames.hopLength(sampleRateHz, config.hopMs)
        val minLag = max1(floor(sampleRateHz / config.maxF0Hz).toInt())
        val maxLag = ceil(sampleRateHz / config.minF0Hz).toInt()
        val needed = w + maxLag
        val n = if (x.size < needed) 0 else 1 + (x.size - needed) / hop

        val centre = DoubleArray(n) { (it * hop + w / 2.0) * 1000.0 / sampleRateHz }
        val f0 = DoubleArray(n) { Double.NaN }
        val periodicity = DoubleArray(n)
        val hnr = DoubleArray(n) { Double.NaN }
        val voiced = BooleanArray(n)
        val r = DoubleArray(maxLag + 2)

        for (i in 0 until n) {
            val start = i * hop
            var ea = 0.0
            for (k in 0 until w) ea += x[start + k] * x[start + k]
            if (ea < SILENT_ENERGY) continue

            // Normalized correlation for every lag in range.
            var rmax = 0.0
            for (lag in minLag..maxLag) {
                var num = 0.0
                var eb = 0.0
                for (k in 0 until w) {
                    val b = x[start + k + lag]
                    num += x[start + k] * b
                    eb += b * b
                }
                val value = if (eb <= SILENT_ENERGY) 0.0 else num / sqrt(ea * eb)
                r[lag] = value
                if (value > rmax) rmax = value
            }
            if (rmax <= 0.0) continue

            // Octave guard: the smallest lag that is a local maximum and reaches 85 % of the global maximum.
            var best = -1
            for (lag in minLag + 1 until maxLag) {
                if (r[lag] >= 0.85 * rmax && r[lag] >= r[lag - 1] && r[lag] >= r[lag + 1]) {
                    best = lag
                    break
                }
            }
            if (best < 0) continue

            val r0 = r[best]
            val denom = r[best - 1] - 2 * r0 + r[best + 1]
            val shift = if (denom != 0.0) 0.5 * (r[best - 1] - r[best + 1]) / denom else 0.0
            val lagRefined = best + shift.coerceIn(-0.5, 0.5)
            val freq = sampleRateHz / lagRefined

            periodicity[i] = r0
            if (r0 >= config.voicingThreshold && freq in config.minF0Hz..config.maxF0Hz) {
                voiced[i] = true
                f0[i] = freq
                val rc = r0.coerceIn(1e-4, 0.9999)
                hnr[i] = 10.0 * log10(rc / (1.0 - rc))
            }
        }
        return PitchTrack(
            hopMs = config.hopMs,
            centreMs = centre,
            f0Hz = f0,
            periodicity = periodicity,
            hnrDb = hnr,
            voiced = voiced,
        )
    }

    private fun max1(v: Int) = if (v < 1) 1 else v

    private companion object {
        /** Frame energy (sum of squares) below which a frame is treated as silence for pitch. */
        const val SILENT_ENERGY = 1e-8
    }
}
