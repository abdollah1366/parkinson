package com.example.parkinson.profile

enum class Gender(val id: String) {
    MALE("male"),
    FEMALE("female");

    companion object {
        fun fromId(id: String?): Gender? = entries.firstOrNull { it.id == id }
    }
}

enum class DominantHand(val id: String) {
    RIGHT("right"),
    LEFT("left");

    companion object {
        fun fromId(id: String?): DominantHand? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A person using the app, entered on the registration screen and editable later; several people can share one
 * phone. Kept only in the app's private database on this phone (no server, no cloud backup). The national ID is sensitive: screens show it masked
 * and it is never logged.
 */
data class UserProfile(
    val firstName: String,
    val lastName: String,
    /** Years, [ProfileRules.MIN_AGE]..[ProfileRules.MAX_AGE]. */
    val age: Int,
    val gender: Gender,
    /** Iranian mobile number, normalised to 09xxxxxxxxx. */
    val mobile: String,
    /** 10 digits with a valid check digit. */
    val nationalId: String,
    /** Whether a doctor has already diagnosed Parkinson's disease (as reported by the person). */
    val parkinsonDiagnosed: Boolean,
    /** Whether Parkinson's medication is taken; null when [parkinsonDiagnosed] is false. */
    val takesParkinsonMedication: Boolean?,
    val dominantHand: DominantHand,
    /** When the person agreed that the data is kept on this phone. */
    val consentEpochMs: Long,
    val updatedEpochMs: Long,
    /** 0 until the person is saved; then the id their results are stored under. */
    val id: Int = 0,
) {
    val fullName: String get() = "$firstName $lastName"

    /** The national ID with all but the last 4 digits hidden. */
    val maskedNationalId: String get() = "••••••" + nationalId.takeLast(4)

    /** Never print the national ID or the phone number (logs, crash reports). */
    override fun toString(): String = "UserProfile(id=$id, age=$age, gender=$gender, diagnosed=$parkinsonDiagnosed)"
}
