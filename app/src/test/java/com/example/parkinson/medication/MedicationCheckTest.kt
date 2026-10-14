package com.example.parkinson.medication

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MedicationCheckTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun aReportAppliesForTwoHours() {
        val check = MedicationCheck(1, MedicationState.ON, t0)
        assertTrue(check.isCurrent(t0))
        assertTrue(check.isCurrent(t0 + MedicationCheck.VALID_MS))
        assertFalse(check.isCurrent(t0 + MedicationCheck.VALID_MS + 1))
        // A clock set back before the report does not reuse it.
        assertFalse(check.isCurrent(t0 - 1))
    }

    @Test
    fun eachPersonHasTheirOwnReport() {
        val store = InMemoryMedicationCheckStore()
        store.record(MedicationCheck(1, MedicationState.ON, t0))
        store.record(MedicationCheck(2, MedicationState.OFF, t0))
        store.record(MedicationCheck(1, MedicationState.UNSURE, t0 + 1_000))
        assertEquals(MedicationState.UNSURE, store.current(1, t0 + 2_000)?.state)
        assertEquals(MedicationState.OFF, store.current(2, t0 + 2_000)?.state)
        assertNull(store.current(3, t0))
        assertNull(store.current(2, t0 + MedicationCheck.VALID_MS + 1))
    }

    @Test
    fun idsAreStable() {
        assertEquals(MedicationState.OFF, MedicationState.fromId("off"))
        assertNull(MedicationState.fromId("later"))
    }
}
