package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tremor.RestingTremorEngine
import com.example.parkinson.tremor.RestingTremorResult
import com.example.parkinson.tremor.SyntheticTremor
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class RestingTremorStorageTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository

    /** A result from SYNTHETIC frames (test input only). */
    private fun result(id: String, time: Long): RestingTremorResult {
        val engine = RestingTremorEngine()
        val frames = SyntheticTremor.frames(frequencyHz = 5.0, amplitude = 0.05)
        val analysis = engine.analyze(SyntheticTremor.recording(frames))
        return RestingTremorResult.from(
            analysis = analysis,
            metrics = requireNotNull(analysis.metrics),
            frames = frames,
            assessmentId = id,
            startEpochMs = time - 15_000L,
            endEpochMs = time,
            hand = SelectedHand.LEFT,
            plannedDurationMs = 15_000L,
            signal = engine.signal,
        )
    }

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-test-7.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    /** Version 6 -> 7 only adds resting_tremor_assessments; the migrated schema must equal 7.json. */
    @Test
    fun migrationFromVersion6AddsTheTableAndValidatesAgainstTheSchema() {
        migrationHelper.createDatabase(6).close()
        val v7 = migrationHelper.runMigrationsAndValidate(7)
        v7.prepare("SELECT COUNT(*) FROM resting_tremor_assessments").use {
            it.step()
            assertEquals(0L, it.getLong(0))
        }
        v7.close()
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
    fun resultRoundTripKeepsMeasurementsQualityAndSeries() {
        val original = result("rt-1", 1_700_000_000_000L)
        kotlinx.coroutines.runBlocking { repository.saveRestingTremor(original) }

        val back = requireNotNull(kotlinx.coroutines.runBlocking { repository.observeRestingTremor("rt-1").first() })
        assertEquals(original.metrics, back.metrics)
        assertEquals(original.qualityStatus, back.qualityStatus)
        assertEquals(original.qualityIssues, back.qualityIssues)
        assertEquals(original.qualityScore, back.qualityScore)
        assertEquals(original.hand, back.hand)
        assertEquals(original.startEpochMs, back.startEpochMs)
        assertEquals(original.endEpochMs, back.endEpochMs)
        assertEquals(original.algorithmVersion, back.algorithmVersion)
        assertEquals(original.configSummary, back.configSummary)
        assertEquals(original.series.frameStatuses, back.series.frameStatuses)
        assertEquals(original.series.frameTimesMs, back.series.frameTimesMs)
        // Palm positions are stored with 3 decimals (pixels).
        original.series.palmXPx.zip(back.series.palmXPx).forEach { (a, b) -> assertEquals(a, b, 0.0006) }
        original.series.palmYPx.zip(back.series.palmYPx).forEach { (a, b) -> assertEquals(a, b, 0.0006) }
    }

    @Test
    fun historyIncludesTheRestingTremorResultNewestFirst() {
        kotlinx.coroutines.runBlocking {
            repository.saveRestingTremor(result("rt-old", 1_000L))
            repository.saveRestingTremor(result("rt-new", 2_000L))
        }
        val history = kotlinx.coroutines.runBlocking { repository.observeHistory().first() }
        val tremor = history.filter { it.type == AssessmentType.RESTING_TREMOR }
        assertEquals(listOf("rt-new", "rt-old"), tremor.map { it.assessmentId })
        assertTrue(history.zipWithNext().all { (a, b) -> a.timestampEpochMs >= b.timestampEpochMs })
    }
}
