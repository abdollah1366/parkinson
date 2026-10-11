package com.example.parkinson.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/**
 * Version 11 -> 12: [SpeechEntity.qualityScore] becomes nullable. SQLite cannot change a column's NOT NULL
 * constraint in place, so the table is rebuilt with the same columns, types and index as version 11.
 *
 * Earlier versions wrote 0 when the voiced share was unavailable. The metrics text records that case as
 * "VOICED_PERCENT=U:<reason>", so those scores are set to NULL. Every other row keeps its stored score.
 */
val MIGRATION_11_12 = object : Migration(11, 12) {
    override fun migrate(connection: SQLiteConnection) {
        connection.execSQL(
            """
            CREATE TABLE IF NOT EXISTS `speech_assessments_new` (
                `assessmentId` TEXT NOT NULL,
                `timestampEpochMs` INTEGER NOT NULL,
                `startEpochMs` INTEGER NOT NULL,
                `endEpochMs` INTEGER NOT NULL,
                `sessionId` TEXT NOT NULL,
                `task` TEXT NOT NULL,
                `plannedDurationMs` INTEGER NOT NULL,
                `consentAccepted` INTEGER NOT NULL,
                `qualityStatus` TEXT NOT NULL,
                `qualityIssues` TEXT NOT NULL,
                `qualityScore` INTEGER,
                `durationMs` REAL NOT NULL,
                `sampleRateHz` INTEGER NOT NULL,
                `requestedSampleRateHz` INTEGER NOT NULL,
                `rmsDbfs` REAL,
                `clippedPercent` REAL,
                `noiseFloorDb` REAL,
                `snrDb` REAL,
                `metrics` TEXT NOT NULL,
                `algorithmVersion` TEXT NOT NULL,
                `configSummary` TEXT NOT NULL,
                `scoringVersion` TEXT NOT NULL,
                PRIMARY KEY(`assessmentId`)
            )
            """.trimIndent()
        )
        connection.execSQL(
            """
            INSERT INTO `speech_assessments_new` (
                assessmentId, timestampEpochMs, startEpochMs, endEpochMs, sessionId, task, plannedDurationMs,
                consentAccepted, qualityStatus, qualityIssues, qualityScore, durationMs, sampleRateHz,
                requestedSampleRateHz, rmsDbfs, clippedPercent, noiseFloorDb, snrDb, metrics, algorithmVersion,
                configSummary, scoringVersion
            )
            SELECT
                assessmentId, timestampEpochMs, startEpochMs, endEpochMs, sessionId, task, plannedDurationMs,
                consentAccepted, qualityStatus, qualityIssues, qualityScore, durationMs, sampleRateHz,
                requestedSampleRateHz, rmsDbfs, clippedPercent, noiseFloorDb, snrDb, metrics, algorithmVersion,
                configSummary, scoringVersion
            FROM `speech_assessments`
            """.trimIndent()
        )
        connection.execSQL(
            "UPDATE `speech_assessments_new` SET qualityScore = NULL WHERE metrics LIKE '%VOICED_PERCENT=U:%'"
        )
        connection.execSQL("DROP TABLE `speech_assessments`")
        connection.execSQL("ALTER TABLE `speech_assessments_new` RENAME TO `speech_assessments`")
        connection.execSQL(
            "CREATE INDEX IF NOT EXISTS `index_speech_assessments_timestampEpochMs` " +
                "ON `speech_assessments` (`timestampEpochMs`)"
        )
    }
}
