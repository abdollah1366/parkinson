package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.dualtask.DualTaskResultTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Dual-task results (SYNTHETIC values) are stored, read back unchanged and listed in the history. */
@RunWith(RobolectricTestRunner::class)
class DualTaskStorageTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-dual-task.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    @Test
    fun aResultRoundTripsAndAppearsInTheHistory() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AssessmentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = RoomAssessmentRepository.from(db)
        val result = DualTaskResultTest.sample()
        repository.saveDualTask(result)

        assertEquals(result, repository.observeDualTask(result.assessmentId).first())
        val history = repository.observeHistory().first()
        assertTrue(history.any { it.type == AssessmentType.DUAL_TASK && it.assessmentId == result.assessmentId })
        db.close()
    }

    @Test
    fun version14MigratesWithTheNewEmptyTable() {
        migrationHelper.createDatabase(14).apply {
            execSQL(
                "INSERT INTO resting_tremor_imu_assessments (assessmentId, timestampEpochMs, startEpochMs, endEpochMs, hand, " +
                    "plannedDurationMs, gyroscopePresent, qualityStatus, qualityIssues, algorithmVersion, configSummary, " +
                    "scoringVersion) VALUES ('keep', 1700000000000, 1699999985000, 1700000000000, 'LEFT', 15000, 1, " +
                    "'VALID', '', 'rt-imu', 'fixture', 'not-scored')"
            )
            close()
        }
        val v15 = migrationHelper.runMigrationsAndValidate(15)
        v15.prepare("SELECT COUNT(*) FROM resting_tremor_imu_assessments").use {
            assertTrue(it.step())
            assertEquals(1L, it.getLong(0))
        }
        v15.prepare("SELECT COUNT(*) FROM dual_task_assessments").use {
            assertTrue(it.step())
            assertEquals(0L, it.getLong(0))
        }
        v15.close()
    }
}
