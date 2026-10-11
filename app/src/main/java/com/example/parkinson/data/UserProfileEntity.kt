package com.example.parkinson.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.example.parkinson.profile.DominantHand
import com.example.parkinson.profile.Gender
import com.example.parkinson.profile.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** The single registered person (row [UserProfileEntity.SINGLE_ID]). */
@Entity(tableName = "user_profile")
data class UserProfileEntity(
    @PrimaryKey val id: Int = SINGLE_ID,
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
) {
    companion object {
        const val SINGLE_ID = 1
    }
}

@Dao
interface UserProfileDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: UserProfileEntity)

    @Query("SELECT * FROM user_profile WHERE id = ${UserProfileEntity.SINGLE_ID}")
    fun observe(): Flow<UserProfileEntity?>

    @Query("DELETE FROM user_profile")
    suspend fun deleteAll()
}

/** Registration data, kept only in this app's private database. */
interface ProfileRepository {
    fun observe(): Flow<UserProfile?>

    suspend fun save(profile: UserProfile)

    suspend fun delete()
}

class RoomProfileRepository(private val dao: UserProfileDao) : ProfileRepository {

    override fun observe(): Flow<UserProfile?> = dao.observe().map { it?.toDomain() }

    override suspend fun save(profile: UserProfile) = dao.upsert(profile.toEntity())

    override suspend fun delete() = dao.deleteAll()

    companion object {
        fun from(db: AssessmentDatabase) = RoomProfileRepository(db.userProfileDao())
    }
}

internal fun UserProfile.toEntity() = UserProfileEntity(
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
