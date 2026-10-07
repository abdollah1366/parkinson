package com.example.parkinson.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.parkinson.model.SelectedHand
import com.example.parkinson.tapping.FingerTappingAnalyzer
import com.example.parkinson.tapping.SyntheticTapping
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AssessmentDaoTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository

    private val analysis = FingerTappingAnalyzer().analyze(SyntheticTapping().regular(3.0).recording())
    private fun assessment(id: String, time: Long) =
        FingerTappingAnalyzer.toAssessment(analysis, id, time, SelectedHand.RIGHT)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), AssessmentDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = RoomAssessmentRepository(db.assessmentDao())
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun emptyDatabase() = runTest {
        assertNull(repository.observeLatest().first())
        assertEquals(emptyList<Any>(), repository.observeAll().first())
        assertNull(repository.observe("missing").first())
    }

    @Test
    fun savesAndReadsBackNewestFirst() = runTest {
        val older = assessment("a", 1_000L)
        val newer = assessment("b", 2_000L)
        repository.save(older)
        repository.save(newer)

        assertEquals(newer, repository.observeLatest().first())
        assertEquals(listOf(newer, older), repository.observeAll().first())
        assertEquals(older, repository.observe("a").first())
    }

    @Test
    fun savingTheSameIdReplaces() = runTest {
        repository.save(assessment("a", 1_000L))
        repository.save(assessment("a", 5_000L))
        assertEquals(1, repository.observeAll().first().size)
        assertEquals(5_000L, repository.observe("a").first()!!.timestampEpochMs)
    }
}
