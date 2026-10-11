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
 * Walks the whole upgrade path from the first released schema (v1) to the current one (v6), one
 * version at a time. Each step validates the migrated schema against its exported JSON, and a row
 * written at v1 must still be there, with its values, at the end. Synthetic values only.
 */
@RunWith(RobolectricTestRunner::class)
class AssessmentMigrationChainTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-chain-test.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    @Test
    fun v1RowSurvivesEveryMigrationUpToVersion6() {
        // Version 1 only has finger_tapping_assessments. Columns not listed here are nullable.
        val v1 = migrationHelper.createDatabase(1)
        v1.execSQL(
            "INSERT INTO finger_tapping_assessments (assessmentId, timestampEpochMs, hand, plannedDurationMs, " +
                "recordingDurationMs, tapCount, tapRateHz, tapsPer10Seconds, pauseCount, fps, validFramePercent, " +
                "dropoutCount, dropoutDurationMs, longestDropoutMs, recordingCompletenessPercent, qualityStatus, " +
                "qualityIssues, qualityScore, algorithmVersion, scoringVersion) VALUES ('chain-ft', 1700000000000, 'RIGHT', " +
                "10000, 10000, 30, 3.0, 30.0, 0, 30.0, 100.0, 0, 0, 0, 100.0, 'VALID', '', 90, " +
                "'ft-algo-1.0.0', 'ft-score-0.1.0-preliminary')"
        )
        v1.close()

        // Each step validates the migrated schema against the exported JSON for that version.
        // Every intermediate step is closed; the last one (version 6) is kept open for the checks.
        var current = migrationHelper.runMigrationsAndValidate(2)
        for (version in 3..6) {
            current.close()
            current = migrationHelper.runMigrationsAndValidate(version)
        }
        val v6 = current
        v6.prepare(
            "SELECT hand, tapCount, tapRateHz, qualityStatus, qualityScore, scoreTotal, scoreAmplitudeTrend, " +
                "algorithmVersion FROM finger_tapping_assessments WHERE assessmentId = 'chain-ft'"
        ).use {
            assertTrue("v1 row was lost during the upgrade", it.step())
            assertEquals("RIGHT", it.getText(0))
            assertEquals(30L, it.getLong(1))
            assertEquals(3.0, it.getDouble(2), 1e-9)
            assertEquals("VALID", it.getText(3))
            assertEquals(90L, it.getLong(4))
            // Columns added after v1 must be NULL, never a fabricated 0.
            assertTrue(it.isNull(5))
            assertTrue(it.isNull(6))
            assertEquals("ft-algo-1.0.0", it.getText(7))
            assertFalse(it.step())
        }
        // The later tables exist and are empty; nothing was copied into them.
        v6.prepare("SELECT COUNT(*) FROM hand_stability_assessments").use {
            it.step()
            assertEquals(0L, it.getLong(0))
        }
        v6.prepare("SELECT COUNT(*) FROM hand_open_close_assessments").use {
            it.step()
            assertEquals(0L, it.getLong(0))
        }
        v6.close()
    }
}
