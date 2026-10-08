package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.stability.HandStabilityEngine
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.stability.SyntheticMotion
import com.example.parkinson.tapping.FingerTappingAnalyzer
import com.example.parkinson.tapping.SyntheticTapping
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class HandStabilityStorageTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository

    private fun stability(id: String, time: Long, motion: SyntheticMotion = SyntheticMotion().oscillation(5.0, 8.0, 0.2)) =
        HandStabilityResult.from(HandStabilityEngine().analyze(motion.recording()), id, time, SelectedHand.LEFT, 15_000L)

    private val tapping = FingerTappingAnalyzer().analyze(SyntheticTapping().regular(3.0).recording())

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
        val valid = stability("a", 1L)
        assertEquals(valid, valid.toEntity().toDomain())
        val lowQuality = stability("b", 2L, SyntheticMotion().gap(5_000, 5_400))
        assertEquals(QualityStatus.LOW_QUALITY, lowQuality.qualityStatus)
        assertNull(lowQuality.stabilityIndex)
        assertEquals(lowQuality, lowQuality.toEntity().toDomain())
    }

    @Test
    fun unknownStoredNamesDegradeSafely() {
        val entity = stability("a", 1L).toEntity().copy(qualityStatus = "FUTURE", qualityIssues = "X,GAPS_PRESENT", hand = "?")
        val r = entity.toDomain()
        assertEquals(QualityStatus.INVALID, r.qualityStatus)
        assertEquals(1, r.qualityIssues.size)
        assertEquals(SelectedHand.RIGHT, r.hand)
    }

    @Test
    fun savesAndReadsBack() = runTest {
        val r = stability("hs", 5_000L)
        repository.saveHandStability(r)
        assertEquals(r, repository.observeHandStability("hs").first())
        assertNull(repository.observeHandStability("missing").first())
    }

    @Test
    fun historyMixesTypesNewestFirst() = runTest {
        repository.save(FingerTappingAnalyzer.toAssessment(tapping, "ft-old", 1_000L, SelectedHand.RIGHT))
        repository.saveHandStability(stability("hs-mid", 2_000L))
        repository.save(FingerTappingAnalyzer.toAssessment(tapping, "ft-new", 3_000L, SelectedHand.LEFT))

        val history = repository.observeHistory().first()
        assertEquals(listOf("ft-new", "hs-mid", "ft-old"), history.map { it.assessmentId })
        assertEquals(
            listOf(AssessmentType.FINGER_TAPPING, AssessmentType.HAND_STABILITY, AssessmentType.FINGER_TAPPING),
            history.map { it.type }
        )
        val hs = history[1]
        assertEquals(SelectedHand.LEFT, hs.hand)
        assertEquals((hs as HandStabilityResult).stabilityIndex?.total, hs.performanceIndex)
    }

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-test.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    @Test
    fun migrationFromVersion1KeepsFingerTappingRowsAndAddsStabilityTable() {
        val v1 = migrationHelper.createDatabase(1)
        val e = FingerTappingAnalyzer.toAssessment(tapping, "kept", 42L, SelectedHand.RIGHT).toEntity()
        v1.execSQL(
            "INSERT INTO finger_tapping_assessments (assessmentId, timestampEpochMs, hand, plannedDurationMs, " +
                "recordingDurationMs, tapCount, tapRateHz, tapsPer10Seconds, pauseCount, fps, validFramePercent, " +
                "dropoutCount, dropoutDurationMs, longestDropoutMs, recordingCompletenessPercent, qualityStatus, " +
                "qualityIssues, qualityScore, algorithmVersion, scoringVersion) VALUES ('kept', 42, 'RIGHT', 10000, " +
                "10000, ${e.tapCount}, ${e.tapRateHz}, ${e.tapsPer10Seconds}, 0, 30.0, 100.0, 0, 0, 0, 100.0, 'VALID', '', " +
                "90, 'ft-algo-1.0.0', 'ft-score-0.1.0-preliminary')"
        )
        v1.close()

        // Validates the migrated schema against 2.json.
        val v2 = migrationHelper.runMigrationsAndValidate(2)
        v2.prepare("SELECT COUNT(*) FROM finger_tapping_assessments WHERE assessmentId = 'kept'").use {
            it.step()
            assertEquals(1L, it.getLong(0))
        }
        v2.prepare("SELECT COUNT(*) FROM hand_stability_assessments").use {
            it.step()
            assertEquals(0L, it.getLong(0))
        }
        v2.close()
    }
}
