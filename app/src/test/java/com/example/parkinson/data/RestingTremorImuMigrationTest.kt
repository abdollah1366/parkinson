package com.example.parkinson.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Upgrade path 1 -> 13 with real rows at the old versions. Version 13 adds only resting_tremor_imu_assessments
 * (automatic migration 12 -> 13); the camera table and every existing row must survive unchanged. Synthetic values.
 */
@RunWith(RobolectricTestRunner::class)
class RestingTremorImuMigrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-tremor-imu.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    @Test
    fun existingRowsSurviveTheUpgradeToVersion13AndTheImuTableStartsEmpty() {
        // Version 1: a finger-tapping row with the version-1 columns only.
        migrationHelper.createDatabase(1).apply {
            execSQL(
                "INSERT INTO finger_tapping_assessments (assessmentId, timestampEpochMs, hand, plannedDurationMs, " +
                    "recordingDurationMs, tapCount, tapRateHz, tapsPer10Seconds, pauseCount, fps, validFramePercent, " +
                    "dropoutCount, dropoutDurationMs, longestDropoutMs, recordingCompletenessPercent, qualityStatus, " +
                    "qualityIssues, qualityScore, algorithmVersion, scoringVersion) VALUES ('keep-ft', 1700000000000, 'RIGHT', " +
                    "10000, 10000, 30, 3.0, 30.0, 0, 30.0, 100.0, 0, 0, 0, 100.0, 'VALID', '', 90, " +
                    "'ft-algo-1.0.0', 'ft-score-0.1.0-preliminary')"
            )
            close()
        }

        // Every version up to 12 is migrated and validated in turn; the manual 11 -> 12 step is passed explicitly.
        for (version in 2..11) migrationHelper.runMigrationsAndValidate(version).close()
        val v12 = migrationHelper.runMigrationsAndValidate(12, listOf(MIGRATION_11_12))
        // Version 12: a camera resting-tremor row (every NOT NULL column of the version-12 schema).
        v12.execSQL(
            "INSERT INTO resting_tremor_assessments (assessmentId, timestampEpochMs, startEpochMs, endEpochMs, hand, " +
                "plannedDurationMs, actualDurationMs, totalFrames, validFrames, noHandFrames, outOfFrameFrames, " +
                "multipleHandsFrames, wrongHandFrames, errorFrames, validFramePercent, interruptionCount, " +
                "interruptionTotalMs, longestInterruptionMs, medianFrameIntervalMs, frameRateHz, irregularIntervalPercent, " +
                "medianHandScalePx, signalCoveragePercent, slowDriftPercent, amplitudeRmsPercent, dominantFrequencyHz, " +
                "spectralProminence, periodicityPercent, qualityStatus, qualityIssues, qualityScore, frameTimesMs, " +
                "frameStatuses, palmXPx, palmYPx, handScalePx, algorithmVersion, configSummary, scoringVersion) " +
                "VALUES ('keep-tremor', 1700000100000, 1700000085000, 1700000100000, 'LEFT', 15000, 15000, 450, 420, " +
                "10, 5, 0, 0, 0, 93.3, 0, 0, 0, 33.3, 30.0, 1.5, 120.0, 98.0, 0.4, 2.5, 5.0, 4.0, 80.0, 'VALID', '', " +
                "93, '0,33,66', 'VVV', '1.000,2.000,3.000', '1.000,2.000,3.000', '1.000,2.000,3.000', " +
                "'rt-algo-1.0.0', 'grid=20.0Hz', 'not-scored')"
        )
        v12.close()

        val v13 = migrationHelper.runMigrationsAndValidate(13)

        v13.prepare("SELECT hand, qualityStatus, qualityScore FROM finger_tapping_assessments WHERE assessmentId = 'keep-ft'").use {
            // Finger-tapping row unchanged by the upgrade chain (its values were stored at version 1).
            assertTrue(it.step())
            assertEquals("RIGHT", it.getText(0))
            assertEquals("VALID", it.getText(1))
            assertEquals(90L, it.getLong(2))
        }
        v13.prepare("SELECT hand, qualityStatus, validFrames, dominantFrequencyHz, algorithmVersion FROM resting_tremor_assessments WHERE assessmentId = 'keep-tremor'").use {
            assertTrue("camera tremor row lost", it.step())
            assertEquals("LEFT", it.getText(0))
            assertEquals("VALID", it.getText(1))
            assertEquals(420L, it.getLong(2))
            assertEquals(5.0, it.getDouble(3), 1e-9)
            assertEquals("rt-algo-1.0.0", it.getText(4))
            assertFalse(it.step())
        }
        v13.prepare("SELECT COUNT(*) FROM resting_tremor_imu_assessments").use {
            assertTrue(it.step())
            assertEquals(0L, it.getLong(0))
        }
        v13.close()
    }
}
