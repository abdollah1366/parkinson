package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.dualtask.DualTaskResultTest
import com.example.parkinson.profile.Gender
import com.example.parkinson.profile.ProfileValidationTest
import com.example.parkinson.profile.UserProfile
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

/**
 * Registrations (made-up values): several people on one phone, each result stored for the person who was active,
 * a person's results removed with them, and the update from the one-person version (v16 -> v17).
 */
@RunWith(RobolectricTestRunner::class)
class UserProfileStorageTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-profile.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    private fun memoryDb() =
        Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AssessmentDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    private fun secondPerson(): UserProfile = ProfileValidationTest.sample().copy(
        firstName = "مریم", lastName = "احمدی", gender = Gender.FEMALE, nationalId = "0013542427", mobile = "09351234567",
    )

    @Test
    fun saveEditAndDelete() = runBlocking {
        val db = memoryDb()
        val repository = RoomProfileRepository.from(db)
        assertTrue(repository.observeAll().first().isEmpty())

        val id = repository.save(ProfileValidationTest.sample())
        val saved = ProfileValidationTest.sample().copy(id = id)
        assertEquals(listOf(saved), repository.observeAll().first())

        val edited = saved.copy(age = 61, takesParkinsonMedication = false)
        assertEquals(id, repository.save(edited))
        assertEquals(listOf(edited), repository.observeAll().first())

        repository.delete(id)
        assertTrue(repository.observeAll().first().isEmpty())
        db.close()
    }

    @Test
    fun eachPersonSeesOnlyTheirOwnResults() = runBlocking {
        val db = memoryDb()
        val profiles = RoomProfileRepository.from(db)
        val active = MutableStateFlow<Int?>(null)
        val results = RoomAssessmentRepository.from(db, active)
        val ali = profiles.save(ProfileValidationTest.sample())
        val maryam = profiles.save(secondPerson())
        assertEquals(2, profiles.observeAll().first().size)

        val first = DualTaskResultTest.sample().copy(assessmentId = "ali-1")
        val second = DualTaskResultTest.sample().copy(assessmentId = "maryam-1")
        active.value = ali
        results.saveDualTask(first)
        active.value = maryam
        results.saveDualTask(second)

        assertEquals(listOf("maryam-1"), results.observeHistory().first().map { it.assessmentId })
        active.value = ali
        assertEquals(listOf("ali-1"), results.observeHistory().first().map { it.assessmentId })

        // Deleting a person removes their results and nobody else's.
        profiles.delete(ali)
        assertNull(results.observeDualTask("ali-1").first())
        assertEquals(second, results.observeDualTask("maryam-1").first())
        assertEquals(listOf(maryam), profiles.observeAll().first().map { it.id })
        db.close()
    }

    @Test
    fun resultsSavedBeforeAnyoneRegisteredGoToTheFirstPerson() = runBlocking {
        val db = memoryDb()
        val profiles = RoomProfileRepository.from(db)
        val active = MutableStateFlow<Int?>(null)
        val results = RoomAssessmentRepository.from(db, active)
        results.saveDualTask(DualTaskResultTest.sample())

        val first = profiles.save(ProfileValidationTest.sample())
        val second = profiles.save(secondPerson())
        active.value = first
        assertEquals(listOf("dual-1"), results.observeHistory().first().map { it.assessmentId })
        active.value = second
        assertTrue(results.observeHistory().first().isEmpty())
        db.close()
    }

    @Test
    fun version16ResultsGoToTheRegisteredPerson() {
        migrationHelper.createDatabase(16).apply {
            execSQL(
                "INSERT INTO user_profile (id, firstName, lastName, age, gender, mobile, nationalId, parkinsonDiagnosed, " +
                    "takesParkinsonMedication, dominantHand, consentEpochMs, updatedEpochMs) VALUES (1, 'Test', 'Person', " +
                    "60, 'male', '09121234567', '0013542419', 1, 1, 'right', 1700000000000, 1700000000000)"
            )
            execSQL(
                "INSERT INTO resting_tremor_imu_assessments (assessmentId, timestampEpochMs, startEpochMs, endEpochMs, hand, " +
                    "plannedDurationMs, gyroscopePresent, qualityStatus, qualityIssues, algorithmVersion, configSummary, " +
                    "scoringVersion) VALUES ('keep', 1700000000000, 1699999985000, 1700000000000, 'LEFT', 15000, 1, " +
                    "'VALID', '', 'rt-imu', 'fixture', 'not-scored')"
            )
            close()
        }
        val v17 = migrationHelper.runMigrationsAndValidate(17, listOf(MIGRATION_16_17))
        v17.prepare("SELECT profileId FROM resting_tremor_imu_assessments WHERE assessmentId = 'keep'").use {
            assertTrue(it.step())
            assertEquals(1L, it.getLong(0))
        }
        v17.close()
    }

    @Test
    fun version16ResultsWithoutARegistrationStayUnowned() {
        migrationHelper.createDatabase(16).apply {
            execSQL(
                "INSERT INTO resting_tremor_imu_assessments (assessmentId, timestampEpochMs, startEpochMs, endEpochMs, hand, " +
                    "plannedDurationMs, gyroscopePresent, qualityStatus, qualityIssues, algorithmVersion, configSummary, " +
                    "scoringVersion) VALUES ('keep', 1700000000000, 1699999985000, 1700000000000, 'LEFT', 15000, 1, " +
                    "'VALID', '', 'rt-imu', 'fixture', 'not-scored')"
            )
            close()
        }
        val v17 = migrationHelper.runMigrationsAndValidate(17, listOf(MIGRATION_16_17))
        v17.prepare("SELECT profileId FROM resting_tremor_imu_assessments WHERE assessmentId = 'keep'").use {
            assertTrue(it.step())
            assertTrue(it.isNull(0))
        }
        v17.close()
    }
}
