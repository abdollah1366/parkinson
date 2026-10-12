package com.example.parkinson.data

import androidx.room.migration.Migration
import androidx.sqlite.SQLiteConnection
import androidx.sqlite.execSQL

/** Every table that stores test results; each has a nullable `profileId` column since version 17. */
internal object ResultTables {

    val NAMES = listOf(
        "finger_tapping_assessments",
        "hand_stability_assessments",
        "pronation_supination_assessments",
        "hand_open_close_assessments",
        "resting_tremor_assessments",
        "gait_assessments",
        "sit_to_stand_assessments",
        "speech_assessments",
        "imu_sit_to_stand_assessments",
        "imu_gait_assessments",
        "resting_tremor_imu_assessments",
        "dual_task_assessments",
    )

    /** Gives [profileId] every result that belongs to nobody. Call inside a transaction. */
    fun assignUnowned(db: AssessmentDatabase, profileId: Int) {
        val sql = db.openHelper.writableDatabase
        for (table in NAMES) sql.execSQL("UPDATE `$table` SET profileId = ? WHERE profileId IS NULL", arrayOf<Any>(profileId))
    }

    /** Deletes every result of [profileId]. Call inside a transaction. */
    fun deleteOwnedBy(db: AssessmentDatabase, profileId: Int) {
        val sql = db.openHelper.writableDatabase
        for (table in NAMES) sql.execSQL("DELETE FROM `$table` WHERE profileId = ?", arrayOf<Any>(profileId))
    }
}

/**
 * Version 16 -> 17: several people can be registered. Each result table gets a nullable `profileId`, and the
 * results stored while only one person could register are given to that person. Without a registration they
 * stay unowned until the first person registers ([ResultTables.assignUnowned]).
 */
val MIGRATION_16_17 = object : Migration(16, 17) {
    override fun migrate(connection: SQLiteConnection) {
        for (table in ResultTables.NAMES) {
            connection.execSQL("ALTER TABLE `$table` ADD COLUMN `profileId` INTEGER")
            connection.execSQL("UPDATE `$table` SET profileId = (SELECT MIN(id) FROM user_profile)")
        }
    }
}
