package com.example.parkinson.data

import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.sqlite.execSQL
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Version 13 -> 14 only adds nullable stride, turn and tremor columns to imu_gait_assessments. A walking row stored at
 * version 13 must keep its values, and its new columns must be NULL (not measured), never zero. Synthetic values.
 */
@RunWith(RobolectricTestRunner::class)
class GaitScoreMigrationTest {

    @get:Rule
    val migrationHelper = MigrationTestHelper(
        instrumentation = InstrumentationRegistry.getInstrumentation(),
        file = File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "migration-gait-score.db"),
        driver = AndroidSQLiteDriver(),
        databaseClass = AssessmentDatabase::class
    )

    @Test
    fun aWalkingRowFromVersion13KeepsItsValuesAndHasNoNewMeasures() {
        migrationHelper.createDatabase(13).apply {
            execSQL(
                "INSERT INTO imu_gait_assessments (assessmentId, timestampEpochMs, startEpochMs, endEpochMs, sessionId, " +
                    "protocolId, placement, plannedWalkingMs, validWalkingMs, steps, bouts, cadenceStepsPerMinute, " +
                    "meanStepIntervalMs, stepIntervalCvPercent, turningMs, turnRejectedSteps, implausibleRejectedPeaks, " +
                    "coveragePercent, invalidIntervals, accelerometerRateHz, gyroscopeRateHz, dropoutCount, timestampIssues, " +
                    "receivedEvents, unreliablePercent, qualityStatus, algorithmVersion, configSummary, scoringVersion) " +
                    "VALUES ('keep-gait', 1700000000000, 1699999967000, 1700000000000, 's1', 'timed_walk_imu', " +
                    "'FRONT_TROUSER_POCKET', 30000, 10600, 17, 2, 85.2, 704.0, 17.8, 3900, 3, 1, 100.0, '', 100.0, 100.0, " +
                    "0, 0, 6000, 0.0, 'VALID', 'imu-algo-1.0.0', 'fixture', 'not-scored')"
            )
            close()
        }

        val v14 = migrationHelper.runMigrationsAndValidate(14)
        v14.prepare(
            "SELECT steps, cadenceStepsPerMinute, scoringVersion, strides, strideCvPercent, meanTurnVelocityDegPerSec, " +
                "walkingTremorIndex FROM imu_gait_assessments WHERE assessmentId = 'keep-gait'"
        ).use {
            assertTrue("walking row lost", it.step())
            assertEquals(17L, it.getLong(0))
            assertEquals(85.2, it.getDouble(1), 1e-9)
            assertEquals("not-scored", it.getText(2))
            for (column in 3..6) assertTrue("column $column should be NULL", it.isNull(column))
        }
        v14.close()
    }
}
