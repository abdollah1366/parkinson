package com.example.parkinson.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeParseException

// Stage 5: prescriptions. A doctor prescribes a medication (a dose at set times of day) or an exercise (so many times a
// week) for a linked patient. The app reminds the patient and sends back each dose taken or missed and each exercise
// done; the doctor sees how closely the plan was followed.

enum class PrescriptionKind { MEDICATION, EXERCISE }

@Serializable
data class PrescriptionIn(
    val kind: PrescriptionKind,
    val name: String,
    val dose: String? = null,
    /** Times of day (HH:mm) of each dose; medications only. */
    val times: List<String> = emptyList(),
    /** Exercises only. */
    val perWeek: Int? = null,
    val instructions: String? = null,
    /** yyyy-MM-dd; today when left out. */
    val startDate: String? = null,
    val endDate: String? = null,
)

/** How closely a prescription was followed: doses (or exercises) due and done in the last 7 and 30 days. */
@Serializable
data class AdherenceDto(
    val due7: Int,
    val done7: Int,
    val due30: Int,
    val done30: Int,
    /** Doses recorded as missed in the last 7 days (medications). */
    val missed7: Int = 0,
    val lastDoneEpochMs: Long? = null,
)

@Serializable
data class PrescriptionDto(
    val id: Long,
    val doctorId: Long,
    val doctorName: String,
    val kind: PrescriptionKind,
    val name: String,
    val dose: String? = null,
    val times: List<String> = emptyList(),
    val perWeek: Int? = null,
    val instructions: String? = null,
    val startDate: String,
    val endDate: String? = null,
    val createdAtEpochMs: Long,
    val stoppedAtEpochMs: Long? = null,
    val active: Boolean,
    val adherence: AdherenceDto? = null,
)

@Serializable
data class IntakeIn(val prescriptionId: Long, val scheduledAtEpochMs: Long, val status: String)

@Serializable
data class IntakesUpload(val intakes: List<IntakeIn>)

@Serializable
data class IntakeDto(val prescriptionId: Long, val scheduledAtEpochMs: Long, val status: String, val recordedAtEpochMs: Long)

object PrescriptionRules {
    const val MAX_TIMES = 8

    /** Checks and tidies what a doctor wrote; [today] is the clinic's date. */
    fun validate(p: PrescriptionIn, today: LocalDate): PrescriptionIn {
        val name = p.name.trim().takeIf { it.length in 1..200 } ?: badRequest("invalid_name")
        val dose = p.dose?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 200) badRequest("invalid_dose") }
        val instructions = p.instructions?.trim()?.takeIf { it.isNotEmpty() }?.also { if (it.length > 1000) badRequest("invalid_instructions") }
        val start = p.startDate?.let(::date) ?: today
        val end = p.endDate?.takeIf { it.isNotBlank() }?.let(::date)
        if (end != null && end < start) badRequest("invalid_dates")
        return when (p.kind) {
            PrescriptionKind.MEDICATION -> {
                val times = p.times.map { time(Text.asciiDigits(it.trim())) }.distinct().sorted()
                if (times.isEmpty() || times.size > MAX_TIMES) badRequest("invalid_times")
                p.copy(name = name, dose = dose, times = times.map { it.toString() }, perWeek = null, instructions = instructions,
                    startDate = start.toString(), endDate = end?.toString())
            }
            PrescriptionKind.EXERCISE -> {
                val perWeek = p.perWeek?.takeIf { it in 1..21 } ?: badRequest("invalid_per_week")
                p.copy(name = name, dose = dose, times = emptyList(), perWeek = perWeek, instructions = instructions,
                    startDate = start.toString(), endDate = end?.toString())
            }
        }
    }

    private fun date(text: String): LocalDate = try {
        LocalDate.parse(Text.asciiDigits(text.trim()))
    } catch (e: DateTimeParseException) {
        badRequest("invalid_dates")
    }

    private fun time(text: String): LocalTime = try {
        LocalTime.parse(if (text.length == 4) "0$text" else text)
    } catch (e: DateTimeParseException) {
        badRequest("invalid_times")
    }

    /**
     * The dose times of a medication between [from] and [to] (both instants), within its dates and before it was
     * stopped. A dose counts as due one hour after its time, so a dose being taken right now is not yet "missed".
     */
    fun dueDoses(
        times: List<LocalTime>, start: LocalDate, end: LocalDate?, created: Instant, stopped: Instant?,
        from: Instant, to: Instant, zone: ZoneId,
    ): List<Instant> {
        val lower = maxOf(from, created)
        val upper = minOf(to.minus(Duration.ofHours(1)), stopped ?: Instant.MAX)
        if (upper <= lower) return emptyList()
        val firstDay = maxOf(start, lower.atZone(zone).toLocalDate())
        val lastDay = minOf(end ?: LocalDate.MAX, upper.atZone(zone).toLocalDate())
        return generateSequence(firstDay) { it.plusDays(1) }.takeWhile { it <= lastDay }
            .flatMap { day -> times.asSequence().map { day.atTime(it).atZone(zone).toInstant() } }
            .filter { it >= lower && it <= upper }
            .toList()
    }
}

class PrescriptionStore(private val db: Db, private val zone: ZoneId) {

    fun today(now: Instant = Instant.now()): LocalDate = now.atZone(zone).toLocalDate()

    fun add(doctorId: Long, patientId: Long, p: PrescriptionIn, now: Instant = Instant.now()): PrescriptionDto {
        val id = db.tx {
            single(
                """
                INSERT INTO prescriptions (doctor_id, patient_id, kind, name, dose, times, per_week, instructions, start_date, end_date, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) RETURNING id
                """.trimIndent(),
                doctorId, patientId, p.kind.name, p.name, p.dose, p.times.takeIf { it.isNotEmpty() }?.joinToString(","),
                p.perWeek, p.instructions, LocalDate.parse(p.startDate), p.endDate?.let(LocalDate::parse), now,
            ) { it.getLong("id") }!!
        }
        return ofPatient(patientId, now = now).first { it.id == id }
    }

    /** Stops one of the doctor's prescriptions; false when there is no such active one. */
    fun stop(doctorId: Long, prescriptionId: Long, now: Instant = Instant.now()): Boolean = db.tx {
        update("UPDATE prescriptions SET stopped_at = ? WHERE id = ? AND doctor_id = ? AND stopped_at IS NULL", now, prescriptionId, doctorId) == 1
    }

    /**
     * The patient's prescriptions with how they were followed, active ones first and newest first; only active ones
     * when [activeOnly], only the [doctorId]'s when given.
     */
    fun ofPatient(patientId: Long, doctorId: Long? = null, activeOnly: Boolean = false, now: Instant = Instant.now()): List<PrescriptionDto> {
        val rows = db.tx {
            query(
                """
                SELECT p.*, u.full_name FROM prescriptions p JOIN users u ON u.id = p.doctor_id
                WHERE p.patient_id = ? AND (?::bigint IS NULL OR p.doctor_id = ?)
                ORDER BY p.created_at DESC, p.id DESC
                """.trimIndent(),
                patientId, doctorId, doctorId,
            ) { it.toRow() }
        }
        val since = now.minus(Duration.ofDays(30))
        val intakes = db.tx {
            query(
                """
                SELECT i.prescription_id, i.scheduled_at, i.status, i.recorded_at FROM intakes i
                JOIN prescriptions p ON p.id = i.prescription_id
                WHERE p.patient_id = ? AND i.scheduled_at >= ?
                """.trimIndent(),
                patientId, since.minus(Duration.ofDays(1)),
            ) { it.toIntake() }
        }.groupBy { it.prescriptionId }
        val today = today(now)
        return rows.map { r ->
            val active = r.stopped == null && (r.end == null || r.end >= today)
            r.toDto(active, adherence(r, intakes[r.id].orEmpty(), now))
        }.filter { !activeOnly || it.active }.sortedByDescending { it.active }
    }

    /** Records doses and exercises from the patient's phone; the latest word on one dose wins. Returns how many. */
    fun record(patientId: Long, intakes: List<IntakeIn>, now: Instant = Instant.now()): Int = db.tx {
        val kinds = query("SELECT id, kind FROM prescriptions WHERE patient_id = ?", patientId) {
            it.getLong("id") to PrescriptionKind.valueOf(it.getString("kind"))
        }.toMap()
        var stored = 0
        for (i in intakes) {
            val kind = kinds[i.prescriptionId] ?: badRequest("unknown_prescription")
            val allowed = if (kind == PrescriptionKind.MEDICATION) setOf("TAKEN", "MISSED") else setOf("DONE")
            if (i.status !in allowed) badRequest("invalid_status")
            val at = Instant.ofEpochMilli(i.scheduledAtEpochMs)
            // Nothing from the far future or past: a phone with a wrong clock should not fill the record.
            if (at > now.plus(Duration.ofDays(1)) || at < now.minus(Duration.ofDays(400))) badRequest("invalid_time")
            stored += update(
                """
                INSERT INTO intakes (prescription_id, scheduled_at, status, recorded_at) VALUES (?, ?, ?, ?)
                ON CONFLICT (prescription_id, scheduled_at) DO UPDATE SET status = EXCLUDED.status, recorded_at = EXCLUDED.recorded_at
                """.trimIndent(),
                i.prescriptionId, at, i.status, now,
            )
        }
        stored
    }

    /** What the patient recorded for the doctor's prescriptions in the last [days] days, newest first. */
    fun intakes(patientId: Long, doctorId: Long, days: Int, now: Instant = Instant.now()): List<IntakeDto> = db.tx {
        query(
            """
            SELECT i.prescription_id, i.scheduled_at, i.status, i.recorded_at FROM intakes i
            JOIN prescriptions p ON p.id = i.prescription_id
            WHERE p.patient_id = ? AND p.doctor_id = ? AND i.scheduled_at >= ? ORDER BY i.scheduled_at DESC
            """.trimIndent(),
            patientId, doctorId, now.minus(Duration.ofDays(days.toLong())),
        ) { it.toIntake() }
    }

    private fun adherence(r: Row, intakes: List<IntakeDto>, now: Instant): AdherenceDto {
        val since7 = now.minus(Duration.ofDays(7))
        val since30 = now.minus(Duration.ofDays(30))
        val lastDone = intakes.filter { it.status != "MISSED" }.maxOfOrNull { it.scheduledAtEpochMs }
        return when (r.kind) {
            PrescriptionKind.MEDICATION -> {
                val due = { from: Instant -> PrescriptionRules.dueDoses(r.times, r.start, r.end, r.created, r.stopped, from, now, zone) }
                val due7 = due(since7)
                val due30 = due(since30)
                val taken = intakes.filter { it.status == "TAKEN" }.map { it.scheduledAtEpochMs }.toSet()
                AdherenceDto(
                    due7 = due7.size, done7 = due7.count { it.toEpochMilli() in taken },
                    due30 = due30.size, done30 = due30.count { it.toEpochMilli() in taken },
                    missed7 = intakes.count { it.status == "MISSED" && it.scheduledAtEpochMs >= since7.toEpochMilli() },
                    lastDoneEpochMs = lastDone,
                )
            }
            PrescriptionKind.EXERCISE -> {
                // Due in proportion to the days the plan has been running, at most the whole week or month.
                val running = { from: Instant ->
                    val begin = maxOf(from, r.created, r.start.atStartOfDay(zone).toInstant())
                    val endAt = minOf(now, r.stopped ?: Instant.MAX, r.end?.plusDays(1)?.atStartOfDay(zone)?.toInstant() ?: Instant.MAX)
                    Duration.between(begin, endAt).toHours().coerceAtLeast(0) / 24.0
                }
                val perDay = (r.perWeek ?: 0) / 7.0
                val done = { from: Instant -> intakes.count { it.status == "DONE" && it.scheduledAtEpochMs >= from.toEpochMilli() } }
                AdherenceDto(
                    due7 = Math.round(running(since7) * perDay).toInt(), done7 = done(since7),
                    due30 = Math.round(running(since30) * perDay).toInt(), done30 = done(since30),
                    lastDoneEpochMs = lastDone,
                )
            }
        }
    }

    private class Row(
        val id: Long, val doctorId: Long, val doctorName: String, val kind: PrescriptionKind, val name: String,
        val dose: String?, val times: List<LocalTime>, val perWeek: Int?, val instructions: String?,
        val start: LocalDate, val end: LocalDate?, val created: Instant, val stopped: Instant?,
    ) {
        fun toDto(active: Boolean, adherence: AdherenceDto) = PrescriptionDto(
            id = id, doctorId = doctorId, doctorName = doctorName, kind = kind, name = name, dose = dose,
            times = times.map { it.toString() }, perWeek = perWeek, instructions = instructions,
            startDate = start.toString(), endDate = end?.toString(), createdAtEpochMs = created.toEpochMilli(),
            stoppedAtEpochMs = stopped?.toEpochMilli(), active = active, adherence = adherence,
        )
    }

    private fun ResultSet.toRow() = Row(
        id = getLong("id"),
        doctorId = getLong("doctor_id"),
        doctorName = getString("full_name"),
        kind = PrescriptionKind.valueOf(getString("kind")),
        name = getString("name"),
        dose = getString("dose"),
        times = getString("times")?.split(',')?.map(LocalTime::parse).orEmpty(),
        perWeek = intOrNull("per_week"),
        instructions = getString("instructions"),
        start = getObject("start_date", LocalDate::class.java),
        end = getObject("end_date", LocalDate::class.java),
        created = instant("created_at")!!,
        stopped = instant("stopped_at"),
    )

    private fun ResultSet.toIntake() = IntakeDto(
        prescriptionId = getLong("prescription_id"),
        scheduledAtEpochMs = epochMs("scheduled_at")!!,
        status = getString("status"),
        recordedAtEpochMs = epochMs("recorded_at")!!,
    )
}

/** The doctor's prescription routes for the linked patient {id} ([linked] answers 404 otherwise). */
fun Route.doctorPrescriptionRoutes(services: Services, doctorOf: suspend io.ktor.server.application.ApplicationCall.() -> Pair<Long, Long>) {
    get("/patients/{id}/prescriptions") {
        val (doctorId, patientId) = call.doctorOf()
        call.respond(io { services.prescriptions.ofPatient(patientId, doctorId) })
    }
    post("/patients/{id}/prescriptions") {
        val (doctorId, patientId) = call.doctorOf()
        val p = PrescriptionRules.validate(call.receive<PrescriptionIn>(), services.prescriptions.today())
        call.respond(HttpStatusCode.Created, io { services.prescriptions.add(doctorId, patientId, p) })
    }
    get("/patients/{id}/intakes") {
        val (doctorId, patientId) = call.doctorOf()
        val days = call.request.queryParameters["days"]?.toIntOrNull()?.coerceIn(1, 90) ?: 14
        call.respond(io { services.prescriptions.intakes(patientId, doctorId, days) })
    }
}
