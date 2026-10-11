package com.example.parkinson.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.sensors.MotionSample
import com.example.parkinson.sensors.MotionSensorType
import com.example.parkinson.tremor.ImuTremorQualityIssue
import com.example.parkinson.tremor.RestingTremorImuConfig
import com.example.parkinson.tremor.RestingTremorImuEngine
import com.example.parkinson.tremor.RestingTremorImuResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.sin

/** Phone-motion results built from SYNTHETIC samples (test input only). */
@RunWith(RobolectricTestRunner::class)
class RestingTremorImuStorageTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository
    private val config = RestingTremorImuConfig()

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AssessmentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAssessmentRepository.from(db)
    }

    @After
    fun tearDown() = db.close()

    private fun accelerometer(durationS: Double): List<MotionSample> =
        (0 until (durationS * 100).toInt()).map { i ->
            val t = i / 100.0
            MotionSample(MotionSensorType.ACCELEROMETER, 1_000_000_000L + i * 10_000_000L, 0f, 0f, (9.81 + 0.3 * sin(2 * PI * 5.0 * t)).toFloat())
        }

    private fun result(id: String, accel: List<MotionSample>, gyroPresent: Boolean, endEpochMs: Long): RestingTremorImuResult {
        val analysis = RestingTremorImuEngine(config).analyze(accel, emptyList(), 15_000L)
        return RestingTremorImuResult.from(
            analysis = analysis,
            assessmentId = id,
            startEpochMs = endEpochMs - 15_000L,
            endEpochMs = endEpochMs,
            hand = SelectedHand.LEFT,
            plannedDurationMs = 15_000L,
            gyroscopePresent = gyroPresent,
            config = config,
        )
    }

    @Test
    fun accelerometerOnlyResultRoundTripsWithItsModalityAndNoGyroscopeFeatures() = runBlocking {
        val saved = result("imu-usable", accelerometer(15.0), gyroPresent = false, endEpochMs = 1_700_000_000_000L)
        repository.saveRestingTremorImu(saved)

        val back = requireNotNull(repository.observeRestingTremorImu("imu-usable").first())
        assertEquals(SelectedHand.LEFT, back.hand)
        assertEquals(QualityStatus.LOW_QUALITY, back.qualityStatus)
        assertTrue(ImuTremorQualityIssue.GYROSCOPE_UNUSABLE in back.qualityIssues)
        assertFalse(back.gyroscopePresent)
        assertFalse(back.gyroscopeUsed)
        assertTrue(back.isUsable)
        val m = requireNotNull(back.metrics)
        assertNull(m.gyroRmsRadS)
        assertNull(m.gyroSamples)
        assertEquals(5.0, requireNotNull(m.dominantFrequencyHz), 0.15)
        assertEquals(saved.metrics, back.metrics)
        assertEquals(saved.configSummary, back.configSummary)
        assertEquals(saved.algorithmVersion, back.algorithmVersion)
    }

    @Test
    fun insufficientResultIsStoredWithItsStatusAndNoFeatures() = runBlocking {
        val saved = result("imu-insufficient", accelerometer(2.0), gyroPresent = false, endEpochMs = 1_700_000_100_000L)
        repository.saveRestingTremorImu(saved)

        val back = requireNotNull(repository.observeRestingTremorImu("imu-insufficient").first())
        assertEquals(QualityStatus.INSUFFICIENT_DATA, back.qualityStatus)
        // Two seconds of samples: enough samples, but far too short a recording for the features.
        assertTrue(ImuTremorQualityIssue.RECORDING_TOO_SHORT in back.qualityIssues)
        assertNull("insufficient data must not carry features", back.metrics)
        assertFalse(back.isUsable)
        assertFalse(back.gyroscopeUsed)
    }

    @Test
    fun historyShowsEveryPhoneMotionResultAsARestingTremorAssessmentWithItsStatus() = runBlocking {
        repository.saveRestingTremorImu(result("imu-old", accelerometer(15.0), gyroPresent = false, endEpochMs = 1_000L))
        repository.saveRestingTremorImu(result("imu-new", accelerometer(2.0), gyroPresent = false, endEpochMs = 2_000L))

        val history = repository.observeHistory().first()
        val tremor = history.filter { it.type == AssessmentType.RESTING_TREMOR }
        assertEquals(listOf("imu-new", "imu-old"), tremor.map { it.assessmentId })
        assertTrue(tremor.all { it is RestingTremorImuResult })
        val byId = tremor.filterIsInstance<RestingTremorImuResult>().associateBy { it.assessmentId }
        assertEquals(QualityStatus.INSUFFICIENT_DATA, byId.getValue("imu-new").quality.status)
        assertNull(byId.getValue("imu-new").quality.score)
        assertEquals(QualityStatus.LOW_QUALITY, byId.getValue("imu-old").quality.status)
    }

    @Test
    fun savingPhoneMotionResultsLeavesTheCameraTableUntouched() = runBlocking {
        repository.saveRestingTremorImu(result("imu-only", accelerometer(15.0), gyroPresent = false, endEpochMs = 3_000L))

        assertTrue(db.restingTremorDao().observeAll().first().isEmpty())
        assertEquals(1, db.restingTremorImuDao().observeAll().first().size)
    }

    @Test
    fun savingTheSameAssessmentAgainReplacesItsRow() = runBlocking {
        repository.saveRestingTremorImu(result("imu-dup", accelerometer(15.0), gyroPresent = false, endEpochMs = 4_000L))
        repository.saveRestingTremorImu(result("imu-dup", accelerometer(2.0), gyroPresent = false, endEpochMs = 4_000L))

        val all = db.restingTremorImuDao().observeAll().first()
        assertEquals(1, all.size)
        assertEquals("INSUFFICIENT_DATA", all.single().qualityStatus)
    }
}
