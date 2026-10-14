package com.example.parkinson.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class PlanScheduleTest {
    private val tehran = TimeZone.getTimeZone("Asia/Tehran")

    private fun at(y: Int, m: Int, d: Int, h: Int, min: Int = 0): Long = Calendar.getInstance(tehran).apply {
        clear()
        set(y, m - 1, d, h, min)
    }.timeInMillis

    private fun medication(times: List<String>, start: String = "2026-10-10", end: String? = null, created: Long = 0) =
        Prescription(1, "دکتر", PlanKind.MEDICATION, "لوودوپا", "۱ قرص", times, null, null, start, end, created)

    private fun exercise(start: String = "2026-10-10", created: Long = 0) =
        Prescription(2, "دکتر", PlanKind.EXERCISE, "پیاده‌روی", null, emptyList(), 3, null, start, null, created)

    @Test
    fun dosesOfTheDayFollowTheTimesAndDates() {
        val p = medication(listOf("08:00", "14:00", "20:30"))
        val doses = PlanSchedule.dosesOfDay(listOf(p), at(2026, 10, 13, 11), tehran)
        assertEquals(listOf(at(2026, 10, 13, 8), at(2026, 10, 13, 14), at(2026, 10, 13, 20, 30)), doses.map { it.atEpochMs })
        // Not before its start date, and not after its end date.
        assertTrue(PlanSchedule.dosesOfDay(listOf(p), at(2026, 10, 9, 11), tehran).isEmpty())
        val ended = medication(listOf("08:00"), end = "2026-10-12")
        assertTrue(PlanSchedule.dosesOfDay(listOf(ended), at(2026, 10, 13, 11), tehran).isEmpty())
        assertEquals(1, PlanSchedule.dosesOfDay(listOf(ended), at(2026, 10, 12, 11), tehran).size)
    }

    @Test
    fun noDoseIsDueBeforeItWasPrescribed() {
        val p = medication(listOf("08:00", "14:00"), created = at(2026, 10, 13, 9))
        assertEquals(listOf(at(2026, 10, 13, 14)), PlanSchedule.dosesOfDay(listOf(p), at(2026, 10, 13, 10), tehran).map { it.atEpochMs })
    }

    @Test
    fun theNextReminderIsTheNextDoseOrTheAfternoonExerciseReminder() {
        val p = medication(listOf("08:00", "14:00"))
        assertEquals(at(2026, 10, 13, 14), PlanSchedule.nextReminder(listOf(p), at(2026, 10, 13, 8), tehran))
        assertEquals(at(2026, 10, 14, 8), PlanSchedule.nextReminder(listOf(p), at(2026, 10, 13, 14), tehran))
        val e = exercise()
        assertEquals(at(2026, 10, 13, 17), PlanSchedule.nextReminder(listOf(p, e), at(2026, 10, 13, 15), tehran))
        assertTrue(PlanSchedule.isExerciseReminder(at(2026, 10, 13, 17), tehran))
        assertEquals(at(2026, 10, 14, 8), PlanSchedule.nextReminder(listOf(p, e), at(2026, 10, 13, 17), tehran))
        // Nothing in the next two days.
        assertNull(PlanSchedule.nextReminder(listOf(medication(listOf("08:00"), start = "2026-11-01")), at(2026, 10, 13, 9), tehran))
    }
}
