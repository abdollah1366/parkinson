package com.example.parkinson.plan

import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** A medication dose due at [atEpochMs]. */
data class Dose(val prescription: Prescription, val atEpochMs: Long)

/**
 * When doses are due, in the phone's time zone (the clinic's, for a phone in the same country as its doctor). Pure
 * functions: java.util.Calendar, since java.time needs Android 8.
 */
object PlanSchedule {
    /** The hour of the daily exercise reminder, on days an exercise is not done yet. */
    const val EXERCISE_REMINDER_HOUR = 17

    /** yyyy-MM-dd of the day [epochMs] falls on. */
    fun dateOf(epochMs: Long, zone: TimeZone): String {
        val c = Calendar.getInstance(zone).apply { timeInMillis = epochMs }
        return String.format(Locale.US, "%04d-%02d-%02d", c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
    }

    /** Midnight at the start of the day [epochMs] falls on. */
    fun startOfDay(epochMs: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = epochMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun at(dayStart: Long, hour: Int, minute: Int, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = dayStart
        set(Calendar.HOUR_OF_DAY, hour)
        set(Calendar.MINUTE, minute)
    }.timeInMillis

    private fun nextDay(dayStart: Long, zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        timeInMillis = dayStart
        add(Calendar.DAY_OF_MONTH, 1)
    }.timeInMillis.let { startOfDay(it, zone) }

    /** Whether the plan runs on the day that starts at [dayStart]. */
    fun runsOn(p: Prescription, dayStart: Long, zone: TimeZone): Boolean {
        val date = dateOf(dayStart, zone)
        return date >= p.startDate && (p.endDate == null || date <= p.endDate)
    }

    /** The doses of medication [p] in [fromMs, toMs), within its dates and not before it was prescribed. */
    fun doses(p: Prescription, fromMs: Long, toMs: Long, zone: TimeZone): List<Dose> {
        if (p.kind != PlanKind.MEDICATION) return emptyList()
        val lower = maxOf(fromMs, p.createdAtEpochMs)
        if (toMs <= lower) return emptyList()
        val result = mutableListOf<Dose>()
        var day = startOfDay(lower, zone)
        while (day < toMs) {
            if (runsOn(p, day, zone)) {
                for (time in p.times) {
                    val (h, m) = time.split(':').map { it.toInt() }
                    val t = at(day, h, m, zone)
                    if (t in lower until toMs) result += Dose(p, t)
                }
            }
            day = nextDay(day, zone)
        }
        return result
    }

    /** All doses of [plan] on the day [nowMs] falls on, by time. */
    fun dosesOfDay(plan: List<Prescription>, nowMs: Long, zone: TimeZone): List<Dose> {
        val day = startOfDay(nowMs, zone)
        return plan.flatMap { doses(it, day, nextDay(day, zone), zone) }.sortedWith(compareBy({ it.atEpochMs }, { it.prescription.name }))
    }

    /**
     * The next reminder after [afterMs]: the earliest dose, or the exercise reminder of a day an exercise runs. Null
     * when nothing is due in the next two days (the plan is read again before then).
     */
    fun nextReminder(plan: List<Prescription>, afterMs: Long, zone: TimeZone): Long? {
        val until = afterMs + 2 * DAY_MS + 1
        val dose = plan.flatMap { doses(it, afterMs + 1, until, zone) }.minOfOrNull { it.atEpochMs }
        val exercise = plan.filter { it.kind == PlanKind.EXERCISE }.flatMap { p ->
            var day = startOfDay(afterMs, zone)
            (0..2).mapNotNull {
                val t = at(day, EXERCISE_REMINDER_HOUR, 0, zone).takeIf { t -> t > afterMs && t >= p.createdAtEpochMs && runsOn(p, day, zone) }
                day = nextDay(day, zone)
                t
            }
        }.minOrNull()
        return listOfNotNull(dose, exercise).minOrNull()
    }

    /** Whether [at] is the time of an exercise reminder (not a dose). */
    fun isExerciseReminder(at: Long, zone: TimeZone): Boolean {
        val c = Calendar.getInstance(zone).apply { timeInMillis = at }
        return c.get(Calendar.HOUR_OF_DAY) == EXERCISE_REMINDER_HOUR && c.get(Calendar.MINUTE) == 0
    }

    const val DAY_MS = 24 * 60 * 60 * 1000L
}
