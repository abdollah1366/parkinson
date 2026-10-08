package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.assessment.ReliabilityLevel
import com.example.parkinson.mediapipe.CameraQuality
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.FingerTappingAnalyzer
import com.example.parkinson.tapping.SyntheticTapping
import com.example.parkinson.tapping.raw.FrameStatus
import com.example.parkinson.tapping.result.FingerTappingAssessment
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Finger Tapping persistence: analysis -> FingerTappingAssessment -> repository -> Room -> History,
 * exactly what FingerTappingSession.onCompleted does after a usable recording.
 */
@RunWith(RobolectricTestRunner::class)
class FingerTappingStorageTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository
    private val analyzer = FingerTappingAnalyzer()

    /** The session's rule: only usable (VALID / LOW_QUALITY) analyses become results and are saved. */
    private suspend fun runAndSave(s: SyntheticTapping, id: String, time: Long): FingerTappingAssessment? {
        val analysis = analyzer.analyze(s.recording())
        if (!analysis.quality.isUsable) return null
        val a = FingerTappingAnalyzer.toAssessment(analysis, id, time, SelectedHand.RIGHT, s.startMs)
        repository.save(a)
        return a
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

    // CRITICAL 30 + 21 + 22
    @Test
    fun lightingWarningResultIsScoredSavedAndShownInHistory() = runTest {
        val saved = runAndSave(SyntheticTapping().regular(3.0).apply { meanLuma = 30f }, "dim-1", 5_000L)
        assertNotNull("a usable recording must produce a result", saved)
        saved!!
        assertEquals(CameraQuality.WARNING, saved.cameraQuality)
        assertNotNull(saved.performanceScore)

        // Persisted: read back identical, including score, interpretation and per-tap payload.
        val back = repository.observe("dim-1").first()
        assertEquals(saved, back)
        assertEquals(saved.performanceScore, back!!.performanceScore)
        assertEquals(saved.tapCount, back.payload.timesMs.size)

        // History shows it with hand, score and quality, and it can be opened again by id.
        val history = repository.observeHistory().first()
        val item = history.single()
        assertEquals(AssessmentType.FINGER_TAPPING, item.type)
        assertEquals(SelectedHand.RIGHT, item.hand)
        assertEquals(saved.performanceScore!!.total, item.performanceIndex)
        assertTrue(item.quality.status == QualityStatus.VALID || item.quality.status == QualityStatus.LOW_QUALITY)
        assertEquals(saved, repository.observe(item.assessmentId).first())
    }

    // CRITICAL 31
    @Test
    fun goodLightingWithPoorTrackingStoresNothing() = runTest {
        val s = SyntheticTapping().regular(3.0).apply { meanLuma = 130f }
        for (k in 0 until 10) s.status(k * 1_000L + 500, k * 1_000L + 1_000, FrameStatus.NO_HAND)
        assertNull(runAndSave(s, "poor-1", 6_000L))
        assertTrue(repository.observeHistory().first().isEmpty())
    }

    @Test
    fun lowQualityResultKeepsItsLimitedScoreInRoom() = runTest {
        val s = SyntheticTapping().regular(3.0).status(2_000, 2_600, FrameStatus.NO_HAND)
            .status(5_000, 5_600, FrameStatus.NO_HAND).status(8_000, 8_600, FrameStatus.NO_HAND)
        val saved = runAndSave(s, "low-1", 7_000L)!!
        assertEquals(QualityStatus.LOW_QUALITY, saved.qualityStatus)
        val back = repository.observe("low-1").first()!!
        assertEquals(ReliabilityLevel.LIMITED, back.performanceScore!!.reliability)
        assertEquals(saved.performanceIndex, repository.observeHistory().first().single().performanceIndex)
    }

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-test-5.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    @Test
    fun migrationFromVersion4KeepsFingerTappingRowsAndAddsColumns() {
        val v4 = migrationHelper.createDatabase(4)
        // A row written by ft-algo-1.0.2 / ft-score-0.1.0-preliminary (v1 columns only).
        v4.execSQL(
            "INSERT INTO finger_tapping_assessments (assessmentId, timestampEpochMs, hand, plannedDurationMs, " +
                "recordingDurationMs, tapCount, tapRateHz, tapsPer10Seconds, pauseCount, fps, validFramePercent, " +
                "dropoutCount, dropoutDurationMs, longestDropoutMs, recordingCompletenessPercent, qualityStatus, " +
                "qualityIssues, qualityScore, scoreTotal, scoreRate, scoreRhythm, scoreAmplitude, scoreConsistency, " +
                "scoreDataQuality, algorithmVersion, scoringVersion) VALUES ('old-ft', 42, 'LEFT', 10000, 10000, 30, " +
                "3.0, 30.0, 0, 30.0, 100.0, 0, 0, 0, 100.0, 'VALID', '', 90, 70, 60, 80, 75, 85, 90, " +
                "'ft-algo-1.0.2', 'ft-score-0.1.0-preliminary')"
        )
        v4.close()

        // Validates the migrated schema against 5.json.
        val v5 = migrationHelper.runMigrationsAndValidate(5)
        v5.prepare("SELECT tapCount, scoreTotal, trendState, reliability, tapTimesMs FROM finger_tapping_assessments WHERE assessmentId = 'old-ft'").use {
            assertTrue(it.step())
            assertEquals(30L, it.getLong(0))
            assertEquals(70L, it.getLong(1))
            assertEquals("INSUFFICIENT_DATA", it.getText(2))
            assertEquals("", it.getText(3))
            assertEquals("", it.getText(4))
        }
        v5.close()
    }

    @Test
    fun legacyRowsAreReadWithTheirScore() {
        val legacy = FingerTappingAnalyzer.toAssessment(analyzer.analyze(SyntheticTapping().regular(3.0).recording()), "x", 1L, SelectedHand.LEFT)
            .toEntity().copy(reliability = "", interpretationNotes = "", tapTimesMs = "", trendState = "INSUFFICIENT_DATA")
            .toDomain()
        assertNotNull(legacy.performanceScore)
        assertEquals(ReliabilityLevel.RELIABLE, legacy.performanceScore!!.reliability)
        assertTrue(legacy.payload.timesMs.isEmpty())
    }
}
