package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.core.app.ApplicationProvider
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.openclose.HandOpenCloseEngine
import com.example.parkinson.openclose.HandOpenCloseResult
import com.example.parkinson.openclose.OpenCloseFrame
import com.example.parkinson.openclose.OpenCloseSeries
import com.example.parkinson.openclose.SyntheticOpening
import com.example.parkinson.tapping.raw.FrameStatus
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HandOpenCloseStorageTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository

    /** A result from SYNTHETIC openings (test input only), with an optional stretch of lost tracking. */
    private fun result(id: String, time: Long, lostFromMs: Long? = null, hand: SelectedHand = SelectedHand.LEFT): HandOpenCloseResult {
        val frames = SyntheticOpening.frames(0, 10_000).map { f ->
            if (lostFromMs != null && f.timestampMs in lostFromMs..(lostFromMs + 1_200)) {
                f.copy(status = FrameStatus.OUT_OF_FRAME, opening = Double.NaN)
            } else f
        }
        val analysis = HandOpenCloseEngine().analyze(SyntheticOpening.recording(frames, 0, 10_000))
        return HandOpenCloseResult.from(analysis, id, time, hand, 10_000L)
    }

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-test-6.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    /** Version 5 -> 6 only adds hand_open_close_assessments; the migrated schema must equal 6.json. */
    @Test
    fun migrationFromVersion5AddsTheTableAndValidatesAgainstTheSchema() {
        migrationHelper.createDatabase(5).close()
        val v6 = migrationHelper.runMigrationsAndValidate(6)
        v6.prepare("SELECT COUNT(*) FROM hand_open_close_assessments").use {
            it.step()
            assertEquals(0L, it.getLong(0))
        }
        v6.close()
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
    fun entityRoundTripKeepsEveryField() {
        val complete = result("a", 1L)
        assertEquals(complete, complete.toEntity().toDomain())
        assertEquals(QualityStatus.VALID, complete.qualityStatus)
        assertNull(complete.performanceIndex)
    }

    @Test
    fun resultWithLostTrackingRoundTripsIncludingMissingOpenings() {
        // Missing openings are stored as NaN and must read back as NaN in the same positions.
        val lost = result("b", 2L, lostFromMs = 3_000L)
        assertTrue(lost.series.sampleOpenings.any { it.isNaN() })
        val back = lost.toEntity().toDomain()
        assertEquals(lost, back)
        assertEquals(lost.series.sampleStatuses, back.series.sampleStatuses)
        assertEquals(lost.series.sampleOpenings.size, back.series.sampleOpenings.size)
    }

    @Test
    fun seriesAndCycleCountReadBackForRecalculation() {
        val r = result("c", 3L)
        val back = r.toEntity().toDomain()
        assertEquals(r.metrics.completedCycles, back.series.cycleEndTimesMs.size)
        assertEquals(r.series.cycleAmplitudes, back.series.cycleAmplitudes)
        assertEquals(OpenCloseSeries.statusCode(FrameStatus.VALID), back.series.sampleStatuses.first())
    }

    @Test
    fun storedResultIsRetrievedById() = runTest {
        val r = result("d", 4L)
        repository.saveHandOpenClose(r)
        assertEquals(r, repository.observeHandOpenClose("d").first())
        assertNull(repository.observeHandOpenClose("missing").first())
    }

    @Test
    fun historyKeepsOtherTypesAndSortsNewestFirst() = runTest {
        val older = result("old", 100L)
        val newer = result("new", 300L)
        repository.saveHandOpenClose(older)
        repository.saveHandOpenClose(newer)
        val history = repository.observeHistory().first()
        assertEquals(listOf("new", "old"), history.map { it.assessmentId })
        assertTrue(history.all { it.type == AssessmentType.HAND_OPEN_CLOSE })
        assertFalse(history.any { it.performanceIndex != null })
    }

    @Test
    fun savingAgainWithTheSameIdReplacesOnlyThatRow() = runTest {
        repository.saveHandOpenClose(result("same", 10L, hand = SelectedHand.RIGHT))
        repository.saveHandOpenClose(result("other", 20L))
        repository.saveHandOpenClose(result("same", 10L, hand = SelectedHand.LEFT))
        val history = repository.observeHistory().first()
        assertEquals(2, history.size)
        assertEquals(SelectedHand.LEFT, repository.observeHandOpenClose("same").first()!!.hand)
    }

    @Test
    fun invalidFrameStatusCodesRoundTrip() {
        val statuses = FrameStatus.entries
        val codes = statuses.map { OpenCloseSeries.statusCode(it) }
        assertEquals(statuses, codes.map { OpenCloseSeries.statusFromCode(it) })
        val frame = OpenCloseFrame(0, 0L, FrameStatus.VALID)
        assertTrue(frame.isValid)
    }
}
