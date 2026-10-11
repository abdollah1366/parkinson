package com.example.parkinson.imu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GaitScoringTest {

    @Test
    fun valuesInsideTheReferenceRangeScoreFullWithoutSigns() {
        val s = GaitScoring.score(cadence = 112.0, strideCvPercent = 1.8, stepAsymmetryPercent = 2.0,
            meanTurnVelocityDegPerSec = 85.0, walkingTremorIndex = 2.0, standingTremorIndex = 1.5)
        assertEquals(100, s.overall)
        assertTrue(s.signs.isEmpty())
    }

    @Test
    fun theScoreFallsLinearlyBetweenTheBounds() {
        assertEquals(50, GaitScoring.linear(85.0, GaitReference.CADENCE_HEALTHY, GaitReference.CADENCE_ZERO))
        assertEquals(0, GaitScoring.linear(60.0, GaitReference.CADENCE_HEALTHY, GaitReference.CADENCE_ZERO))
        assertEquals(50, GaitScoring.linear(12.5, GaitReference.ASYMMETRY_HEALTHY, GaitReference.ASYMMETRY_ZERO))
    }

    @Test
    fun theScreenshotValuesGiveALowerScoreAndTheirSigns() {
        // The values on a real result screen (cadence 85.2, variability 17.8 %), measured with the older step-peak method.
        val s = GaitScoring.score(cadence = 85.2, strideCvPercent = 17.8, stepAsymmetryPercent = null,
            meanTurnVelocityDegPerSec = null, walkingTremorIndex = null, standingTremorIndex = null)
        assertEquals(26, s.overall)
        assertTrue(GaitSign.LOW_CADENCE in s.signs)
        assertTrue(GaitSign.HIGH_VARIABILITY in s.signs)
        assertTrue(GaitSign.PARKINSONIAN_PATTERN in s.signs)
    }

    @Test
    fun aLimpAloneIsAnAsymmetrySignNotAParkinsonianPattern() {
        val s = GaitScoring.score(cadence = 105.0, strideCvPercent = 2.0, stepAsymmetryPercent = 15.0,
            meanTurnVelocityDegPerSec = 80.0, walkingTremorIndex = null, standingTremorIndex = null)
        assertTrue(GaitSign.ASYMMETRY in s.signs)
        assertFalse(GaitSign.PARKINSONIAN_PATTERN in s.signs)
        assertEquals(83, s.overall)
    }

    @Test
    fun oneMeasuredDomainIsNotEnoughForAnOverallScore() {
        val s = GaitScoring.score(cadence = 110.0, strideCvPercent = null, stepAsymmetryPercent = null,
            meanTurnVelocityDegPerSec = null, walkingTremorIndex = null, standingTremorIndex = null)
        assertNull(s.overall)
        assertEquals(1, s.domains.size)
    }

    @Test
    fun tremorIsASignButNotPartOfTheScore() {
        val s = GaitScoring.score(cadence = 110.0, strideCvPercent = 2.0, stepAsymmetryPercent = 3.0,
            meanTurnVelocityDegPerSec = null, walkingTremorIndex = 20.0, standingTremorIndex = null)
        assertEquals(100, s.overall)
        assertTrue(GaitSign.WALKING_TREMOR in s.signs)
    }

    @Test
    fun syntheticWalksEndToEnd() {
        val healthy = GaitFeatureExtractor.extract(
            SyntheticThighGait(strideS = 1.05, turns = listOf(Triple(14.0, 2.0, 180.0))).grid(),
        )
        val shuffling = GaitFeatureExtractor.extract(
            SyntheticThighGait(strideS = 1.5, strideJitter = 0.07, turns = listOf(Triple(14.0, 4.5, 180.0)), seed = 4).grid(),
        )
        fun scoreOf(f: GaitFeatures) = GaitScoring.score(f.strideCadenceStepsPerMinute, f.strideCvPercent, f.stepAsymmetryPercent,
            f.meanTurnVelocityDegPerSec, f.walkingTremorIndex, null)
        val good = scoreOf(healthy)
        val poor = scoreOf(shuffling)
        assertTrue("good=$good", good.overall!! >= 90)
        assertTrue("poor=$poor", poor.overall!! < 50)
        assertTrue(GaitSign.SLOW_TURNING in poor.signs)
        assertTrue(GaitSign.PARKINSONIAN_PATTERN in poor.signs)
    }
}
