package com.example.parkinson.assessment

import com.example.parkinson.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssessmentCatalogTest {

    private val all = AssessmentCatalog.all

    @Test
    fun everyPlannedTestIsListedExactlyOnce() {
        assertEquals(AssessmentType.entries.toSet(), all.map { it.type }.toSet())
        assertEquals(AssessmentType.entries.size, all.size)
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(9, all.size)
    }

    @Test
    fun idsAreStableAndRoundTrip() {
        AssessmentType.entries.forEach { assertEquals(it, AssessmentType.fromId(it.id)) }
        assertNull(AssessmentType.fromId("unknown"))
        assertNull(AssessmentCatalog.byId(null))
        assertEquals("finger_tapping", AssessmentType.FINGER_TAPPING.id)
        assertEquals("hand_stability", AssessmentType.HAND_STABILITY.id)
    }

    @Test
    fun onlyImplementedTestsCanStart() {
        assertEquals(
            listOf(
                AssessmentType.FINGER_TAPPING,
                AssessmentType.HAND_STABILITY,
                AssessmentType.PRONATION_SUPINATION,
                AssessmentType.HAND_OPEN_CLOSE,
                AssessmentType.RESTING_TREMOR,
                AssessmentType.GAIT,
                AssessmentType.SIT_TO_STAND
            ),
            AssessmentCatalog.available.map { it.type }
        )
    }

    @Test
    fun unavailableTestsHaveNoRouteAndCannotStart() {
        all.filterNot { it.isAvailable }.forEach {
            assertNull(it.id, it.startRoute)
            assertNull(it.id, it.afterSensorCheckRoute)
            assertNotEquals(it.id, AssessmentStatus.AVAILABLE, it.status)
            assertFalse(it.status.canStart)
        }
    }

    @Test
    fun availableTestsHaveAFullFlow() {
        assertEquals(Screen.FingerTappingIntro.route, AssessmentCatalog[AssessmentType.FINGER_TAPPING].startRoute)
        assertEquals(Screen.FingerTappingPreparation.route, AssessmentCatalog[AssessmentType.FINGER_TAPPING].afterSensorCheckRoute)
        assertEquals(Screen.HandStabilityIntro.route, AssessmentCatalog[AssessmentType.HAND_STABILITY].startRoute)
        assertEquals(Screen.HandStabilityTest.route, AssessmentCatalog[AssessmentType.HAND_STABILITY].afterSensorCheckRoute)
        val ps = AssessmentCatalog[AssessmentType.PRONATION_SUPINATION]
        assertEquals(Screen.PronationSupinationIntro.route, ps.startRoute)
        assertEquals(Screen.PronationSupinationTest.route, ps.afterSensorCheckRoute)
        assertEquals(AssessmentStatus.AVAILABLE, ps.status)
        assertTrue(ps.englishName.contains("PRONATION_SUPINATION"))
    }

    @Test
    fun specifiedDurationsAndSensors() {
        val ft = AssessmentCatalog[AssessmentType.FINGER_TAPPING]
        assertEquals(10, ft.durationSeconds)
        assertEquals(listOf(SensorRequirement.CAMERA, SensorRequirement.HAND_LANDMARK_MODEL), ft.sensors)
        val hs = AssessmentCatalog[AssessmentType.HAND_STABILITY]
        assertEquals(15, hs.durationSeconds)
        assertEquals(setOf(SensorRequirement.ACCELEROMETER, SensorRequirement.GYROSCOPE), hs.sensors.toSet())
        val ps = AssessmentCatalog[AssessmentType.PRONATION_SUPINATION]
        // 5 s preparation + 3 s countdown + 10 s recording.
        assertEquals(18, ps.durationSeconds)
        // Gyroscope required (primary); accelerometer optional (supporting).
        assertEquals(listOf(SensorRequirement.GYROSCOPE), ps.sensors)
        assertEquals(listOf(SensorRequirement.ACCELEROMETER), ps.optionalSensors)
        assertTrue(ps.requiresHandSelection)
        assertTrue(ft.requiresHandSelection && hs.requiresHandSelection)
    }

    @Test
    fun restingTremorIsAvailableWithAFullFlow() {
        val rt = AssessmentCatalog[AssessmentType.RESTING_TREMOR]
        assertEquals(AssessmentStatus.AVAILABLE, rt.status)
        assertEquals(Screen.RestingTremorIntro.route, rt.startRoute)
        assertEquals(Screen.RestingTremorTest.route, rt.afterSensorCheckRoute)
        assertEquals(15, rt.durationSeconds)
        assertEquals(listOf(SensorRequirement.CAMERA, SensorRequirement.HAND_LANDMARK_MODEL), rt.sensors)
    }

    @Test
    fun everyTestHasTextsSensorsAndIcon() {
        all.forEach {
            assertTrue(it.id, it.title != 0 && it.description != 0 && it.purpose != 0)
            assertTrue(it.id, it.sensors.isNotEmpty())
            assertTrue(it.id, it.icon.isNotBlank() && it.englishName.isNotBlank())
        }
    }

    @Test
    fun nextAvailableTestCycles() {
        assertEquals(AssessmentType.HAND_STABILITY, AssessmentCatalog.nextAvailableAfter(AssessmentType.FINGER_TAPPING)?.type)
        assertEquals(AssessmentType.PRONATION_SUPINATION, AssessmentCatalog.nextAvailableAfter(AssessmentType.HAND_STABILITY)?.type)
        assertEquals(AssessmentType.HAND_OPEN_CLOSE, AssessmentCatalog.nextAvailableAfter(AssessmentType.PRONATION_SUPINATION)?.type)
        assertEquals(AssessmentType.RESTING_TREMOR, AssessmentCatalog.nextAvailableAfter(AssessmentType.HAND_OPEN_CLOSE)?.type)
        assertEquals(AssessmentType.GAIT, AssessmentCatalog.nextAvailableAfter(AssessmentType.RESTING_TREMOR)?.type)
        assertEquals(AssessmentType.SIT_TO_STAND, AssessmentCatalog.nextAvailableAfter(AssessmentType.GAIT)?.type)
        assertEquals(AssessmentType.FINGER_TAPPING, AssessmentCatalog.nextAvailableAfter(AssessmentType.SIT_TO_STAND)?.type)
    }

    @Test
    fun sitToStandIsAvailableWithAFiveRepetitionFlowAndCameraPoseOnly() {
        val sts = AssessmentCatalog[AssessmentType.SIT_TO_STAND]
        assertEquals(AssessmentStatus.AVAILABLE, sts.status)
        assertEquals(Screen.SitToStandIntro.route, sts.startRoute)
        assertEquals(Screen.SitToStandTest.route, sts.afterSensorCheckRoute)
        assertFalse(sts.requiresHandSelection)
        // Camera pose only: the chair and the body are observed from the side; no phone sensor is used.
        assertEquals(listOf(SensorRequirement.CAMERA, SensorRequirement.POSE_LANDMARK_MODEL), sts.sensors)
        assertTrue(sts.optionalSensors.isEmpty())
    }

    @Test
    fun gaitIsAvailableWithAWalkingFlowAndCameraPoseOnly() {
        val gait = AssessmentCatalog[AssessmentType.GAIT]
        assertEquals(AssessmentStatus.AVAILABLE, gait.status)
        assertEquals(Screen.GaitIntro.route, gait.startRoute)
        assertEquals(Screen.GaitTest.route, gait.afterSensorCheckRoute)
        assertEquals(20, gait.durationSeconds)
        assertFalse(gait.requiresHandSelection)
        // Camera pose only: no phone sensor is required or used by this protocol.
        assertEquals(listOf(SensorRequirement.CAMERA, SensorRequirement.POSE_LANDMARK_MODEL), gait.sensors)
        assertTrue(gait.optionalSensors.isEmpty())
    }

    @Test
    fun sensorCheckReportsMissingSensors() {
        val hs = AssessmentCatalog[AssessmentType.HAND_STABILITY]
        val noGyro = SensorCheckResult.check(hs) { it != SensorRequirement.GYROSCOPE }
        assertFalse(noGyro.canStart)
        assertEquals(listOf(SensorRequirement.GYROSCOPE), noGyro.missing)
        assertEquals(listOf(SensorRequirement.ACCELEROMETER), noGyro.available)

        assertTrue(SensorCheckResult.check(hs) { true }.canStart)
    }

    @Test
    fun failingSensorProbeCountsAsMissing() {
        val ft = AssessmentCatalog[AssessmentType.FINGER_TAPPING]
        val result = SensorCheckResult.check(ft) { if (it == SensorRequirement.CAMERA) error("probe failed") else true }
        assertEquals(listOf(SensorRequirement.CAMERA), result.missing)
    }

    @Test
    fun pronationNeedsTheGyroscopeButNotTheAccelerometer() {
        val ps = AssessmentCatalog[AssessmentType.PRONATION_SUPINATION]
        val noAccel = SensorCheckResult.check(ps) { it != SensorRequirement.ACCELEROMETER }
        assertTrue(noAccel.canStart)
        assertEquals(listOf(SensorRequirement.ACCELEROMETER), noAccel.missingOptional)

        val noGyro = SensorCheckResult.check(ps) { it != SensorRequirement.GYROSCOPE }
        assertFalse(noGyro.canStart)
        assertEquals(listOf(SensorRequirement.GYROSCOPE), noGyro.missing)
        assertTrue(SensorRequirement.GYROSCOPE in ps.missingSensorMessages)
    }
}
