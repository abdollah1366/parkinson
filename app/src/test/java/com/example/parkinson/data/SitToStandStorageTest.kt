package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.sts.SitToStandEngine
import com.example.parkinson.sts.SitToStandProtocol
import com.example.parkinson.sts.SitToStandQualityThresholds
import com.example.parkinson.sts.SitToStandResult
import com.example.parkinson.sts.SitToStandIssue
import com.example.parkinson.sts.SyntheticSitToStand
import com.example.parkinson.sts.previousSitToStand
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

/** Results come from SYNTHETIC movements (test input only). */
@RunWith(RobolectricTestRunner::class)
class SitToStandStorageTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository

    /** A complete attempt of five cycles, built with the engine (synthetic input). */
    private fun result(id: String, endEpochMs: Long, invalidEvery: Int = 0): SitToStandResult {
        val engine = SitToStandEngine(SyntheticSitToStand.BASELINE, SitToStandProtocol.FiveTimesSitToStand)
        val samples = SyntheticSitToStand.samples(SyntheticSitToStand.fiveCycles()).mapIndexed { i, s ->
            if (invalidEvery > 0 && i % invalidEvery == 0) com.example.parkinson.sts.SitToStandSample.invalid(
                s.timestampMs, SitToStandIssue.NO_POSE, s.side,
            ) else s
        }
        samples.forEach { engine.process(it) }
        return SitToStandResult.from(
            engine = engine,
            samples = samples,
            assessmentId = id,
            startEpochMs = endEpochMs - 20_000L,
            endEpochMs = endEpochMs,
            qualityThresholds = SitToStandQualityThresholds(),
        )
    }

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-test-9.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    /** Version 8 -> 9 only adds sit_to_stand_assessments; the migrated schema must equal 9.json. */
    @Test
    fun migrationFromVersion8AddsTheTableAndValidatesAgainstTheSchema() {
        migrationHelper.createDatabase(8).close()
        val v9 = migrationHelper.runMigrationsAndValidate(9)
        v9.prepare("SELECT COUNT(*) FROM sit_to_stand_assessments").use {
            it.step()
            assertEquals(0L, it.getLong(0))
        }
        v9.close()
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
    fun resultRoundTripKeepsRepetitionsBaselineQualityAndSeries() {
        val original = result("sts-1", 1_700_000_000_000L)
        runBlocking { repository.saveSitToStand(original) }

        val back = requireNotNull(runBlocking { repository.observeSitToStand("sts-1").first() })
        assertEquals(original.repetitions, back.repetitions)
        assertEquals(original.totalTimeMs, back.totalTimeMs)
        assertEquals(original.baseline, back.baseline)
        assertEquals(original.side, back.side)
        assertEquals(original.qualityStatus, back.qualityStatus)
        assertEquals(original.validFrames, back.validFrames)
        assertEquals(original.invalidFrames, back.invalidFrames)
        assertEquals(original.protocolId, back.protocolId)
        assertEquals(original.algorithmVersion, back.algorithmVersion)
        assertEquals(original.configSummary, back.configSummary)
        assertEquals(original.series.frameTimesMs, back.series.frameTimesMs)
        assertEquals(original.series.frameValid, back.series.frameValid)
        original.series.hipRiseShinLengths.zip(back.series.hipRiseShinLengths).forEach { (a, b) -> assertEquals(a, b, 0.0001) }
        original.series.kneeAngleDeg.zip(back.series.kneeAngleDeg).forEach { (a, b) -> assertEquals(a, b, 0.0001) }
        // Derived values are recomputed from the stored measurements, so they agree after the round trip.
        assertEquals(original.meanRepetitionMs!!, back.meanRepetitionMs!!, 1e-6)
        assertEquals(original.repetitionCvPercent!!, back.repetitionCvPercent!!, 1e-6)
    }

    @Test
    fun invalidFrameReasonsAndCountsSurviveStorage() {
        val original = result("sts-invalid-frames", 1_700_000_000_000L, invalidEvery = 4)
        assertTrue(original.invalidFrames > 0)
        runBlocking { repository.saveSitToStand(original) }
        val back = requireNotNull(runBlocking { repository.observeSitToStand("sts-invalid-frames").first() })
        assertEquals(original.invalidFramesByIssue, back.invalidFramesByIssue)
        assertEquals(original.validFramePercent, back.validFramePercent, 1e-9)
        assertTrue(back.series.frameValid.contains('I'))
    }

    @Test
    fun storedMeasurementsAreTheSourceOfTheDerivedValues() {
        val original = result("sts-derived", 1_700_000_000_000L)
        assertEquals(5, original.repetitions.size)
        assertEquals(QualityStatus.VALID, original.qualityStatus)
        // Mean repetition time is the mean of the five measured durations.
        assertEquals(original.repetitions.map { it.durationMs.toDouble() }.average(), original.meanRepetitionMs!!, 1e-9)
        assertTrue(original.meanInterRepetitionMs!! > 0)
        // Full stand in the synthetic movement is posture 1.0: 80 degrees above the seated knee.
        assertEquals(80.0, original.repetitions.first().peakKneeExtensionDeg, 1.0)
    }

    @Test
    fun historyIncludesTheSitToStandResultNewestFirst() {
        runBlocking {
            repository.saveSitToStand(result("sts-old", 1_000L))
            repository.saveSitToStand(result("sts-new", 2_000L))
        }
        val history = runBlocking { repository.observeHistory().first() }
        val sts = history.filter { it.type == AssessmentType.SIT_TO_STAND }
        assertEquals(listOf("sts-new", "sts-old"), sts.map { it.assessmentId })
        assertTrue(history.zipWithNext().all { (a, b) -> a.timestampEpochMs >= b.timestampEpochMs })
    }

    @Test
    fun previousAttemptIsTheMostRecentEarlierResultOfTheSameProtocol() {
        val oldest = result("a", 1_000L)
        val middle = result("b", 2_000L)
        val newest = result("c", 3_000L)
        val all = listOf(newest, oldest, middle)
        assertEquals("b", previousSitToStand(newest, all)?.assessmentId)
        assertEquals("a", previousSitToStand(middle, all)?.assessmentId)
        assertNull(previousSitToStand(oldest, all))
    }

    @Test
    fun aRepeatedAttemptWithTheSameIdReplacesTheRowInsteadOfDuplicating() {
        runBlocking {
            repository.saveSitToStand(result("same", 1_000L))
            repository.saveSitToStand(result("same", 1_000L))
        }
        val all = runBlocking { repository.observeAllSitToStand().first() }
        assertEquals(1, all.size)
    }
}
