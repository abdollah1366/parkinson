package com.example.parkinson.navigation

import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tremor.RestingTremorImuConfig
import com.example.parkinson.tremor.RestingTremorImuEngine
import com.example.parkinson.tremor.RestingTremorImuResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** A phone-motion result opens its own screen, never the camera result screen. */
@RunWith(RobolectricTestRunner::class)
class RestingTremorImuRoutingTest {

    @Test
    fun phoneMotionResultRoutesToTheImuResultScreen() {
        val config = RestingTremorImuConfig()
        val analysis = RestingTremorImuEngine(config).analyze(emptyList(), emptyList(), 15_000L)
        val result = RestingTremorImuResult.from(
            analysis = analysis,
            assessmentId = "imu-42",
            startEpochMs = 0L,
            endEpochMs = 1L,
            hand = SelectedHand.RIGHT,
            plannedDurationMs = 15_000L,
            gyroscopePresent = true,
            config = config,
        )
        assertEquals(QualityStatus.INSUFFICIENT_DATA, result.qualityStatus)

        val route = resultRouteFor(result)
        assertEquals("resting_tremor_imu_result/imu-42", route)
        assertNotEquals("resting_tremor_result/imu-42", route)
    }
}
