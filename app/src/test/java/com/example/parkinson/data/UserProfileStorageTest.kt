package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.profile.ProfileValidationTest
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

/** The registration (made-up values) is stored as one row, replaced on edit and removed on delete. */
@RunWith(RobolectricTestRunner::class)
class UserProfileStorageTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-profile.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    @Test
    fun saveEditAndDelete() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AssessmentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val repository = RoomProfileRepository.from(db)
        assertNull(repository.observe().first())

        val profile = ProfileValidationTest.sample()
        repository.save(profile)
        assertEquals(profile, repository.observe().first())

        val edited = profile.copy(age = 61, takesParkinsonMedication = false)
        repository.save(edited)
        assertEquals(edited, repository.observe().first())

        repository.delete()
        assertNull(repository.observe().first())
        db.close()
    }

    @Test
    fun version15MigratesWithAnEmptyProfileTable() {
        migrationHelper.createDatabase(15).apply {
            execSQL(
                "INSERT INTO resting_tremor_imu_assessments (assessmentId, timestampEpochMs, startEpochMs, endEpochMs, hand, " +
                    "plannedDurationMs, gyroscopePresent, qualityStatus, qualityIssues, algorithmVersion, configSummary, " +
                    "scoringVersion) VALUES ('keep', 1700000000000, 1699999985000, 1700000000000, 'LEFT', 15000, 1, " +
                    "'VALID', '', 'rt-imu', 'fixture', 'not-scored')"
            )
            close()
        }
        val v16 = migrationHelper.runMigrationsAndValidate(16)
        v16.prepare("SELECT COUNT(*) FROM resting_tremor_imu_assessments").use {
            assertTrue(it.step())
            assertEquals(1L, it.getLong(0))
        }
        v16.prepare("SELECT COUNT(*) FROM user_profile").use {
            assertTrue(it.step())
            assertEquals(0L, it.getLong(0))
        }
        v16.close()
    }
}
