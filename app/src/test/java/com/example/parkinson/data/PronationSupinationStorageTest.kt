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
import com.example.parkinson.pronation.PronationSupinationEngine
import com.example.parkinson.pronation.PronationSupinationResult
import com.example.parkinson.pronation.SyntheticRotation
import com.example.parkinson.stability.HandStabilityEngine
import com.example.parkinson.stability.HandStabilityResult
import com.example.parkinson.stability.SyntheticMotion
import com.example.parkinson.tapping.FingerTappingAnalyzer
import com.example.parkinson.tapping.SyntheticTapping
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class PronationSupinationStorageTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository

    private fun pronation(id: String, time: Long, s: SyntheticRotation = SyntheticRotation().sinusoid(1.5, 90.0)) =
        PronationSupinationResult.from(PronationSupinationEngine().analyze(s.recording()), id, time, SelectedHand.RIGHT, 10_000L)

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
        val valid = pronation("a", 1L)
        assertNotNull(valid.performanceScore)
        assertEquals(valid, valid.toEntity().toDomain())

        val lowQuality = pronation("b", 2L, SyntheticRotation().sinusoid(1.5, 90.0).gap(5_000, 5_300))
        assertEquals(QualityStatus.LOW_QUALITY, lowQuality.qualityStatus)
        assertNull(lowQuality.performanceScore)
        assertEquals(lowQuality, lowQuality.toEntity().toDomain())
    }

    @Test
    fun unknownStoredNamesDegradeSafely() {
        val entity = pronation("a", 1L).toEntity()
            .copy(qualityStatus = "FUTURE", qualityIssues = "X,GAPS_PRESENT", hand = "?", ampDirection = "SIDEWAYS")
        val r = entity.toDomain()
        assertEquals(QualityStatus.INVALID, r.qualityStatus)
        assertEquals(1, r.qualityIssues.size)
        assertEquals(SelectedHand.RIGHT, r.hand)
        assertNull(r.trend!!.amplitudeDeg.direction)
    }

    @Test
    fun savesAndReadsBack() = runTest {
        val r = pronation("ps", 5_000L)
        repository.savePronationSupination(r)
        assertEquals(r, repository.observePronationSupination("ps").first())
        assertNull(repository.observePronationSupination("missing").first())
    }

    @Test
    fun historyMixesAllThreeTypesNewestFirst() = runTest {
        val tapping = FingerTappingAnalyzer().analyze(SyntheticTapping().regular(3.0).recording())
        repository.save(FingerTappingAnalyzer.toAssessment(tapping, "ft", 1_000L, SelectedHand.RIGHT))
        repository.saveHandStability(
            HandStabilityResult.from(HandStabilityEngine().analyze(SyntheticMotion().recording()), "hs", 2_000L, SelectedHand.LEFT, 15_000L)
        )
        repository.savePronationSupination(pronation("ps", 3_000L))

        val history = repository.observeHistory().first()
        assertEquals(listOf("ps", "hs", "ft"), history.map { it.assessmentId })
        assertEquals(
            listOf(AssessmentType.PRONATION_SUPINATION, AssessmentType.HAND_STABILITY, AssessmentType.FINGER_TAPPING),
            history.map { it.type }
        )
        val ps = history[0] as PronationSupinationResult
        assertEquals(SelectedHand.RIGHT, ps.hand)
        assertEquals(ps.performanceScore?.total, ps.performanceIndex)
    }

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-test-3.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    @Test
    fun migrationFromVersion2KeepsExistingRowsAndAddsPronationTable() {
        val v2 = migrationHelper.createDatabase(2)
        val hs = HandStabilityResult.from(
            HandStabilityEngine().analyze(SyntheticMotion().recording()), "kept-hs", 7L, SelectedHand.LEFT, 15_000L
        ).toEntity()
        v2.execSQL(
            "INSERT INTO hand_stability_assessments (assessmentId, timestampEpochMs, hand, plannedDurationMs, " +
                "recordingDurationMs, accSampleCount, accSamplingRateHz, accMedianIntervalMs, gyroSampleCount, " +
                "gyroSamplingRateHz, gyroMedianIntervalMs, validSamplePercent, dropoutCount, dropoutDurationMs, " +
                "longestGapMs, completenessPercent, accMagnitudeMean, accMagnitudeSd, accMagnitudeVariance, " +
                "accMagnitudeRange, accDynamicRms, accDynamicVariance, gyroMagnitudeMean, gyroMagnitudeRms, " +
                "gyroMagnitudeSd, gyroMagnitudeVariance, gyroMagnitudeMax, gyroDynamicRms, rotationRangeDeg, " +
                "tiltChangeDeg, dominantFrequencyHz, oscillationBandPowerPercent, frequencyStatus, qualityStatus, " +
                "qualityIssues, qualityScore, indexTotal, indexRotation, indexAcceleration, algorithmVersion, " +
                "scoringVersion) VALUES ('kept-hs', 7, 'LEFT', 15000, 15000, 1500, 100.0, 10.0, 1500, 100.0, 10.0, " +
                "100.0, 0, 0, 10, 100.0, 9.8, 0.01, 0.0001, 0.05, ${hs.accDynamicRms}, 0.0001, 0.2, 0.2, 0.1, 0.01, " +
                "0.5, 0.2, 0.3, 0.5, NULL, NULL, 'MOVEMENT_TOO_SMALL', 'VALID', '', 100, 99, 99, 99, " +
                "'hs-algo-1.0.0', 'hs-score-0.1.0-preliminary')"
        )
        v2.close()

        // Validates the migrated schema against 3.json.
        val v3 = migrationHelper.runMigrationsAndValidate(3)
        v3.prepare("SELECT COUNT(*) FROM hand_stability_assessments WHERE assessmentId = 'kept-hs'").use {
            it.step()
            assertEquals(1L, it.getLong(0))
        }
        v3.prepare("SELECT COUNT(*) FROM pronation_supination_assessments").use {
            it.step()
            assertEquals(0L, it.getLong(0))
        }
        v3.close()
    }
}
