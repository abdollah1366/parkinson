package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.dualtask.DualTaskResultTest
import com.example.parkinson.medication.MedicationState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** The medication state reported before a test (SYNTHETIC values) is saved with it; v17 rows keep NULL. */
@RunWith(RobolectricTestRunner::class)
class MedicationStorageTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-medication.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    @Test
    fun theReportedStateIsStoredWithEachResult() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AssessmentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        var state: MedicationState? = MedicationState.OFF
        val results = RoomAssessmentRepository.from(db, MutableStateFlow(1)) { state }
        results.saveDualTask(DualTaskResultTest.sample().copy(assessmentId = "off"))
        state = null
        results.saveDualTask(DualTaskResultTest.sample().copy(assessmentId = "unknown"))

        val stored = results.observeStoredHistory().first().associate { it.result.assessmentId to it.medicationState }
        assertEquals(MedicationState.OFF, stored["off"])
        assertNull(stored["unknown"])
        assertTrue("unknown" in stored)
        db.close()
    }

    @Test
    fun version17MigratesWithoutAState() {
        migrationHelper.createDatabase(17).apply {
            execSQL(
                "INSERT INTO resting_tremor_imu_assessments (assessmentId, timestampEpochMs, startEpochMs, endEpochMs, hand, " +
                    "plannedDurationMs, gyroscopePresent, qualityStatus, qualityIssues, algorithmVersion, configSummary, " +
                    "scoringVersion, profileId) VALUES ('keep', 1700000000000, 1699999985000, 1700000000000, 'LEFT', 15000, 1, " +
                    "'VALID', '', 'rt-imu', 'fixture', 'not-scored', 1)"
            )
            close()
        }
        val v18 = migrationHelper.runMigrationsAndValidate(18, listOf(MIGRATION_17_18))
        v18.prepare("SELECT profileId, medicationState FROM resting_tremor_imu_assessments WHERE assessmentId = 'keep'").use {
            assertTrue(it.step())
            assertEquals(1L, it.getLong(0))
            assertTrue(it.isNull(1))
        }
        v18.close()
    }
}
