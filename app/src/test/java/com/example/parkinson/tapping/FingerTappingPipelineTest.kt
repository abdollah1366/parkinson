package com.example.parkinson.tapping

import com.example.parkinson.assessment.PerformanceTrendState
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.mediapipe.CameraDropEstimator
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.mediapipe.HandSideStatus
import com.example.parkinson.mediapipe.HandTrackingQuality
import com.example.parkinson.mediapipe.VisionQualityConfig
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.quality.QualityIssue
import com.example.parkinson.tapping.raw.FrameStatus
import com.example.parkinson.tapping.scoring.TappingNote
import com.example.parkinson.ui.screens.invalid.InvalidResultKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * End-to-end analysis scenarios for the layered quality model (camera / hand detection /
 * landmark tracking / tap detection / recording). Deterministic synthetic frames, no delays.
 */
class FingerTappingPipelineTest {

    private val analyzer = FingerTappingAnalyzer()
    private fun analyze(s: SyntheticTapping) = analyzer.analyze(s.recording())
    private fun near(expected: Int, actual: Int, tolerance: Int = 1) =
        assertTrue("expected $expected±$tolerance, got $actual", abs(expected - actual) <= tolerance)

    // 1 + 3
    @Test
    fun validTappingSequenceIsScoredWithEveryLayerGood() {
        val s = SyntheticTapping().regular(3.0)
        val a = analyze(s)
        near(s.expectedTaps(), a.metrics.tapCount)
        assertEquals(QualityStatus.VALID, a.quality.status)
        assertEquals(CameraQuality.GOOD, a.quality.cameraQuality)
        assertEquals(HandTrackingQuality.GOOD, a.quality.handTrackingQuality)
        assertTrue(a.quality.trackingRate > 0.95)
        assertEquals(ReliabilityLevel.RELIABLE, a.score!!.reliability)
        assertTrue(a.score!!.total in 0..100)
    }

    // 2 + 4
    @Test
    fun slowAndFastTappingAreCountedAndScoredBySpeed() {
        val slow = analyze(SyntheticTapping().regular(1.0))
        val fast = analyze(SyntheticTapping(fps = 60.0).regular(5.0))
        near(10, slow.metrics.tapCount)
        near(50, fast.metrics.tapCount, tolerance = 2)
        assertTrue(slow.score!!.rate < fast.score!!.rate)
    }

    // 5 + 6
    @Test
    fun smallAndLargeAmplitudesAreCountedAndScoredBySize() {
        val small = SyntheticTapping().regular(3.0).apply { amplitudes = listOf(0.3) }
        val large = SyntheticTapping().regular(3.0).apply { amplitudes = listOf(1.4) }
        val a = analyze(small)
        val b = analyze(large)
        near(small.expectedTaps(), a.metrics.tapCount)
        near(large.expectedTaps(), b.metrics.tapCount)
        assertTrue(a.score!!.amplitude < b.score!!.amplitude)
    }

    // 7
    @Test
    fun noMovementGivesNoScore() {
        val a = analyze(SyntheticTapping().apply { constantOpening = 0.5 })
        assertEquals(0, a.metrics.tapCount)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertNull(a.score)
    }

    // 8
    @Test
    fun noHandDetectedIsInsufficientDataNotLighting() {
        val a = analyze(SyntheticTapping().regular(3.0).status(0, 20_000, FrameStatus.NO_HAND))
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertEquals(InvalidResultKind.NO_HAND, InvalidResultKind.fromQuality(a.quality))
        assertNull(a.score)
    }

    // 9
    @Test
    fun intermittentHandDetectionKeepsTheTest() {
        // 150 ms without landmarks every second: short gaps are bridged, the test stays usable.
        val s = SyntheticTapping().regular(3.0)
        for (sec in 1..9) s.status(sec * 1_000L, sec * 1_000L + 150, FrameStatus.NO_HAND)
        val a = analyze(s)
        assertTrue(a.quality.isUsable)
        assertNotNull(a.score)
        // ~17 % of the time without landmarks: reduced (LOW_QUALITY) but usable and scored.
        assertEquals(0.83, a.quality.trackingRate, 0.03)
        assertTrue(a.metrics.tapCount >= s.expectedTaps() - 4)
    }

    // 10
    @Test
    fun cameraAndPipelineFrameDropsAreMeasuredNotFatal() {
        val s = SyntheticTapping().regular(3.0).dropped(4_000, 4_120).dropped(7_000, 7_100)
        val a = analyze(s)
        assertTrue("dropped ${a.metrics.frames.pipelineFramesDropped}", a.metrics.frames.pipelineFramesDropped >= 6)
        assertTrue(a.quality.isUsable)
        assertNotNull(a.score)
    }

    // 11 + CRITICAL 31: good light, poor tracking -> insufficient data, no score
    @Test
    fun goodLightingButPoorTrackingIsInsufficientData() {
        val s = SyntheticTapping().regular(3.0)
        // Landmarks missing half of the time, in 500 ms blocks.
        for (k in 0 until 10) s.status(k * 1_000L + 500, k * 1_000L + 1_000, FrameStatus.NO_HAND)
        s.meanLuma = 130f
        val a = analyze(s)
        assertEquals(CameraQuality.GOOD, a.quality.cameraQuality)
        assertEquals(HandTrackingQuality.POOR, a.quality.handTrackingQuality)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertTrue(QualityIssue.INSUFFICIENT_TRACKING in a.quality.issues)
        assertNull(a.score)
        // The patient is told about tracking, NOT about light.
        assertEquals(InvalidResultKind.POOR_TRACKING, InvalidResultKind.fromQuality(a.quality))
    }

    // 12 + CRITICAL 30: lighting warning != invalid data
    @Test
    fun lightingWarningWithValidLandmarksIsScored() {
        val s = SyntheticTapping().regular(3.0).apply { meanLuma = 30f } // dark image
        val a = analyze(s)
        assertEquals(CameraQuality.WARNING, a.quality.cameraQuality)
        assertEquals(HandTrackingQuality.GOOD, a.quality.handTrackingQuality)
        assertTrue(a.quality.lightingWarning)
        // The motor data are good, so the result is NOT rejected.
        assertTrue(a.quality.status == QualityStatus.VALID || a.quality.status == QualityStatus.LOW_QUALITY)
        assertFalse(a.quality.status == QualityStatus.INSUFFICIENT_DATA)
        assertNotNull(a.score)
        near(s.expectedTaps(), a.metrics.tapCount)
        assertTrue(TappingNote.LIGHTING_WARNING in a.interpretation.notes)
    }

    @Test
    fun darkImageWithReducedTrackingIsLowQualityWithALightingCause() {
        val s = SyntheticTapping().regular(3.0).apply { meanLuma = 25f }
        for (k in 1..4) s.status(k * 2_000L, k * 2_000L + 600, FrameStatus.NO_HAND)
        val a = analyze(s)
        assertEquals(HandTrackingQuality.WARNING, a.quality.handTrackingQuality)
        assertTrue(QualityIssue.LIGHTING_AFFECTED_TRACKING in a.quality.issues)
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertNotNull(a.score)
    }

    @Test
    fun poorTrackingInTheDarkGetsTheLightingTip() {
        val s = SyntheticTapping().regular(3.0).apply { meanLuma = 15f }
        for (k in 0 until 10) s.status(k * 1_000L + 500, k * 1_000L + 1_000, FrameStatus.NO_HAND)
        val a = analyze(s)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertEquals(InvalidResultKind.POOR_TRACKING_LIGHTING, InvalidResultKind.fromQuality(a.quality))
    }

    // Root cause 1: the handedness score must never discard landmarks.
    @Test
    fun uncertainHandednessDoesNotDiscardMotorData() {
        val s = SyntheticTapping().regular(3.0).side(0, 20_000, HandSideStatus.UNCERTAIN)
        val a = analyze(s)
        near(s.expectedTaps(), a.metrics.tapCount)
        assertTrue(a.quality.trackingRate > 0.95)
        assertEquals(QualityStatus.LOW_QUALITY, a.quality.status)
        assertEquals(listOf(QualityIssue.HAND_SIDE_UNCERTAIN), a.quality.issues)
        assertNotNull(a.score)
    }

    // Root cause 3: the handedness score must not lower tap confidence.
    @Test
    fun tapConfidenceDoesNotDependOnHandednessScore() {
        val s = SyntheticTapping().regular(3.0)
        val frames = s.frames().map { it.copy(confidence = 0.3) }
        val a = analyzer.analyze(s.recording().copy(frames = frames))
        assertTrue(a.metrics.meanEventConfidence!! > 0.8)
        assertFalse(QualityIssue.LOW_EVENT_CONFIDENCE in a.quality.issues)
        assertEquals(QualityStatus.VALID, a.quality.status)
    }

    // Root cause 2: frames without a hand arrive faster; tracking is measured in time, not frames.
    @Test
    fun trackingRateIsTimeBasedNotFrameBased() {
        val s = SyntheticTapping().regular(3.0)
        // 1 s without a hand delivered at 3x the frame rate (palm-detection-only frames are cheaper).
        val extra = (0 until 60).map { i ->
            com.example.parkinson.tapping.raw.TapFrame(10_000 + i, s.startMs + 4_000 + i * 16L, FrameStatus.NO_HAND)
        }
        val frames = (s.frames().filter { it.timestampMs - s.startMs !in 4_000L until 5_000L } + extra).sortedBy { it.timestampMs }
        val a = analyzer.analyze(s.recording().copy(frames = frames))
        assertTrue("frame share ${a.metrics.frames.frameTrackingRate}", a.metrics.frames.frameTrackingRate < 0.85)
        assertEquals(0.9, a.quality.trackingRate, 0.03)
        assertTrue(a.quality.isUsable)
    }

    // 13
    @Test
    fun insufficientTapEventsGiveNoScore() {
        val a = analyze(SyntheticTapping().apply { periodsMs = listOf(1_000.0, 1_000.0); constantOpening = null })
        assertTrue(a.metrics.tapCount < 4)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, a.quality.status)
        assertNull(a.score)
    }

    // 14 + 15
    @Test
    fun jitterAndBouncesDoNotCreateDuplicateTaps() {
        val clean = analyze(SyntheticTapping().regular(3.0))
        val jittery = analyze(SyntheticTapping(noiseSigma = 0.04, seed = 3).regular(3.0))
        near(clean.metrics.tapCount, jittery.metrics.tapCount)
        val intervals = jittery.detection.events.zipWithNext { a, b -> b.timestampMs - a.timestampMs }
        assertTrue(intervals.all { it >= DetectionDefaults.MIN_INTERVAL_MS })
    }

    private object DetectionDefaults {
        val MIN_INTERVAL_MS = com.example.parkinson.tapping.detection.DetectionConfig().minInterTapIntervalMs
    }

    // 16
    @Test
    fun irregularTappingLowersRegularity() {
        val regular = analyze(SyntheticTapping().regular(3.0))
        val irregular = analyze(SyntheticTapping().apply {
            periodsMs = List(40) { i -> if (i % 2 == 0) 220.0 else 520.0 }
        })
        assertTrue(irregular.score!!.rhythm!! < regular.score!!.rhythm!!)
    }

    // 17
    @Test
    fun performanceDeclineIsDetected() {
        val s = SyntheticTapping().apply {
            // Slows from ~4 Hz to ~1.7 Hz while the opening shrinks.
            periodsMs = List(40) { i -> 250.0 + i * 15.0 }
            amplitudes = List(40) { i -> (1.1 - i * 0.03).coerceAtLeast(0.3) }
        }
        val a = analyze(s)
        assertEquals(PerformanceTrendState.DECLINING, a.score!!.trendState)
        assertTrue(a.score!!.earlyScore!! > a.score!!.lateScore!!)
        assertTrue(TappingNote.TREND_DECLINING in a.interpretation.notes)
    }

    // 18
    @Test
    fun stablePerformanceIsStable() {
        val a = analyze(SyntheticTapping().regular(3.0))
        assertEquals(PerformanceTrendState.STABLE, a.score!!.trendState)
        assertEquals(100, a.score!!.trend)
        assertTrue(TappingNote.TREND_STABLE in a.interpretation.notes)
    }

    @Test
    fun payloadKeepsEveryTap() {
        val s = SyntheticTapping().regular(3.0)
        val analysis = analyze(s)
        val r = FingerTappingAnalyzer.toAssessment(analysis, "id", 1L, SelectedHand.RIGHT, s.startMs)
        assertEquals(r.tapCount, r.payload.timesMs.size)
        assertEquals(r.tapCount, r.payload.amplitudes.size)
        assertEquals(r.tapCount, r.payload.velocities.size)
        assertTrue(r.payload.timesMs.all { it in 0..10_000 })
        assertEquals(r.tapCount - 1, r.payload.intervalsMs.size)
        assertTrue(r.payload.velocities.all { it > 0 })
        assertEquals(r.validLandmarkFrames, analysis.metrics.frames.validFrames)
    }

    @Test
    fun visionQualityLimitsAndDropEstimator() {
        val v = VisionQualityConfig()
        assertEquals(CameraQuality.GOOD, v.cameraQuality(120f))
        assertEquals(CameraQuality.WARNING, v.cameraQuality(40f))
        assertEquals(CameraQuality.POOR, v.cameraQuality(10f))
        assertEquals(CameraQuality.WARNING, v.cameraQuality(240f))
        assertEquals(CameraQuality.GOOD, v.cameraQuality(null))
        assertEquals(HandTrackingQuality.GOOD, v.trackingQuality(0.9))
        assertEquals(HandTrackingQuality.WARNING, v.trackingQuality(0.7))
        assertEquals(HandTrackingQuality.POOR, v.trackingQuality(0.4))

        val e = CameraDropEstimator()
        val period = 33_333_333L
        var t = 0L
        assertEquals(0, e.onFrame(t))
        repeat(5) { t += period; assertEquals(0, e.onFrame(t)) }
        t += 3 * period
        assertEquals(2, e.onFrame(t))
    }
}
