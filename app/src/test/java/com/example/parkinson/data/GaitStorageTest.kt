package com.example.parkinson.data

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.example.parkinson.assessment.AssessmentType
import com.example.parkinson.gait.GaitEngine
import com.example.parkinson.gait.GaitRecording
import com.example.parkinson.gait.GaitResult
import com.example.parkinson.gait.PoseFeatures
import com.example.parkinson.gait.PoseFrame
import com.example.parkinson.gait.PoseFrameStatus
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
import kotlin.math.PI
import kotlin.math.sin

/** Results come from SYNTHETIC pose series (test input only). */
@RunWith(RobolectricTestRunner::class)
class GaitStorageTest {

    private lateinit var db: AssessmentDatabase
    private lateinit var repository: RoomAssessmentRepository

    /** A walk at 0.8333 Hz stride frequency, 15 fps, 20 s. */
    private fun syntheticWalk(): GaitRecording {
        val stepMs = 1000.0 / 15.0
        val frames = (0 until 301).map { k ->
            val t = k * stepMs / 1000.0
            val phase = 2 * PI * 0.8333 * t
            PoseFrame(
                (k * stepMs).toLong(),
                PoseFrameStatus.VALID,
                PoseFeatures(128.0, 2.0, 0.3 * sin(phase), -0.3 * sin(phase), 2.0, 2.0, 0.0, 0.8 * sin(phase)),
            )
        }
        return GaitRecording(frames, 20_000L)
    }

    private fun result(id: String, time: Long): GaitResult {
        val engine = GaitEngine()
        val recording = syntheticWalk()
        val analysis = engine.analyze(recording)
        return GaitResult.from(
            analysis = analysis,
            metrics = requireNotNull(analysis.metrics),
            frames = recording.frames,
            assessmentId = id,
            startEpochMs = time - 20_000L,
            endEpochMs = time,
            plannedDurationMs = 20_000L,
            signal = engine.signal,
        )
    }

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-test-8.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    /** Version 7 -> 8 only adds gait_assessments; the migrated schema must equal 8.json and keep older rows. */
    @Test
    fun migrationFromVersion7AddsTheTableAndValidatesAgainstTheSchema() {
        migrationHelper.createDatabase(7).close()
        val v8 = migrationHelper.runMigrationsAndValidate(8)
        v8.prepare("SELECT COUNT(*) FROM gait_assessments").use {
            it.step()
            assertEquals(0L, it.getLong(0))
        }
        v8.close()
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
    fun resultRoundTripKeepsMetricsQualityAndSeries() {
        val original = result("gait-1", 1_700_000_000_000L)
        runBlocking { repository.saveGait(original) }

        val back = requireNotNull(runBlocking { repository.observeGait("gait-1").first() })
        assertEquals(original.metrics.stepCount, back.metrics.stepCount)
        assertEquals(original.metrics.stepTimesMs, back.metrics.stepTimesMs)
        assertEquals(original.metrics.cadenceStepsPerMinute, back.metrics.cadenceStepsPerMinute)
        assertEquals(original.metrics.stepIntervalCvPercent, back.metrics.stepIntervalCvPercent)
        assertEquals(original.metrics.trunkLeanMeanDeg, back.metrics.trunkLeanMeanDeg)
        assertEquals(original.metrics.leftArmSwingRangeTorso, back.metrics.leftArmSwingRangeTorso)
        assertEquals(original.qualityStatus, back.qualityStatus)
        assertEquals(original.qualityIssues, back.qualityIssues)
        assertEquals(original.qualityScore, back.qualityScore)
        assertEquals(original.startEpochMs, back.startEpochMs)
        assertEquals(original.endEpochMs, back.endEpochMs)
        assertEquals(original.algorithmVersion, back.algorithmVersion)
        assertEquals(original.configSummary, back.configSummary)
        assertEquals(original.series.frameStatuses, back.series.frameStatuses)
        assertEquals(original.series.frameTimesMs, back.series.frameTimesMs)
        original.series.ankleSeparationTorso.zip(back.series.ankleSeparationTorso).forEach { (a, b) -> assertEquals(a, b, 0.0001) }
        assertTrue(!back.plantarPressureMeasured)
    }

    @Test
    fun unavailableMetricsStayNullAfterStorage() {
        // Wrists missing in every frame: the arm-swing metrics must come back as null, not as zero.
        val recording = syntheticWalk()
        val frames = recording.frames.map { f -> f.copy(features = f.features?.copy(leftWristOffset = null, rightWristOffset = null)) }
        val engine = GaitEngine()
        val analysis = engine.analyze(GaitRecording(frames, 20_000L))
        val original = GaitResult.from(
            analysis = analysis,
            metrics = requireNotNull(analysis.metrics),
            frames = frames,
            assessmentId = "gait-nowrist",
            startEpochMs = 0L,
            endEpochMs = 20_000L,
            plannedDurationMs = 20_000L,
            signal = engine.signal,
        )
        runBlocking { repository.saveGait(original) }
        val back = requireNotNull(runBlocking { repository.observeGait("gait-nowrist").first() })
        assertNull(back.metrics.leftArmSwingRangeTorso)
        assertNull(back.metrics.rightArmSwingRangeTorso)
    }

    @Test
    fun historyIncludesTheGaitResultNewestFirst() {
        runBlocking {
            repository.saveGait(result("gait-old", 1_000L))
            repository.saveGait(result("gait-new", 2_000L))
        }
        val history = runBlocking { repository.observeHistory().first() }
        val gait = history.filter { it.type == AssessmentType.GAIT }
        assertEquals(listOf("gait-new", "gait-old"), gait.map { it.assessmentId })
        assertTrue(history.zipWithNext().all { (a, b) -> a.timestampEpochMs >= b.timestampEpochMs })
    }
}
