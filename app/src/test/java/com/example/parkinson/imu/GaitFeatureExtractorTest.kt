package com.example.parkinson.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Walking features on SYNTHETIC pocket data (see [SyntheticThighGait]). Not clinical evidence. */
class GaitFeatureExtractorTest {

    @Test
    fun steadySymmetricWalkingGivesTheStrideCadenceAndLowVariability() {
        val f = GaitFeatureExtractor.extract(SyntheticThighGait(strideS = 1.1).grid())
        assertEquals(1100.0, f.strideTimeMs!!, 15.0)
        assertEquals(109.1, f.strideCadenceStepsPerMinute!!, 2.0)
        assertTrue("cv=${f.strideCvPercent}", f.strideCvPercent!! < 1.0)
        assertTrue("asym=${f.stepAsymmetryPercent}", f.stepAsymmetryPercent!! < 3.0)
        assertTrue("strides=${f.strides}", f.strides >= 20)
        assertTrue(f.turns.isEmpty())
    }

    @Test
    fun theLouderPocketLegStepDoesNotHideTheOtherStep() {
        // The far leg's heel strike is weaker; the stride cadence still counts both steps.
        val f = GaitFeatureExtractor.extract(SyntheticThighGait(strideS = 1.2).grid())
        assertEquals(100.0, f.strideCadenceStepsPerMinute!!, 2.0)
    }

    @Test
    fun irregularStridesRaiseTheVariability() {
        val f = GaitFeatureExtractor.extract(SyntheticThighGait(strideJitter = 0.06, seed = 9).grid())
        assertTrue("cv=${f.strideCvPercent}", f.strideCvPercent!! in 3.0..10.0)
    }

    @Test
    fun unequalStepsAreReportedAsAsymmetry() {
        // The other leg strikes at 42 % of the stride: steps of 42 % and 58 %, a timing asymmetry of 16 %.
        val f = GaitFeatureExtractor.extract(SyntheticThighGait(stepPhase = 0.42).grid())
        assertEquals(16.0, f.stepAsymmetryPercent!!, 3.0)
    }

    @Test
    fun turnsAreMeasuredWithAngleDurationAndSpeed() {
        val gait = SyntheticThighGait(turns = listOf(Triple(10.0, 2.0, 180.0), Triple(22.0, 4.0, -180.0)))
        val f = GaitFeatureExtractor.extract(gait.grid())
        assertEquals("turns=${f.turns}", 2, f.turns.size)
        val (fast, slow) = f.turns
        assertEquals(180.0, abs(fast.angleDeg), 25.0)
        assertTrue(fast.angleDeg > 0 && slow.angleDeg < 0)
        assertTrue("fast=${fast.meanDegPerSec} slow=${slow.meanDegPerSec}", fast.meanDegPerSec > slow.meanDegPerSec)
        assertEquals(45.0, slow.meanDegPerSec, 12.0)
        assertNotNull(f.meanTurnVelocityDegPerSec)
        // Straight walking between the turns still gives strides.
        assertNotNull(f.strideTimeMs)
    }

    @Test
    fun aTremorPeakAwayFromTheGaitHarmonicsIsProminent() {
        val calm = GaitFeatureExtractor.extract(SyntheticThighGait().grid(), SyntheticThighGait.standing())
        val shaky = GaitFeatureExtractor.extract(
            SyntheticThighGait(tremorHz = 5.3, tremorDegPerS = 20.0).grid(),
            SyntheticThighGait.standing(tremorHz = 5.3, tremorDegPerS = 4.0),
        )
        assertTrue("walking=${shaky.walkingTremorIndex}", shaky.walkingTremorIndex!! >= GaitReference.TREMOR_PROMINENCE_SIGN)
        assertEquals(5.3, shaky.walkingTremorHz!!, 0.4)
        assertTrue("standing=${shaky.standingTremorIndex}", shaky.standingTremorIndex!! >= GaitReference.TREMOR_PROMINENCE_SIGN)
        assertTrue(
            "calm walking=${calm.walkingTremorIndex}",
            calm.walkingTremorIndex == null || calm.walkingTremorIndex!! < GaitReference.TREMOR_PROMINENCE_SIGN,
        )
    }

    @Test
    fun standingStillGivesNoWalkingFeatures() {
        val f = GaitFeatureExtractor.extract(SyntheticThighGait.standing(durationS = 30.0))
        assertNull(f.strideTimeMs)
        assertNull(f.strideCvPercent)
        assertNull(f.stepAsymmetryPercent)
        assertEquals(0, f.strides)
    }
}
