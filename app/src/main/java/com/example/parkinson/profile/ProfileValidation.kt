package com.example.parkinson.profile

object ProfileRules {
    const val MIN_AGE = 10
    const val MAX_AGE = 110
    val AGES: IntRange = MIN_AGE..MAX_AGE
    const val NAME_MAX_LENGTH = 40
}

/** A field of the registration form that can be wrong or missing. */
enum class ProfileField {
    FIRST_NAME, LAST_NAME, AGE, GENDER, MOBILE, NATIONAL_ID, DIAGNOSIS, MEDICATION, DOMINANT_HAND, CONSENT
}

/** What the registration form holds while it is being filled in; any field may still be empty or wrong. */
data class ProfileForm(
    val firstName: String = "",
    val lastName: String = "",
    val age: Int? = null,
    val gender: Gender? = null,
    val mobile: String = "",
    val nationalId: String = "",
    val parkinsonDiagnosed: Boolean? = null,
    val takesParkinsonMedication: Boolean? = null,
    val dominantHand: DominantHand? = null,
    val consent: Boolean = false,
) {
    override fun toString(): String = "ProfileForm(age=$age, gender=$gender)"

    companion object {
        fun from(profile: UserProfile) = ProfileForm(
            firstName = profile.firstName,
            lastName = profile.lastName,
            age = profile.age,
            gender = profile.gender,
            mobile = profile.mobile,
            nationalId = profile.nationalId,
            parkinsonDiagnosed = profile.parkinsonDiagnosed,
            takesParkinsonMedication = profile.takesParkinsonMedication,
            dominantHand = profile.dominantHand,
            consent = true,
        )
    }
}

object ProfileValidation {

    /** Persian (۰-۹) and Arabic (٠-٩) digits to ASCII; other characters are kept. */
    fun normalizeDigits(text: String): String = buildString(text.length) {
        for (c in text) append(
            when (c) {
                in '۰'..'۹' -> '0' + (c - '۰')
                in '٠'..'٩' -> '0' + (c - '٠')
                else -> c
            }
        )
    }

    fun normalizeName(text: String): String = text.trim().replace(Regex("\\s+"), " ")

    /** Letters of any script, spaces, the zero-width non-joiner, hyphen and apostrophe; at least two letters. */
    fun isValidName(text: String): Boolean {
        val name = normalizeName(text)
        return name.length <= ProfileRules.NAME_MAX_LENGTH &&
            name.count { it.isLetter() } >= 2 &&
            name.all { it.isLetter() || it == ' ' || it == '‌' || it == '-' || it == '\'' }
    }

    /** An Iranian mobile number as 09xxxxxxxxx, from forms such as +98 912..., 0098912..., 912..., or null. */
    fun normalizeMobile(text: String): String? {
        val raw = normalizeDigits(text).filterNot { it == ' ' || it == '-' || it == '(' || it == ')' }
        val digits = when {
            raw.startsWith("+98") -> "0" + raw.drop(3)
            raw.startsWith("0098") -> "0" + raw.drop(4)
            raw.startsWith("98") && raw.length == 12 -> "0" + raw.drop(2)
            raw.startsWith("9") && raw.length == 10 -> "0$raw"
            else -> raw
        }
        return digits.takeIf { MOBILE.matches(it) }
    }

    /**
     * An Iranian national ID (کد ملی) as 10 digits, or null when the check digit is wrong. Shorter codes are padded
     * with leading zeros, as on the card. Check: s = Σ d[i]·(10 − i) for the first 9 digits, r = s mod 11; the last
     * digit is r when r < 2, otherwise 11 − r. Codes of one repeated digit are rejected.
     */
    fun normalizeNationalId(text: String): String? {
        val raw = normalizeDigits(text).filterNot { it == ' ' || it == '-' }
        if (raw.length !in 8..10 || !raw.all { it in '0'..'9' }) return null
        val code = raw.padStart(10, '0')
        if (code.all { it == code[0] }) return null
        val sum = (0 until 9).sumOf { (code[it] - '0') * (10 - it) }
        val r = sum % 11
        val check = code[9] - '0'
        val valid = if (r < 2) check == r else check == 11 - r
        return code.takeIf { valid }
    }

    /** The fields that are missing or wrong; empty when the form can be saved. */
    fun errors(form: ProfileForm): Set<ProfileField> = buildSet {
        if (!isValidName(form.firstName)) add(ProfileField.FIRST_NAME)
        if (!isValidName(form.lastName)) add(ProfileField.LAST_NAME)
        if (form.age == null || form.age !in ProfileRules.AGES) add(ProfileField.AGE)
        if (form.gender == null) add(ProfileField.GENDER)
        if (normalizeMobile(form.mobile) == null) add(ProfileField.MOBILE)
        if (normalizeNationalId(form.nationalId) == null) add(ProfileField.NATIONAL_ID)
        if (form.parkinsonDiagnosed == null) add(ProfileField.DIAGNOSIS)
        if (form.parkinsonDiagnosed == true && form.takesParkinsonMedication == null) add(ProfileField.MEDICATION)
        if (form.dominantHand == null) add(ProfileField.DOMINANT_HAND)
        if (!form.consent) add(ProfileField.CONSENT)
    }

    /**
     * The profile to save, or null while [errors] is not empty. [previous] keeps the id and the first consent time
     * when an existing profile is edited.
     */
    fun toProfile(form: ProfileForm, nowEpochMs: Long, previous: UserProfile? = null): UserProfile? {
        if (errors(form).isNotEmpty()) return null
        val diagnosed = form.parkinsonDiagnosed!!
        return UserProfile(
            firstName = normalizeName(form.firstName),
            lastName = normalizeName(form.lastName),
            age = form.age!!,
            gender = form.gender!!,
            mobile = normalizeMobile(form.mobile)!!,
            nationalId = normalizeNationalId(form.nationalId)!!,
            parkinsonDiagnosed = diagnosed,
            takesParkinsonMedication = if (diagnosed) form.takesParkinsonMedication else null,
            dominantHand = form.dominantHand!!,
            consentEpochMs = previous?.consentEpochMs ?: nowEpochMs,
            updatedEpochMs = nowEpochMs,
            id = previous?.id ?: 0,
        )
    }

    private val MOBILE = Regex("^09\\d{9}$")
}
