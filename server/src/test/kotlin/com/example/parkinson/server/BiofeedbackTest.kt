package com.example.parkinson.server

import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stage 6: biofeedback exercises prescribed from the library, and the sessions the phone sends. */
class BiofeedbackTest {
    private suspend fun TestEnv.linkedPhone(doctor: String, p: PatientIn = patient()): String {
        val invite = client.postJson("/api/doctor/invites", Unit, doctor).body<InviteDto>()
        return client.postJson("/api/device/link", LinkRequest(invite.code, p)).body<LinkResponse>().patientToken
    }

    @Test
    fun theDoctorPrescribesAnExerciseAndSeesTheSessions() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val phone = env.linkedPhone(doctor)
        val patientId = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().id
        val path = "/api/doctor/patients/$patientId/prescriptions"

        val library = env.client.getAuth("/api/doctor/biofeedback/exercises", doctor).body<List<ExerciseInfo>>()
        assertTrue(library.any { it.code == "BALANCE" && it.standing })
        assertEquals("invalid_exercise", env.client.postJson(path,
            PrescriptionIn(PrescriptionKind.BIOFEEDBACK, "", perWeek = 3, exercise = "FLYING", level = 1), doctor).body<ErrorDto>().error)
        assertEquals("invalid_level", env.client.postJson(path,
            PrescriptionIn(PrescriptionKind.BIOFEEDBACK, "", perWeek = 3, exercise = "LOUD_VOICE", level = 4), doctor).body<ErrorDto>().error)

        val created = env.client.postJson(path,
            PrescriptionIn(PrescriptionKind.BIOFEEDBACK, "", perWeek = 5, exercise = "LOUD_VOICE", level = 2), doctor)
        assertEquals(HttpStatusCode.Created, created.status)
        val loud = created.body<PrescriptionDto>()
        assertEquals(BiofeedbackExercise.LOUD_VOICE.title, loud.name) // The library's name when none is written.
        assertEquals(2, loud.level)
        assertEquals("LOUD_VOICE", env.client.getAuth("/api/device/prescriptions", phone).body<List<PrescriptionDto>>().single().exercise)

        val now = System.currentTimeMillis()
        val sessions = SessionsUpload(listOf(
            SessionIn(loud.id, now - 86_400_000, 120_000, 64, metrics = mapOf("repsInTarget" to 3.0, "meanAboveBaselineDb" to 5.5)),
            SessionIn(loud.id, now - 60_000, 30_000, 20, completed = false),
        ))
        assertEquals(HttpStatusCode.OK, env.client.postJson("/api/device/biofeedback/sessions", sessions, phone).status)
        // Sent again (the phone was not sure it arrived): replaced, not doubled.
        env.client.postJson("/api/device/biofeedback/sessions", SessionsUpload(sessions.sessions.take(1)), phone)
        assertEquals("invalid_session", env.client.postJson("/api/device/biofeedback/sessions",
            SessionsUpload(listOf(SessionIn(loud.id, now, 1000, 140))), phone).body<ErrorDto>().error)
        assertEquals("invalid_session", env.client.postJson("/api/device/biofeedback/sessions",
            SessionsUpload(listOf(SessionIn(loud.id, now, 1000, 40, metrics = mapOf("bad key" to 1.0)))), phone).body<ErrorDto>().error)

        val seen = env.client.getAuth("/api/doctor/patients/$patientId/biofeedback", doctor).body<List<SessionDto>>()
        assertEquals(listOf(20, 64), seen.map { it.score })
        assertEquals(5.5, seen.last().metrics["meanAboveBaselineDb"])
        assertEquals("LOUD_VOICE", seen.first().exercise)
        // Only the completed session counts as done.
        assertEquals(1, env.client.getAuth(path, doctor).body<List<PrescriptionDto>>().single().adherence!!.done7)
    }

    @Test
    fun anotherPatientsPhoneCannotSendSessions() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val other = env.client.newDoctor("09122222222")
        env.linkedPhone(doctor)
        val otherPhone = env.linkedPhone(other, patient(nationalId(223456789), "رضا"))
        val patientId = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().id
        val p = env.client.postJson("/api/doctor/patients/$patientId/prescriptions",
            PrescriptionIn(PrescriptionKind.BIOFEEDBACK, "", perWeek = 3, exercise = "TAP_RHYTHM", level = 1), doctor).body<PrescriptionDto>()
        assertEquals("unknown_prescription", env.client.postJson("/api/device/biofeedback/sessions",
            SessionsUpload(listOf(SessionIn(p.id, System.currentTimeMillis(), 1000, 50))), otherPhone).body<ErrorDto>().error)
        assertEquals(HttpStatusCode.NotFound, env.client.getAuth("/api/doctor/patients/$patientId/biofeedback", other).status)
    }
}
