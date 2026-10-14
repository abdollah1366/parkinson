package com.example.parkinson.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Stage 3: the patient's own panel, the doctor's notes, the analysis and the CSV export. */
class PatientPanelTest {

    private val day = 86_400_000L
    private val start = 1_760_000_000_000L

    private fun result(id: String, day: Int, pi: Int?, med: String? = null, type: String = "finger_tapping") = ResultIn(
        assessmentId = id, type = type, timestampEpochMs = start + day * this.day, hand = "right",
        qualityStatus = if (pi == null) "LOW_QUALITY" else "VALID", qualityScore = 80, performanceIndex = pi,
        medicationState = med, algorithmVersion = "ft-1", scoringVersion = "s-1",
        metrics = buildJsonObject { put("tapCount", 40); put("note", "a,b \"c\"") },
    )

    private suspend fun TestEnv.linkedPhone(doctor: String, p: PatientIn = patient()): String {
        val invite = client.postJson("/api/doctor/invites", Unit, doctor).body<InviteDto>()
        return client.postJson("/api/device/link", LinkRequest(invite.code, p)).body<LinkResponse>().patientToken
    }

    private suspend fun HttpClient.setWebPassword(phone: String, password: String) =
        put("/api/device/web-password") {
            contentType(ContentType.Application.Json)
            setBody(WebPasswordRequest(password))
            bearerAuth(phone)
        }

    private suspend fun HttpClient.patientLogin(nationalId: String, password: String) =
        postJson("/api/auth/patient-login", PatientLoginRequest(nationalId, password))

    @Test
    fun aPatientLogsInWithAPasswordSetFromThePhone() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111", "دکتر احمدی")
        val phone = env.linkedPhone(doctor)
        val id = nationalId(123456789)
        // No panel password yet: no login.
        assertEquals(HttpStatusCode.Unauthorized, env.client.patientLogin(id, "patient-pass").status)

        assertEquals(HttpStatusCode.NoContent, env.client.setWebPassword(phone, "patient-pass").status)
        assertEquals("weak_password", env.client.setWebPassword(phone, "short").body<ErrorDto>().error)
        assertEquals(HttpStatusCode.Unauthorized, env.client.patientLogin(id, "wrong-pass").status)
        // Typed in Persian digits, as on the phone.
        val typed = id.map { '۰' + (it - '0') }.joinToString("")
        val login = env.client.patientLogin(typed, "patient-pass")
        assertEquals(HttpStatusCode.OK, login.status)
        val me = login.body<PatientLoginResponse>()
        assertEquals(listOf("دکتر احمدی"), me.patient.doctors)

        env.client.postJson("/api/device/results", UploadRequest(listOf(result("a1", 0, 60))), phone)
        val mine = env.client.getAuth("/api/patient/results", me.token).body<List<ResultDto>>()
        assertEquals(listOf("a1"), mine.map { it.assessmentId })
    }

    @Test
    fun patientAndStaffTokensNeverCrossOver() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val phone = env.linkedPhone(doctor)
        env.client.setWebPassword(phone, "patient-pass")
        val patientToken = env.client.patientLogin(nationalId(123456789), "patient-pass").body<PatientLoginResponse>().token

        // The patient has id 1, like the admin: their token must not open staff pages, nor a staff token theirs.
        assertEquals(HttpStatusCode.Unauthorized, env.client.getAuth("/api/me", patientToken).status)
        assertEquals(HttpStatusCode.Unauthorized, env.client.getAuth("/api/admin/doctors", patientToken).status)
        assertEquals(HttpStatusCode.Unauthorized, env.client.getAuth("/api/patient/me", doctor).status)
        assertEquals(HttpStatusCode.Unauthorized, env.client.getAuth("/api/patient/results", phone).status)
    }

    @Test
    fun notesAreTheDoctorsOwnAndOnlySharedOnesReachThePatient() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111", "دکتر احمدی")
        val other = env.client.newDoctor("09122222222", "دکتر کریمی")
        val phone = env.linkedPhone(doctor)
        env.linkedPhone(other)
        val pid = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().id

        val private = env.client.postJson("/api/doctor/patients/$pid/notes", NoteIn("برای خودم: لرزش بیشتر شده"), doctor).body<NoteDto>()
        env.client.postJson("/api/doctor/patients/$pid/notes", NoteIn("تمرین روزانه‌ی راه رفتن را ادامه دهید.", sharedWithPatient = true), doctor)
        assertEquals("invalid_note", env.client.postJson("/api/doctor/patients/$pid/notes", NoteIn("  "), doctor).body<ErrorDto>().error)

        assertEquals(2, env.client.getAuth("/api/doctor/patients/$pid/notes", doctor).body<List<NoteDto>>().size)
        assertTrue(env.client.getAuth("/api/doctor/patients/$pid/notes", other).body<List<NoteDto>>().isEmpty())
        // Another doctor cannot delete it.
        assertEquals(HttpStatusCode.NotFound, env.client.delete("/api/doctor/notes/${private.id}") { bearerAuth(other) }.status)

        env.client.setWebPassword(phone, "patient-pass")
        val token = env.client.patientLogin(nationalId(123456789), "patient-pass").body<PatientLoginResponse>().token
        val shared = env.client.getAuth("/api/patient/notes", token).body<List<NoteDto>>()
        assertEquals(listOf("تمرین روزانه‌ی راه رفتن را ادامه دهید."), shared.map { it.text })
        assertEquals("دکتر احمدی", shared.single().doctorName)

        assertEquals(HttpStatusCode.NoContent, env.client.delete("/api/doctor/notes/${private.id}") { bearerAuth(doctor) }.status)
        assertEquals(1, env.client.getAuth("/api/doctor/patients/$pid/notes", doctor).body<List<NoteDto>>().size)
    }

    @Test
    fun theAnalysisFlagsADropAndComparesOnAndOff() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val phone = env.linkedPhone(doctor)
        val falling = listOf(
            result("t1", 0, 80, "on"), result("t2", 5, 78, "off"), result("t3", 10, 76, "on"),
            result("t4", 20, 66, "off"), result("t5", 25, 64, "on"), result("t6", 30, 62, "off"),
            result("t7", 31, null),
        )
        env.client.postJson("/api/device/results", UploadRequest(falling), phone)
        val pid = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().also {
            assertTrue(it.attention)
        }.id

        val a = env.client.getAuth("/api/doctor/patients/$pid/analysis", doctor).body<List<TestAnalysis>>().single()
        assertEquals(7, a.count)
        assertEquals(6, a.scoredCount)
        assertEquals(78.0, a.baseline)
        assertEquals(64.0, a.recent)
        assertEquals(-14.0, a.change)
        assertTrue(a.attention)
        assertEquals((80 + 76 + 64) / 3.0, a.onMean!!, 0.05)
        assertEquals((78 + 66 + 62) / 3.0, a.offMean!!, 0.05)
        assertTrue(a.slopePer30Days!! < -10)
    }

    @Test
    fun fewResultsGiveNoChangeAndNoFlag() {
        val points = listOf(60, 50, 40).mapIndexed { i, v -> ScorePoint("gait", null, start + i * day, v, null) }
        val a = Analysis.summarize(points).single()
        assertNull(a.change)
        assertFalse(a.attention)
        assertNull(a.slopePer30Days) // Under a week.
    }

    @Test
    fun resultsExportAsCsv() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val phone = env.linkedPhone(doctor)
        env.client.postJson("/api/device/results", UploadRequest(listOf(result("a1", 0, 60, "on"))), phone)
        val pid = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().id

        val response = env.client.getAuth("/api/doctor/patients/$pid/results.csv", doctor)
        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers["Content-Disposition"]!!.contains("patient-$pid-results.csv"))
        val lines = response.bodyAsText().removePrefix("﻿").trimEnd().split("\r\n")
        assertEquals(2, lines.size)
        assertTrue(lines[0].startsWith("assessment_id,type,taken_at_utc"))
        assertTrue(lines[1].startsWith("a1,finger_tapping,2025-10-09T"))
        // The JSON column is quoted, with its quotes doubled, so the comma inside stays in one cell.
        assertTrue(lines[1].contains(",\"{\"\"") && lines[1].contains("\"\"tapCount\"\":40") && lines[1].endsWith("}\""), lines[1])
    }
}
