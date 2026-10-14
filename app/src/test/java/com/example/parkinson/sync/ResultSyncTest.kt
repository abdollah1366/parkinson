package com.example.parkinson.sync

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.parkinson.data.AssessmentDatabase
import com.example.parkinson.data.ResultRowReader
import com.example.parkinson.data.RoomAssessmentRepository
import com.example.parkinson.data.RoomProfileRepository
import com.example.parkinson.dualtask.DualTaskResultTest
import com.example.parkinson.medication.MedicationState
import com.example.parkinson.profile.Gender
import com.example.parkinson.profile.ProfileValidationTest
import com.example.parkinson.profile.UserProfile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Sending results to the doctor's server (a fake one), with made-up people and results. */
@RunWith(RobolectricTestRunner::class)
class ResultSyncTest {

    private val db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AssessmentDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val profiles = RoomProfileRepository.from(db)
    private val active = MutableStateFlow<Int?>(null)
    private val medication = MutableStateFlow<MedicationState?>(null)
    private val results = RoomAssessmentRepository.from(db, active) { medication.value }
    private val api = FakeServerApi()
    private val store = InMemoryServerLinkStore()
    private var now = 1_760_000_000_000L
    private val sync = ResultSync(api, store, ResultRowReader(db)::rows) { now }

    @After
    fun close() = db.close()

    private suspend fun person(sample: UserProfile = ProfileValidationTest.sample()): UserProfile {
        val id = profiles.save(sample)
        return profiles.observeAll().first().single { it.id == id }
    }

    private suspend fun saveFor(person: UserProfile, vararg ids: String) {
        active.value = person.id
        for (id in ids) results.saveDualTask(DualTaskResultTest.sample().copy(assessmentId = id))
    }

    private suspend fun historyOf(person: UserProfile) = run {
        active.value = person.id
        results.observeStoredHistory().first()
    }

    @Test
    fun nothingIsSentForSomeoneWhoIsNotConnected() = runBlocking {
        val ali = person()
        saveFor(ali, "a1")
        assertEquals(0, sync.sync(ali, historyOf(ali)))
        assertTrue(api.uploads.isEmpty() && api.profiles.isEmpty())
    }

    @Test
    fun linkingSendsTheProfileAndSyncSendsEachResultOnce() = runBlocking {
        val ali = person()
        medication.value = MedicationState.OFF
        saveFor(ali, "a1", "a2")

        val link = sync.link("https://panel.example.ir", "ABCD2345", ali)
        assertEquals("دکتر آزمایشی", link.doctorName)
        val (code, patient) = api.linked.single()
        assertEquals("ABCD2345", code)
        assertEquals(ali.nationalId, patient.getString("nationalId"))
        assertEquals("male", patient.getString("gender"))

        assertEquals(2, sync.pendingCount(ali.id, historyOf(ali)))
        assertEquals(2, sync.sync(ali, historyOf(ali)))
        assertEquals(setOf("a1", "a2"), api.uploadedIds.toSet())
        assertEquals(0, sync.pendingCount(ali.id, historyOf(ali)))
        assertEquals(0, sync.sync(ali, historyOf(ali)))
        assertEquals(1, api.uploads.size)

        val sent = api.uploads.single().first()
        assertEquals("dual_task", sent.getString("type"))
        assertEquals("off", sent.getString("medicationState"))
        val metrics = sent.getJSONObject("metrics")
        assertEquals("dual_task_assessments", metrics.getString("_table"))
        assertTrue(metrics.has("protocolId"))
        assertFalse(metrics.has("profileId") || metrics.has("medicationState") || metrics.has("assessmentId"))
        assertEquals(now, store.links.value.getValue(ali.id).lastSyncEpochMs)
    }

    @Test
    fun anotherPersonsResultsAreNeverSent() = runBlocking {
        val ali = person()
        val maryam = person(
            ProfileValidationTest.sample().copy(firstName = "مریم", gender = Gender.FEMALE, nationalId = "0013542427"),
        )
        saveFor(ali, "ali-1")
        saveFor(maryam, "maryam-1")
        sync.link("https://panel.example.ir", "ABCD2345", ali)

        // Even if the list handed over were the wrong person's, only rows stored under Ali go out.
        sync.sync(ali, historyOf(maryam) + historyOf(ali))
        assertEquals(listOf("ali-1"), api.uploadedIds)
    }

    @Test
    fun aProfileEditIsSentWithTheNextSync() = runBlocking {
        val ali = person()
        sync.link("https://panel.example.ir", "ABCD2345", ali)
        sync.sync(ali, emptyList())
        assertTrue(api.profiles.isEmpty())

        val edited = ali.copy(age = ali.age + 1, updatedEpochMs = ali.updatedEpochMs + 1)
        sync.sync(edited, emptyList())
        assertEquals(edited.age, api.profiles.single().getInt("age"))
        sync.sync(edited, emptyList())
        assertEquals(1, api.profiles.size)
    }

    @Test
    fun aFailedSendIsRecordedAndRetriedLater() = runBlocking {
        val ali = person()
        saveFor(ali, "a1")
        sync.link("https://panel.example.ir", "ABCD2345", ali)
        api.failWith = ServerException.NETWORK
        try {
            sync.sync(ali, historyOf(ali))
            fail("expected a network error")
        } catch (e: ServerException) {
            assertEquals(ServerException.NETWORK, e.code)
        }
        assertEquals(ServerException.NETWORK, store.links.value.getValue(ali.id).lastError)
        assertEquals(1, sync.pendingCount(ali.id, historyOf(ali)))

        api.failWith = null
        assertEquals(1, sync.sync(ali, historyOf(ali)))
        assertNull(store.links.value.getValue(ali.id).lastError)
    }

    @Test
    fun manyResultsGoInSeveralRequests() = runBlocking {
        val ali = person()
        saveFor(ali, *Array(45) { "r$it" })
        sync.link("https://panel.example.ir", "ABCD2345", ali)
        assertEquals(45, sync.sync(ali, historyOf(ali)))
        assertEquals(listOf(20, 20, 5), api.uploads.map { it.size })
    }

    @Test
    fun unlinkingTellsTheServerAndForgetsTheLink() = runBlocking {
        val ali = person()
        saveFor(ali, "a1")
        sync.link("https://panel.example.ir", "ABCD2345", ali)
        sync.sync(ali, historyOf(ali))
        sync.unlink(ali.id)
        assertEquals(listOf("token-1"), api.revoked)
        assertNull(store.links.value[ali.id])
        assertEquals(1, sync.pendingCount(ali.id, historyOf(ali)))

        // Offline: still disconnected on the phone.
        sync.link("https://panel.example.ir", "WXYZ2345", ali)
        api.failWith = ServerException.NETWORK
        sync.unlink(ali.id)
        assertNull(store.links.value[ali.id])
    }

    @Test
    fun theWebPanelPasswordGoesToTheLinkedServerOnly() = runBlocking {
        val ali = person()
        try {
            sync.setWebPassword(ali.id, "patient-pass")
            fail("not linked yet")
        } catch (e: ServerException) {
            assertEquals("not_linked", e.code)
        }
        sync.link("https://panel.example.ir", "ABCD2345", ali)
        sync.setWebPassword(ali.id, "patient-pass")
        assertEquals(listOf("patient-pass"), api.webPasswords)
    }
}
