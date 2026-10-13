package com.example.parkinson.server

import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stage 5: prescriptions, doses taken or missed, and how closely the plan was followed. */
class PrescriptionsTest {
    private val tehran = ZoneId.of("Asia/Tehran")

    private suspend fun TestEnv.linkedPhone(doctor: String, p: PatientIn = patient()): String {
        val invite = client.postJson("/api/doctor/invites", Unit, doctor).body<InviteDto>()
        return client.postJson("/api/device/link", LinkRequest(invite.code, p)).body<LinkResponse>().patientToken
    }

    private suspend fun TestEnv.patientId(doctor: String) =
        client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().id

    @Test
    fun dueDosesFollowTheClinicsClockAndDates() {
        val times = listOf(LocalTime.of(8, 0), LocalTime.of(20, 0))
        val start = LocalDate.of(2026, 10, 1)
        val created = LocalDate.of(2026, 9, 30).atStartOfDay(tehran).toInstant()
        val now = LocalDate.of(2026, 10, 3).atTime(12, 0).atZone(tehran).toInstant()
        val due = PrescriptionRules.dueDoses(times, start, null, created, null, Instant.EPOCH, now, tehran)
        // 1st and 2nd: two doses each; 3rd: only 08:00 so far.
        assertEquals(5, due.size)
        assertEquals(LocalDate.of(2026, 10, 1).atTime(8, 0).atZone(tehran).toInstant(), due.first())
        // A dose is not due until an hour after its time.
        val justAfter = LocalDate.of(2026, 10, 3).atTime(8, 30).atZone(tehran).toInstant()
        assertEquals(4, PrescriptionRules.dueDoses(times, start, null, created, null, Instant.EPOCH, justAfter, tehran).size)
        // Ended on the 1st, or stopped at noon on the 1st.
        assertEquals(2, PrescriptionRules.dueDoses(times, start, start, created, null, Instant.EPOCH, now, tehran).size)
        val stopped = start.atTime(12, 0).atZone(tehran).toInstant()
        assertEquals(1, PrescriptionRules.dueDoses(times, start, null, created, stopped, Instant.EPOCH, now, tehran).size)
    }

    @Test
    fun theDoctorPrescribesAndThePhoneRecordsDoses() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111", "دکتر احمدی")
        val phone = env.linkedPhone(doctor)
        val patientId = env.patientId(doctor)
        val path = "/api/doctor/patients/$patientId/prescriptions"

        assertEquals("invalid_times", env.client.postJson(path, PrescriptionIn(PrescriptionKind.MEDICATION, "لوودوپا"), doctor).body<ErrorDto>().error)
        assertEquals("invalid_per_week", env.client.postJson(path, PrescriptionIn(PrescriptionKind.EXERCISE, "راه رفتن"), doctor).body<ErrorDto>().error)
        assertEquals("invalid_dates", env.client.postJson(path,
            PrescriptionIn(PrescriptionKind.EXERCISE, "راه رفتن", perWeek = 3, startDate = "2026-10-10", endDate = "2026-10-01"), doctor).body<ErrorDto>().error)

        val med = env.client.postJson(path, PrescriptionIn(PrescriptionKind.MEDICATION, " لوودوپا ", dose = "یک قرص ۱۰۰/۲۵",
            times = listOf("۲۰:۰۰", "8:00", "08:00"), instructions = "نیم ساعت پیش از غذا"), doctor)
        assertEquals(HttpStatusCode.Created, med.status)
        val m = med.body<PrescriptionDto>()
        assertEquals("لوودوپا", m.name)
        assertEquals(listOf("08:00", "20:00"), m.times)
        assertTrue(m.active)
        val walk = env.client.postJson(path, PrescriptionIn(PrescriptionKind.EXERCISE, "راه رفتن ۲۰ دقیقه", perWeek = 5), doctor).body<PrescriptionDto>()

        // The phone gets both, and sends doses and an exercise.
        val onPhone = env.client.getAuth("/api/device/prescriptions", phone).body<List<PrescriptionDto>>()
        assertEquals(setOf(m.id, walk.id), onPhone.map { it.id }.toSet())
        assertEquals("دکتر احمدی", onPhone.first().doctorName)
        val now = System.currentTimeMillis()
        val upload = IntakesUpload(listOf(
            IntakeIn(m.id, now - 3_600_000, "TAKEN"),
            IntakeIn(m.id, now - 7_200_000, "MISSED"),
            IntakeIn(walk.id, now - 600_000, "DONE"),
        ))
        assertEquals(HttpStatusCode.OK, env.client.postJson("/api/device/intakes", upload, phone).status)
        // Sending the same dose again changes its status, not the count.
        env.client.postJson("/api/device/intakes", IntakesUpload(listOf(IntakeIn(m.id, now - 7_200_000, "TAKEN"))), phone)
        assertEquals("invalid_status", env.client.postJson("/api/device/intakes",
            IntakesUpload(listOf(IntakeIn(walk.id, now, "TAKEN"))), phone).body<ErrorDto>().error)

        val intakes = env.client.getAuth("/api/doctor/patients/$patientId/intakes", doctor).body<List<IntakeDto>>()
        assertEquals(3, intakes.size)
        assertEquals(listOf("DONE", "TAKEN", "TAKEN"), intakes.map { it.status })
        val seen = env.client.getAuth(path, doctor).body<List<PrescriptionDto>>()
        assertEquals(1, seen.first { it.id == walk.id }.adherence!!.done7)

        // Stopping keeps the history but takes it off the phone.
        assertEquals(HttpStatusCode.NoContent, env.client.postJson("/api/doctor/prescriptions/${m.id}/stop", Unit, doctor).status)
        assertEquals(listOf(walk.id), env.client.getAuth("/api/device/prescriptions", phone).body<List<PrescriptionDto>>().map { it.id })
        val history = env.client.getAuth(path, doctor).body<List<PrescriptionDto>>()
        assertEquals(false, history.first { it.id == m.id }.active)
    }

    @Test
    fun onlyTheLinkedDoctorAndPatientSeeIt() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val other = env.client.newDoctor("09122222222")
        val phone = env.linkedPhone(doctor)
        val otherPhone = env.linkedPhone(other, patient(nationalId(223456789), "رضا"))
        val patientId = env.patientId(doctor)
        val med = env.client.postJson("/api/doctor/patients/$patientId/prescriptions",
            PrescriptionIn(PrescriptionKind.MEDICATION, "رازاژیلین", times = listOf("09:00")), doctor).body<PrescriptionDto>()

        assertEquals(HttpStatusCode.NotFound, env.client.getAuth("/api/doctor/patients/$patientId/prescriptions", other).status)
        assertEquals(HttpStatusCode.NotFound, env.client.postJson("/api/doctor/prescriptions/${med.id}/stop", Unit, other).status)
        // Another patient's phone cannot record doses for it.
        assertEquals("unknown_prescription", env.client.postJson("/api/device/intakes",
            IntakesUpload(listOf(IntakeIn(med.id, System.currentTimeMillis(), "TAKEN"))), otherPhone).body<ErrorDto>().error)
        assertEquals(0, env.client.getAuth("/api/device/prescriptions", otherPhone).body<List<PrescriptionDto>>().size)
        assertEquals(1, env.client.getAuth("/api/device/prescriptions", phone).body<List<PrescriptionDto>>().size)
    }
}
