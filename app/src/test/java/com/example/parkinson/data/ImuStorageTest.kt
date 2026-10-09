package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.imu.ImuDataInfo
import com.example.parkinson.imu.ImuGaitResult
import com.example.parkinson.imu.ImuRepetition
import com.example.parkinson.imu.ImuSitToStandResult
import com.example.parkinson.imu.InvalidInterval
import com.example.parkinson.imu.PLACEMENT_FRONT_TROUSER_POCKET
import com.example.parkinson.imu.Rejection
import com.example.parkinson.imu.RejectionReason
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Storage of the IMU results. The results below are hand-written FIXTURES that exercise the columns; they are not
 * measurements from a phone or a person.
 */
@RunWith(RobolectricTestRunner::class)
class ImuStorageTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository

    private val data = ImuDataInfo(
        accelerometerRateHz = 49.8,
        gyroscopeRateHz = 49.6,
        dropoutCount = 1,
        timestampIssues = 2,
        receivedEvents = 4000,
        unreliablePercent = 0.0,
    )

    private fun sts(id: String, endEpochMs: Long, repetitions: Int = 5): ImuSitToStandResult = ImuSitToStandResult(
        assessmentId = id,
        timestampEpochMs = endEpochMs,
        startEpochMs = endEpochMs - 20_000L,
        endEpochMs = endEpochMs,
        sessionId = "session-$id",
        protocolId = "five_times_sit_to_stand_imu",
        placement = PLACEMENT_FRONT_TROUSER_POCKET,
        targetRepetitions = 5,
        repetitions = (0 until repetitions).map { i ->
            val start = 4_000.0 + i * 4_000.0
            ImuRepetition(
                index = i + 1,
                standOnsetMs = start,
                standReachedMs = start + 900.0,
                sitOnsetMs = start + 1_800.0,
                seatedReachedMs = start + 2_600.0,
                peakPostureDeg = 70.0,
                peakRotationDegPerSec = 120.0,
            )
        },
        rejections = listOf(Rejection(RejectionReason.PARTIAL_RISE, 1_000.0, 1_500.0)),
        invalidIntervals = listOf(InvalidInterval(30_000.0, 30_400.0)),
        coveragePercent = 97.5,
        data = data,
        qualityStatus = QualityStatus.VALID,
        algorithmVersion = "imu-algo-1.0.0",
        configSummary = "fixture",
        scoringVersion = "not-scored",
    )

    private fun gait(id: String, endEpochMs: Long, cadence: Double?): ImuGaitResult = ImuGaitResult(
        assessmentId = id,
        timestampEpochMs = endEpochMs,
        startEpochMs = endEpochMs - 33_000L,
        endEpochMs = endEpochMs,
        sessionId = "session-$id",
        protocolId = "timed_walk_imu",
        placement = PLACEMENT_FRONT_TROUSER_POCKET,
        plannedWalkingMs = 30_000.0,
        validWalkingMs = 24_000.0,
        steps = 40,
        bouts = 2,
        cadenceStepsPerMinute = cadence,
        meanStepIntervalMs = cadence?.let { 60_000.0 / it },
        stepIntervalCvPercent = cadence?.let { 4.2 },
        turningMs = 1_200.0,
        turnRejectedSteps = 2,
        implausibleRejectedPeaks = 1,
        coveragePercent = 93.0,
        invalidIntervals = emptyList(),
        data = data,
        qualityStatus = QualityStatus.VALID,
        algorithmVersion = "imu-algo-1.0.0",
        configSummary = "fixture",
        scoringVersion = "not-scored",
    )

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-test-imu-10.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class,
    )

    /** Version 10 -> 11 only adds the two IMU tables; the migrated schema must equal 11.json. */
    @Test
    fun migrationFromVersion10AddsTheImuTablesAndValidatesAgainstTheSchema() {
        migrationHelper.createDatabase(10).close()
        val v11 = migrationHelper.runMigrationsAndValidate(11)
        listOf("imu_sit_to_stand_assessments", "imu_gait_assessments").forEach { table ->
            v11.prepare("SELECT COUNT(*) FROM $table").use {
                it.step()
                assertEquals(0L, it.getLong(0))
            }
        }
        v11.close()
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AssessmentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAssessmentRepository.from(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun sitToStandRoundTripKeepsRepetitionsRejectionsIntervalsAndDataFacts() {
        val original = sts("imu-sts-1", 1_700_000_000_000L)
        runBlocking { repository.saveImuSitToStand(original) }

        val back = requireNotNull(runBlocking { repository.observeImuSitToStand("imu-sts-1").first() })
        assertEquals(original.repetitions, back.repetitions)
        assertEquals(original.rejections, back.rejections)
        assertEquals(original.invalidIntervals, back.invalidIntervals)
        assertEquals(original.data, back.data)
        assertEquals(original.coveragePercent, back.coveragePercent, 1e-9)
        assertEquals(original.qualityStatus, back.qualityStatus)
        assertEquals(original.placement, back.placement)
        assertEquals(original.protocolId, back.protocolId)
        assertEquals(original.algorithmVersion, back.algorithmVersion)
        assertEquals(original.scoringVersion, back.scoringVersion)
        // Derived values come from the stored repetitions, so they agree after the round trip.
        assertEquals(original.totalTimeMs, back.totalTimeMs, 1e-9)
        assertEquals(original.meanPauseMs!!, back.meanPauseMs!!, 1e-9)
    }

    @Test
    fun gaitRoundTripKeepsCadenceNullableAndTheCountsIntact() {
        val withCadence = gait("imu-gait-1", 1_700_000_000_000L, cadence = 112.0)
        val withoutCadence = gait("imu-gait-2", 1_700_000_100_000L, cadence = null)
        runBlocking {
            repository.saveImuGait(withCadence)
            repository.saveImuGait(withoutCadence)
        }

        val back = requireNotNull(runBlocking { repository.observeImuGait("imu-gait-1").first() })
        assertEquals(withCadence.steps, back.steps)
        assertEquals(withCadence.bouts, back.bouts)
        assertEquals(withCadence.cadenceStepsPerMinute!!, back.cadenceStepsPerMinute!!, 1e-9)
        assertEquals(withCadence.turningMs, back.turningMs, 1e-9)
        assertEquals(withCadence.turnRejectedSteps, back.turnRejectedSteps)
        assertEquals(withCadence.implausibleRejectedPeaks, back.implausibleRejectedPeaks)
        assertEquals(withCadence.data, back.data)
        assertEquals(false, back.plantarPressureMeasured)

        val backNull = requireNotNull(runBlocking { repository.observeImuGait("imu-gait-2").first() })
        assertNull(backNull.cadenceStepsPerMinute)
        assertNull(backNull.stepIntervalCvPercent)
    }

    @Test
    fun aRepeatedAttemptWithTheSameIdReplacesTheRowInsteadOfDuplicating() {
        runBlocking {
            repository.saveImuSitToStand(sts("same", 1_000L))
            repository.saveImuSitToStand(sts("same", 1_000L))
            repository.saveImuGait(gait("same-gait", 1_000L, cadence = 100.0))
            repository.saveImuGait(gait("same-gait", 1_000L, cadence = 100.0))
        }
        assertEquals(1, runBlocking { repository.observeAllImuSitToStand().first() }.size)
        assertEquals(1, runBlocking { repository.observeAllImuGait().first() }.size)
    }

    @Test
    fun historyIncludesBothImuTypesNewestFirst() {
        runBlocking {
            repository.saveImuSitToStand(sts("sts-old", 1_000L))
            repository.saveImuGait(gait("gait-new", 3_000L, cadence = 110.0))
            repository.saveImuSitToStand(sts("sts-new", 2_000L))
        }
        val history = runBlocking { repository.observeHistory().first() }
        assertEquals(
            listOf("gait-new", "sts-new", "sts-old"),
            history.filter { it.type == AssessmentType.GAIT || it.type == AssessmentType.SIT_TO_STAND }.map { it.assessmentId },
        )
        assertTrue(history.zipWithNext().all { (a, b) -> a.timestampEpochMs >= b.timestampEpochMs })
    }
}
