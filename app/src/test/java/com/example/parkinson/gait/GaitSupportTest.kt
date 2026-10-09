package com.example.parkinson.gait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** SYNTHETIC body landmarks and frames for unit tests only. Image is 480 x 640 px (upright). */
class GaitSupportTest {

    private fun body(): MutableList<PosePoint?> {
        val p = MutableList<PosePoint?>(PoseLandmarkIndex.COUNT) { null }
        fun set(i: Int, x: Double, y: Double) { p[i] = PosePoint(x, y, 0.95) }
        set(PoseLandmarkIndex.LEFT_SHOULDER, 0.40, 0.30)
        set(PoseLandmarkIndex.RIGHT_SHOULDER, 0.60, 0.30)
        set(PoseLandmarkIndex.LEFT_HIP, 0.42, 0.50)
        set(PoseLandmarkIndex.RIGHT_HIP, 0.58, 0.50)
        set(PoseLandmarkIndex.LEFT_ANKLE, 0.42, 0.90)
        set(PoseLandmarkIndex.RIGHT_ANKLE, 0.58, 0.90)
        return p
    }

    @Test
    fun ankleSeparationIsRightMinusLeftInTorsoLengths() {
        // Right ankle x 0.58 x 480 = 278.4 px, left 0.42 x 480 = 201.6 px: 76.8 px / 128 px torso = 0.6.
        val f = requireNotNull(CameraPoseFeatureExtractor().extract(0L, body(), 480, 640).features)
        assertEquals(0.6, f.ankleSeparationTorso, 1e-9)
    }

    @Test
    fun ankleSeparationChangesSignWhenTheFeetCross() {
        val p = body()
        p[PoseLandmarkIndex.LEFT_ANKLE] = PosePoint(0.60, 0.90, 0.95)
        p[PoseLandmarkIndex.RIGHT_ANKLE] = PosePoint(0.40, 0.90, 0.95)
        val f = requireNotNull(CameraPoseFeatureExtractor().extract(0L, p, 480, 640).features)
        assertTrue(f.ankleSeparationTorso < 0)
    }

    @Test
    fun diagnosticsSummaryCountsStatusesAndNamesUnavailableMetrics() {
        val frames = (0 until 40).map { i ->
            PoseFrame(i * 66L, if (i < 30) PoseFrameStatus.NO_POSE else PoseFrameStatus.INCOMPLETE, null)
        }
        val recording = GaitRecording(frames, 20_000L)
        val analysis = GaitEngine().analyze(recording)
        val text = GaitDiagnostics.summary(40, 3, recording, analysis)
        assertTrue(text, text.contains("resultsInWindow=40"))
        assertTrue(text, text.contains("resultsOutsideWindow=3"))
        assertTrue(text, text.contains("NO_POSE:30"))
        assertTrue(text, text.contains("INCOMPLETE:10"))
        assertTrue(text, text.contains("metrics=unavailable"))
        assertTrue(text, text.contains("LOW_VALID_FRAMES"))
    }

    @Test
    fun diagnosticsSummaryNeverPrintsCoordinates() {
        val recording = GaitRecording(
            (0 until 40).map { i ->
                PoseFrame(
                    i * 66L,
                    PoseFrameStatus.VALID,
                    PoseFeatures(128.0, 1.5, 0.3, -0.3, 2.0, 2.0, 0.0, 0.6),
                )
            },
            20_000L,
        )
        val text = GaitDiagnostics.summary(40, 0, recording, GaitEngine().analyze(recording))
        // Only counts and timings are logged: no landmark-derived series, no per-frame values.
        assertFalse(text, text.contains("ankle"))
        assertFalse(text, text.contains("wrist"))
        assertFalse(text, text.contains("torso"))
    }
}
