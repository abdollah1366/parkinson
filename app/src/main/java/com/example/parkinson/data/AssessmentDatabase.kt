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

/** v2 adds the hand_stability_assessments table; v1 Finger Tapping rows are kept unchanged. */
@Database(
    entities = [AssessmentEntity::class, HandStabilityEntity::class],
    version = 2,
    exportSchema = true,
    autoMigrations = [AutoMigration(from = 1, to = 2)]
)
abstract class AssessmentDatabase : RoomDatabase() {

    abstract fun assessmentDao(): AssessmentDao

    abstract fun handStabilityDao(): HandStabilityDao

    companion object {
        private const val NAME = "assessments.db"

        fun build(context: Context): AssessmentDatabase =
            Room.databaseBuilder(context.applicationContext, AssessmentDatabase::class.java, NAME).build()
    }
}
