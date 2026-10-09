package com.example.parkinson.sts

/**
 * SYNTHETIC sit-to-stand movements for unit tests only. Never used as an assessment result.
 *
 * The posture p runs from 0 (seated) to 1 (full stand). The baseline is seated: knee 95 degrees, hip at y = 400 px,
 * shin 200 px. A posture p gives knee = 95 + 80 p degrees and hip y = 400 - 0.9 x 200 x p px, so the movement score
 * is 2 p (the knee term dominates). Score 1 = p 0.5 = full stand; score 0.35 = p 0.175 = seated band.
 */
object SyntheticSitToStand {

    val BASELINE = SeatedBaseline(side = SitToStandSide.RIGHT, kneeAngleDeg = 95.0, hipYPx = 400.0, shinPx = 200.0)

    private const val FRAME_MS = 33L

    /** One movement segment: [durationMs] of linear change from posture [from] to posture [to]. */
    data class Segment(val durationMs: Long, val from: Double, val to: Double)

    /** Five standard cycles: rise 1 s, stand 1 s, sit 1 s, seated 1 s; preceded by 1 s seated. */
    fun fiveCycles(standPosture: Double = 1.0): List<Segment> {
        val list = mutableListOf(Segment(1_000L, 0.0, 0.0))
        repeat(5) {
            list += Segment(1_000L, 0.0, standPosture)
            list += Segment(1_000L, standPosture, standPosture)
            list += Segment(1_000L, standPosture, 0.0)
            list += Segment(1_000L, 0.0, 0.0)
        }
        return list
    }

    /** Posture at time [t] (ms) along the segments; the last value holds after the end. */
    fun postureAt(segments: List<Segment>, t: Long): Double {
        var start = 0L
        var last = 0.0
        for (s in segments) {
            val end = start + s.durationMs
            if (t < end) {
                val f = (t - start).toDouble() / s.durationMs
                return s.from + (s.to - s.from) * f
            }
            start = end
            last = s.to
        }
        return last
    }

    fun totalMs(segments: List<Segment>): Long = segments.sumOf { it.durationMs }

    /** A valid sample for posture [p] at [timestampMs]. */
    fun sample(timestampMs: Long, p: Double, side: SitToStandSide = SitToStandSide.RIGHT): SitToStandSample =
        SitToStandSample(
            timestampMs = timestampMs,
            issue = null,
            side = side,
            visibility = 0.95,
            kneeAngleDeg = BASELINE.kneeAngleDeg + 80.0 * p,
            hipYPx = BASELINE.hipYPx - 0.9 * BASELINE.shinPx * p,
            shinPx = BASELINE.shinPx,
            trunkLeanDeg = 2.0,
        )

    /** Samples of a segment list at about 30 fps, starting at [startMs]. */
    fun samples(segments: List<Segment>, startMs: Long = 0L): List<SitToStandSample> {
        val total = totalMs(segments)
        val out = ArrayList<SitToStandSample>()
        var t = 0L
        while (t <= total) {
            out += sample(startMs + t, postureAt(segments, t))
            t += FRAME_MS
        }
        return out
    }

    fun frameMs(): Long = FRAME_MS
}
