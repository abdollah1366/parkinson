package com.example.parkinson.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.withTransaction
import com.example.parkinson.profile.DominantHand
import com.example.parkinson.profile.Gender
import com.example.parkinson.profile.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** One registered person; several people can share the phone. [id] links the person's results to them. */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int,
    val firstName: String,
    val lastName: String,
    val age: Int,
    val gender: String,
    val mobile: String,
    val nationalId: String,
    val parkinsonDiagnosed: Boolean,
    val takesParkinsonMedication: Boolean?,
    val dominantHand: String,
    val consentEpochMs: Long,
    val updatedEpochMs: Long,
)

@Dao
interface UserProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserProfileEntity)

    @Query("SELECT * FROM user_profile ORDER BY firstName, lastName, id")
    fun observeAll(): Flow<List<UserProfileEntity>>

    @Query("SELECT MAX(id) FROM user_profile")
    suspend fun maxId(): Int?

    @Query("SELECT COUNT(*) FROM user_profile")
    suspend fun count(): Int

    @Query("DELETE FROM user_profile WHERE id = :id")
    suspend fun deleteById(id: Int)
}

/** The registered people, kept only in this app's private database. */
interface ProfileRepository {
    /** Everyone registered on this phone, by name. */
    fun observeAll(): Flow<List<UserProfile>>

    /** Saves a new person (id 0) or replaces an existing one; returns the person's id. */
    suspend fun save(profile: UserProfile): Int

    /** Removes the person and every result stored for them. */
    suspend fun delete(id: Int)
}

class RoomProfileRepository(private val db: AssessmentDatabase) : ProfileRepository {

    private val dao = db.userProfileDao()

    override fun observeAll(): Flow<List<UserProfile>> = dao.observeAll().map { rows -> rows.mapNotNull { it.toDomain() } }

    override suspend fun save(profile: UserProfile): Int = db.withTransaction {
        if (profile.id != 0) {
            dao.upsert(profile.toEntity())
            return@withTransaction profile.id
        }
        val first = dao.count() == 0
        val id = (dao.maxId() ?: 0) + 1
        dao.upsert(profile.copy(id = id).toEntity())
        // Results saved before anyone registered belong to the first person who does.
        if (first) ResultTables.assignUnowned(db, id)
        id
    }

    override suspend fun delete(id: Int) = db.withTransaction {
        ResultTables.deleteOwnedBy(db, id)
        dao.deleteById(id)
    }

    companion object {
        fun from(db: AssessmentDatabase) = RoomProfileRepository(db)
    }
}

internal fun UserProfile.toEntity() = UserProfileEntity(
    id = id,
    firstName = firstName,
    lastName = lastName,
    age = age,
    gender = gender.id,
    mobile = mobile,
    nationalId = nationalId,
    parkinsonDiagnosed = parkinsonDiagnosed,
    takesParkinsonMedication = takesParkinsonMedication,
    dominantHand = dominantHand.id,
    consentEpochMs = consentEpochMs,
    updatedEpochMs = updatedEpochMs,
)

/** Null for a row with an unknown gender or hand id (a newer app version's data). */
internal fun UserProfileEntity.toDomain(): UserProfile? {
    val gender = Gender.fromId(gender) ?: return null
    val hand = DominantHand.fromId(dominantHand) ?: return null
    return UserProfile(
        id = id,
        firstName = firstName,
        lastName = lastName,
        age = age,
        gender = gender,
        mobile = mobile,
        nationalId = nationalId,
        parkinsonDiagnosed = parkinsonDiagnosed,
        takesParkinsonMedication = takesParkinsonMedication,
        dominantHand = hand,
        consentEpochMs = consentEpochMs,
        updatedEpochMs = updatedEpochMs,
    )
}
