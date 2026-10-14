package com.example.parkinson.profile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ActivePatientTest {

    private val ali = ProfileValidationTest.sample().copy(id = 1)
    private val maryam = ProfileValidationTest.sample().copy(id = 2, firstName = "مریم", nationalId = "0013542427")

    @Test
    fun theChosenPersonIsActive() {
        assertEquals(maryam, ActivePatient.resolve(listOf(ali, maryam), selected = 2))
    }

    @Test
    fun theOnlyPersonIsActiveWithoutAChoice() {
        // For example right after the update from the one-person version.
        assertEquals(ali, ActivePatient.resolve(listOf(ali), selected = null))
        assertEquals(ali, ActivePatient.resolve(listOf(ali), selected = 7))
    }

    @Test
    fun severalPeopleAndNoValidChoiceMeansNobody() {
        assertNull(ActivePatient.resolve(listOf(ali, maryam), selected = null))
        assertNull(ActivePatient.resolve(listOf(ali, maryam), selected = 7))
        assertNull(ActivePatient.resolve(emptyList(), selected = 1))
    }
}
