package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.assessment.QualityStatus
import com.example.parkinson.speech.SpeechAnalysis
import com.example.parkinson.speech.SpeechComparison
import com.example.parkinson.speech.SpeechMetric
import com.example.parkinson.speech.SpeechResult
import com.example.parkinson.speech.SpeechSignalConfig
import com.example.parkinson.speech.SpeechTask
import com.example.parkinson.speech.SpeechTaskEngine
import com.example.parkinson.speech.SpeechValue
import com.example.parkinson.speech.SyntheticSpeech
import com.example.parkinson.speech.UnavailableReason
import com.example.parkinson.speech.previousSpeech
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/** Speech results built from SYNTHETIC audio (test input only). */
@RunWith(RobolectricTestRunner::class)
class SpeechStorageTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository

    private fun result(id: String, endEpochMs: Long, task: SpeechTask = SpeechTask.SUSTAINED_VOWEL): SpeechResult {
        val capture = SyntheticSpeech.capture(SyntheticSpeech.tone(150.0, 5_000, amp = 0.3, rate = 8_000), rate = 8_000, requested = 16_000)
        val analysis: SpeechAnalysis = SpeechTaskEngine(SpeechSignalConfig()).analyze(task, capture, plannedDurationMs = 5_000L)
        return SpeechResult.from(
            analysis = analysis,
            capture = capture,
            assessmentId = id,
            sessionId = "visit-$id",
            startEpochMs = endEpochMs - 5_000L,
            endEpochMs = endEpochMs,
            consentAccepted = true,
            signal = SpeechSignalConfig(),
            plannedDurationMs = 5_000L,
        )
    }

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-test-10.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    /** Version 9 -> 10 only adds speech_assessments; the migrated schema must equal 10.json. */
    @Test
    fun migrationFromVersion9AddsTheTableAndValidatesAgainstTheSchema() {
        migrationHelper.createDatabase(9).close()
        val v10 = migrationHelper.runMigrationsAndValidate(10)
        v10.prepare("SELECT COUNT(*) FROM speech_assessments").use {
            it.step()
            assertEquals(0L, it.getLong(0))
        }
        v10.close()
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
    fun roundTripKeepsMeasuredValuesUnavailableReasonsAndTheActualSampleRate() {
        val original = result("sp-1", 1_700_000_000_000L)
        runBlocking { repository.saveSpeech(original) }
        val back = requireNotNull(runBlocking { repository.observeSpeech("sp-1").first() })

        assertEquals(original.task, back.task)
        assertEquals(8_000, back.sampleRateHz)
        assertEquals(16_000, back.requestedSampleRateHz)
        assertEquals(original.qualityStatus, back.qualityStatus)
        assertEquals(original.qualityIssues, back.qualityIssues)
        assertEquals(original.sessionId, back.sessionId)
        assertTrue(back.consentAccepted)
        assertEquals(original.metrics, back.metrics)
        // Unavailable metrics stay unavailable with their reason: never a stored zero.
        assertEquals(
            SpeechValue.Unavailable(UnavailableReason.CYCLE_MEASUREMENT_NOT_RELIABLE),
            back.metrics[SpeechMetric.JITTER_PERCENT],
        )
        assertNull(back.measured(SpeechMetric.JITTER_PERCENT))
        assertEquals(original.measured(SpeechMetric.F0_MEDIAN_HZ), back.measured(SpeechMetric.F0_MEDIAN_HZ))
    }

    @Test
    fun noRawAudioFieldExistsInTheStoredRow() {
        // The stored row has only measurements, quality findings and versions: no samples, no transcript.
        val row = result("sp-privacy", 1_700_000_000_000L).toEntity()
        val fields = SpeechEntity::class.java.declaredFields.map { it.name }
        assertTrue(fields.none { it.contains("audio", ignoreCase = true) || it.contains("transcript", ignoreCase = true) })
        assertEquals(SpeechTask.SUSTAINED_VOWEL.id, row.task)
    }

    @Test
    fun resavingTheSameAssessmentReplacesTheRowInsteadOfDuplicatingIt() {
        runBlocking {
            repository.saveSpeech(result("sp-dup", 1_000L))
            repository.saveSpeech(result("sp-dup", 1_000L))
        }
        val all = runBlocking { repository.observeAllSpeech().first() }
        assertEquals(1, all.size)
    }

    @Test
    fun historyIncludesSpeechNewestFirst() {
        runBlocking {
            repository.saveSpeech(result("sp-old", 1_000L))
            repository.saveSpeech(result("sp-new", 2_000L))
        }
        val history = runBlocking { repository.observeHistory().first() }
        val speech = history.filter { it.type == AssessmentType.SPEECH }
        assertEquals(listOf("sp-new", "sp-old"), speech.map { it.assessmentId })
        assertTrue(history.zipWithNext().all { (a, b) -> a.timestampEpochMs >= b.timestampEpochMs })
    }

    @Test
    fun previousResultIsTheEarlierResultOfTheSameTaskOnly() {
        val vowelOld = result("v-old", 1_000L, SpeechTask.SUSTAINED_VOWEL)
        val readingMid = result("r-mid", 2_000L, SpeechTask.READING)
        val vowelNew = result("v-new", 3_000L, SpeechTask.SUSTAINED_VOWEL)
        val all = listOf(vowelNew, readingMid, vowelOld)
        assertEquals("v-old", previousSpeech(vowelNew, all)?.assessmentId)
        assertNull(previousSpeech(readingMid, all))
        assertEquals("v-old", SpeechComparison(vowelNew, vowelOld).previous?.assessmentId)
    }

    @Test
    fun aStoredRowWithAnUnknownMetricNameIsIgnoredNotGuessed() {
        val entity = result("sp-unknown", 1_000L).toEntity().copy(metrics = "NOT_A_METRIC=12.0;DURATION_MS=5000.0")
        val back = entity.toDomain()
        assertEquals(5_000.0, back.measured(SpeechMetric.DURATION_MS)!!, 1e-6)
        // Only the entries present in the row are decoded; the rest are absent, not invented.
        assertEquals(1, back.metrics.size)
    }
}
