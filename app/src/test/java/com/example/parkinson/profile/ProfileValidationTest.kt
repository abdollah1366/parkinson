package com.example.parkinson.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Registration rules. National IDs here are made-up numbers with a computed check digit, not real people. */
class ProfileValidationTest {

    @Test
    fun nationalIdCheckDigit() {
        assertEquals("0013542419", ProfileValidation.normalizeNationalId("0013542419"))
        assertEquals("1234567891", ProfileValidation.normalizeNationalId("۱۲۳۴۵۶۷۸۹۱"))
        assertEquals("0087964570", ProfileValidation.normalizeNationalId("87964570"))
        assertEquals("4475896320", ProfileValidation.normalizeNationalId("447-589632-0"))
        assertNull(ProfileValidation.normalizeNationalId("1234567890"))
        assertNull(ProfileValidation.normalizeNationalId("1111111111"))
        assertNull(ProfileValidation.normalizeNationalId("12345"))
        assertNull(ProfileValidation.normalizeNationalId("12345678a1"))
    }

    @Test
    fun mobileForms() {
        assertEquals("09121234567", ProfileValidation.normalizeMobile("09121234567"))
        assertEquals("09121234567", ProfileValidation.normalizeMobile("+98 912 123 4567"))
        assertEquals("09121234567", ProfileValidation.normalizeMobile("00989121234567"))
        assertEquals("09121234567", ProfileValidation.normalizeMobile("9121234567"))
        assertEquals("09121234567", ProfileValidation.normalizeMobile("۰۹۱۲۱۲۳۴۵۶۷"))
        assertNull(ProfileValidation.normalizeMobile("02112345678"))
        assertNull(ProfileValidation.normalizeMobile("0912123456"))
    }

    @Test
    fun names() {
        assertTrue(ProfileValidation.isValidName("علیرضا"))
        assertTrue(ProfileValidation.isValidName("  محمد‌رضا  "))
        assertTrue(ProfileValidation.isValidName("Anne-Marie"))
        assertFalse(ProfileValidation.isValidName(""))
        assertFalse(ProfileValidation.isValidName("ع"))
        assertFalse(ProfileValidation.isValidName("Ali2"))
        assertEquals("محمد رضا", ProfileValidation.normalizeName(" محمد   رضا "))
    }

    @Test
    fun anEmptyFormListsEveryRequiredField() {
        assertEquals(
            setOf(
                ProfileField.FIRST_NAME, ProfileField.LAST_NAME, ProfileField.AGE, ProfileField.GENDER,
                ProfileField.MOBILE, ProfileField.NATIONAL_ID, ProfileField.DIAGNOSIS, ProfileField.DOMINANT_HAND,
                ProfileField.CONSENT
            ),
            ProfileValidation.errors(ProfileForm())
        )
    }

    @Test
    fun medicationIsAskedOnlyAfterADiagnosis() {
        assertTrue(ProfileField.MEDICATION in ProfileValidation.errors(valid.copy(parkinsonDiagnosed = true, takesParkinsonMedication = null)))
        assertTrue(ProfileValidation.errors(valid.copy(parkinsonDiagnosed = false, takesParkinsonMedication = null)).isEmpty())
        val saved = ProfileValidation.toProfile(valid.copy(parkinsonDiagnosed = false, takesParkinsonMedication = true), 5L)!!
        assertNull(saved.takesParkinsonMedication)
    }

    @Test
    fun agesOutsideTheRangeAreRejected() {
        assertTrue(ProfileField.AGE in ProfileValidation.errors(valid.copy(age = 9)))
        assertTrue(ProfileField.AGE in ProfileValidation.errors(valid.copy(age = 111)))
        assertTrue(ProfileValidation.errors(valid.copy(age = 10)).isEmpty())
        assertTrue(ProfileValidation.errors(valid.copy(age = 110)).isEmpty())
    }

    @Test
    fun aValidFormBecomesANormalisedProfileAndKeepsTheFirstConsent() {
        val first = ProfileValidation.toProfile(valid, nowEpochMs = 100L)!!
        assertEquals("علی", first.firstName)
        assertEquals("09121234567", first.mobile)
        assertEquals("0013542419", first.nationalId)
        assertEquals(100L, first.consentEpochMs)
        assertEquals("••••••2419", first.maskedNationalId)
        assertFalse(first.toString().contains("2419"))

        val edited = ProfileValidation.toProfile(ProfileForm.from(first).copy(age = 61), nowEpochMs = 200L, previous = first)!!
        assertEquals(61, edited.age)
        assertEquals(100L, edited.consentEpochMs)
        assertEquals(200L, edited.updatedEpochMs)
        assertNull(ProfileValidation.toProfile(valid.copy(consent = false), 1L))
    }

    companion object {
        val valid = ProfileForm(
            firstName = " علی ",
            lastName = "رضایی",
            age = 60,
            gender = Gender.MALE,
            mobile = "+989121234567",
            nationalId = "۰۰۱۳۵۴۲۴۱۹",
            parkinsonDiagnosed = true,
            takesParkinsonMedication = true,
            dominantHand = DominantHand.RIGHT,
            consent = true,
        )

        fun sample(): UserProfile = ProfileValidation.toProfile(valid, nowEpochMs = 1_700_000_000_000L)!!
    }
}
