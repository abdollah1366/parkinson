package com.example.parkinson.data

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface AssessmentDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: AssessmentEntity)

    @Query("SELECT * FROM finger_tapping_assessments ORDER BY timestampEpochMs DESC LIMIT 1")
    fun observeLatest(): Flow<AssessmentEntity?>

    @Query("SELECT * FROM finger_tapping_assessments ORDER BY timestampEpochMs DESC")
    fun observeAll(): Flow<List<AssessmentEntity>>

    @Query("SELECT * FROM finger_tapping_assessments WHERE assessmentId = :id")
    fun observeById(id: String): Flow<AssessmentEntity?>
}

/**
 * v2 adds hand_stability_assessments, v3 adds pronation_supination_assessments, v4 adds columns
 * to pronation_supination_assessments, v5 adds columns to finger_tapping_assessments (layered
 * quality, interpretation, per-tap payload), v6 adds hand_open_close_assessments, v7 adds
 * resting_tremor_assessments, v8 adds gait_assessments, v9 adds sit_to_stand_assessments. All are automatic migrations; existing rows are kept. The Rapid
 * Alternating Movements test never stored anything, so it has no table and no legacy rows.
 */
@Database(
    entities = [
        AssessmentEntity::class,
        HandStabilityEntity::class,
        PronationSupinationEntity::class,
        HandOpenCloseEntity::class,
        RestingTremorEntity::class,
        GaitEntity::class,
        SitToStandEntity::class
    ],
    version = 9,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 1, to = 2),
        AutoMigration(from = 2, to = 3),
        AutoMigration(from = 3, to = 4),
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6),
        AutoMigration(from = 6, to = 7),
        AutoMigration(from = 7, to = 8),
        AutoMigration(from = 8, to = 9)
    ]
)
abstract class AssessmentDatabase : RoomDatabase() {

    abstract fun assessmentDao(): AssessmentDao

    abstract fun handStabilityDao(): HandStabilityDao

    abstract fun pronationSupinationDao(): PronationSupinationDao

    abstract fun handOpenCloseDao(): HandOpenCloseDao

    abstract fun restingTremorDao(): RestingTremorDao

    abstract fun gaitDao(): GaitDao

    abstract fun sitToStandDao(): SitToStandDao

    companion object {
        private const val NAME = "assessments.db"

        fun build(context: Context): AssessmentDatabase =
            Room.databaseBuilder(context.applicationContext, AssessmentDatabase::class.java, NAME).build()
    }
}
