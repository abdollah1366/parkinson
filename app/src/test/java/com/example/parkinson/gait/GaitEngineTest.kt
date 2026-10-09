package com.example.parkinson.gait

import com.example.parkinson.assessment.QualityStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/** SYNTHETIC pose series for unit tests only. Never used as an assessment result. */
class GaitEngineTest {

    private val engine = GaitEngine()

    /**
     * Pose frames at 15 fps. The ankle separation is amplitude x sin(2 pi f t): |sin| has one peak per
     * half cycle, so the step rate is 2 f per second. [stepHz] = null gives a standing person (no steps).
     */
    private fun walk(
        durationMs: Long = 20_000L,
        stepHz: Double? = 0.8333,
        amplitudeTorso: Double = 0.8,
        wrists: Boolean = true,
        valid: (Int) -> Boolean = { true },
    ): GaitRecording {
        val stepMs = 1000.0 / 15.0
        val n = (durationMs / stepMs).toInt() + 1
        val frames = (0 until n).map { k ->
            val t = k * stepMs / 1000.0
            val timestamp = (k * stepMs).toLong()
            if (!valid(k)) {
                PoseFrame(timestamp, PoseFrameStatus.NO_POSE, null)
            } else {
                val phase = if (stepHz == null) 0.0 else 2 * PI * stepHz * t
                val sep = if (stepHz == null) 0.0 else amplitudeTorso * sin(phase)
                val swing = 0.3 * sin(phase)
                PoseFrame(
                    timestamp,
                    PoseFrameStatus.VALID,
                    PoseFeatures(
                        torsoLengthPx = 128.0,
                        trunkInclinationDeg = 2.0,
                        leftWristOffset = if (wrists) swing else null,
                        rightWristOffset = if (wrists) -swing else null,
                        leftAnkleHeight = 2.0,
                        rightAnkleHeight = 2.0,
                        shoulderTiltDeg = 0.0,
                        ankleSeparationTorso = sep,
                    )
                )
            }
        }
        return GaitRecording(frames, plannedDurationMs = 20_000L)
    }

    @Test
    fun steadyWalkGivesTheKnownStepCountAndCadence() {
        val analysis = engine.analyze(walk())
        val m = requireNotNull(analysis.metrics)
        assertEquals(QualityStatus.VALID, analysis.quality.status)
        assertTrue(analysis.quality.isUsable)
        // 0.8333 Hz stride frequency -> 1.667 steps per second -> about 100 steps per minute.
        assertEquals(33.0, m.stepCount.toDouble(), 3.0)
        assertEquals(100.0, requireNotNull(m.cadenceStepsPerMinute), 2.0)
        assertTrue(requireNotNull(m.stepIntervalCvPercent) < 5.0)
        assertEquals(m.stepCount, m.stepTimesMs.size)
    }

    @Test
    fun trunkLeanAndArmSwingAreMeasuredFromTheFrames() {
        val m = requireNotNull(engine.analyze(walk()).metrics)
        assertEquals(2.0, requireNotNull(m.trunkLeanMeanDeg), 1e-9)
        // Wrist swing amplitude 0.3 torso lengths: the range is 0.6 (up to sampling at the extremes).
        assertEquals(0.6, requireNotNull(m.leftArmSwingRangeTorso), 0.02)
        assertEquals(0.6, requireNotNull(m.rightArmSwingRangeTorso), 0.02)
    }

    @Test
    fun standingPersonGivesNoStepsCadenceOrVariabilityAndIsInsufficient() {
        val analysis = engine.analyze(walk(stepHz = null))
        val m = requireNotNull(analysis.metrics)
        assertEquals(0, m.stepCount)
        assertNull(m.cadenceStepsPerMinute)
        assertNull(m.stepIntervalCvPercent)
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertEquals(GaitQualityIssue.TOO_FEW_STEPS, analysis.quality.primaryIssue)
        assertFalse(analysis.quality.isUsable)
    }

    @Test
    fun missingWristsGiveNoArmSwingInsteadOfZero() {
        val m = requireNotNull(engine.analyze(walk(wrists = false)).metrics)
        assertNull(m.leftArmSwingRangeTorso)
        assertNull(m.rightArmSwingRangeTorso)
        // The step measurement does not depend on the wrists.
        assertTrue(m.stepCount > 30)
    }

    @Test
    fun aLongGapOfBodyNotVisibleIsInsufficientAndAddsNoSteps() {
        // 5 s with no body (frames 60..134 at 15 fps): longer than the 1 s gap limit.
        val analysis = engine.analyze(walk(valid = { it !in 60..134 }))
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertTrue(analysis.quality.issues.contains(GaitQualityIssue.LONG_GAP))
        val m = requireNotNull(analysis.metrics)
        assertTrue(m.longestGapMs >= 5_000L)
        // Steps are detected inside each valid segment only, so no step is placed in the gap.
        assertTrue(m.stepTimesMs.none { it in 4_000L..9_000L })
    }

    @Test
    fun repeatedShortGapsAreLowQualityButStillUsable() {
        // Every 10th frame missing: 90 % valid, gaps of about 67 ms (shorter than the 500 ms interruption limit).
        val analysis = engine.analyze(walk(valid = { it % 10 != 0 }))
        assertEquals(QualityStatus.LOW_QUALITY, analysis.quality.status)
        assertEquals(listOf(GaitQualityIssue.REDUCED_VALID_FRAMES), analysis.quality.issues)
        assertTrue(analysis.quality.isUsable)
        assertTrue(requireNotNull(analysis.metrics).stepCount > 30)
    }

    @Test
    fun lowValidShareIsInsufficientEvenWithoutLongGaps() {
        // Two of every three frames missing: about 33 % valid.
        val analysis = engine.analyze(walk(valid = { it % 3 == 0 }))
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
        assertTrue(analysis.quality.issues.contains(GaitQualityIssue.LOW_VALID_FRAMES))
    }

    @Test
    fun tooShortRecordingIsInsufficient() {
        val analysis = engine.analyze(walk(durationMs = 5_000L))
        assertTrue(analysis.quality.issues.contains(GaitQualityIssue.RECORDING_TOO_SHORT))
        assertEquals(QualityStatus.INSUFFICIENT_DATA, analysis.quality.status)
    }

    @Test
    fun nonMonotonicTimestampsAreInvalidAndGiveNoMetrics() {
        val recording = walk()
        val frames = recording.frames.toMutableList()
        // Frame 100 is stamped earlier than frame 99: the clock data cannot be trusted.
        frames[100] = frames[100].copy(timestampMs = frames[99].timestampMs - 10)
        val analysis = engine.analyze(GaitRecording(frames, 20_000L))
        assertNull(analysis.metrics)
        assertEquals(QualityStatus.INVALID, analysis.quality.status)
        assertEquals(GaitQualityIssue.NON_MONOTONIC_TIMESTAMPS, analysis.quality.primaryIssue)
    }

    @Test
    fun tooFewFramesGivesNoMetrics() {
        val few = GaitRecording(walk().frames.take(10), 20_000L)
        val analysis = engine.analyze(few)
        assertNull(analysis.metrics)
        assertEquals(GaitQualityIssue.TOO_FEW_FRAMES, analysis.quality.primaryIssue)
    }

    @Test
    fun noValidFrameGivesNoMetrics() {
        val analysis = engine.analyze(walk(valid = { false }))
        assertNull(analysis.metrics)
        assertEquals(GaitQualityIssue.LOW_VALID_FRAMES, analysis.quality.primaryIssue)
    }

    @Test
    fun qualityStatusFollowsTheWorstFinding() {
        val report = GaitQualityReport.of(
            listOf(GaitQualityIssue.REDUCED_VALID_FRAMES, GaitQualityIssue.TOO_FEW_STEPS, GaitQualityIssue.NON_MONOTONIC_TIMESTAMPS)
        )
        assertEquals(QualityStatus.INVALID, report.status)
        assertEquals(
            QualityStatus.INSUFFICIENT_DATA,
            GaitQualityReport.of(listOf(GaitQualityIssue.REDUCED_VALID_FRAMES, GaitQualityIssue.TOO_FEW_STEPS)).status
        )
        assertEquals(QualityStatus.VALID, GaitQualityReport.of(emptyList()).status)
    }
}
