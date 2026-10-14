package com.example.parkinson.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerTest {

    private fun result(id: String, type: String = "finger_tapping", pi: Int? = 72, med: String? = "on") = ResultIn(
        assessmentId = id,
        type = type,
        timestampEpochMs = 1_760_000_000_000,
        hand = "right",
        qualityStatus = "VALID",
        qualityScore = 90,
        performanceIndex = pi,
        medicationState = med,
        algorithmVersion = "ft-1",
        scoringVersion = "s-1",
        metrics = buildJsonObject { put("tapCount", 41); put("meanIntervalMs", 243.5) },
    )

    /** Doctor makes an invite; the phone links with it. Returns the phone's token. */
    private suspend fun TestEnv.link(doctor: String, p: PatientIn = patient()): String {
        val invite = client.postJson("/api/doctor/invites", Unit, doctor).body<InviteDto>()
        val response = client.postJson("/api/device/link", LinkRequest(invite.code, p))
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return response.body<LinkResponse>().patientToken
    }

    @Test
    fun health() = withServer { env ->
        assertEquals("ok", env.client.get("/health").bodyAsText())
    }

    @Test
    fun loginChecksPasswordAndRole() = withServer { env ->
        assertEquals(HttpStatusCode.Unauthorized, env.client.login(ADMIN_MOBILE, "wrong-password").status)
        assertEquals(HttpStatusCode.Unauthorized, env.client.login("09129999999", "whatever-pass").status)
        // Persian digits and +98 are accepted, as people type them.
        assertEquals(HttpStatusCode.OK, env.client.login("+98 ۹۱۲ ۰۰۰ ۰۰۰۰", ADMIN_PASSWORD).status)

        val doctor = env.client.newDoctor("09121111111")
        assertEquals(HttpStatusCode.Forbidden, env.client.getAuth("/api/admin/doctors", doctor).status)
        val admin = env.client.token(ADMIN_MOBILE, ADMIN_PASSWORD)
        assertEquals(HttpStatusCode.Forbidden, env.client.getAuth("/api/doctor/patients", admin).status)
        assertEquals(HttpStatusCode.Unauthorized, env.client.get("/api/doctor/patients").status)
        assertEquals(HttpStatusCode.Unauthorized, env.client.getAuth("/api/doctor/patients", "not-a-token").status)
    }

    @Test
    fun adminManagesDoctors() = withServer { env ->
        val admin = env.client.token(ADMIN_MOBILE, ADMIN_PASSWORD)
        val created = env.client.postJson("/api/admin/doctors", NewDoctorRequest("دکتر احمدی", "09121111111", "doctor-password", "۱۲۳۴۵"), admin)
        assertEquals(HttpStatusCode.Created, created.status)
        assertEquals("12345", created.body<UserDto>().medicalCouncilNo)

        val duplicate = env.client.postJson("/api/admin/doctors", NewDoctorRequest("دیگری", "09121111111", "doctor-password"), admin)
        assertEquals(HttpStatusCode.Conflict, duplicate.status)
        assertEquals("mobile_taken", duplicate.body<ErrorDto>().error)
        val weak = env.client.postJson("/api/admin/doctors", NewDoctorRequest("دیگری", "09122222222", "short"), admin)
        assertEquals("weak_password", weak.body<ErrorDto>().error)

        assertEquals(listOf("دکتر احمدی"), env.client.getAuth("/api/admin/doctors", admin).body<List<UserDto>>().map { it.fullName })
    }

    @Test
    fun disablingADoctorStopsTheirSessionAtOnce() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val id = env.client.getAuth("/api/me", doctor).body<UserDto>().id
        val admin = env.client.token(ADMIN_MOBILE, ADMIN_PASSWORD)
        env.client.send("/api/admin/doctors/$id", admin, UpdateDoctorRequest(active = false))

        assertEquals(HttpStatusCode.Unauthorized, env.client.getAuth("/api/doctor/patients", doctor).status)
        assertEquals("account_disabled", env.client.login("09121111111", "doctor-password").body<ErrorDto>().error)
    }

    @Test
    fun phoneLinksUploadsAndTheDoctorSeesTheResults() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val phone = env.link(doctor)

        val first = env.client.postJson("/api/device/results", UploadRequest(listOf(result("a1"), result("a2", "gait", 55, "off"))), phone)
        assertEquals(listOf("a1", "a2"), first.body<UploadResponse>().stored)
        // Sending again (the phone did not hear the answer) stores nothing twice.
        val again = env.client.postJson("/api/device/results", UploadRequest(listOf(result("a1"))), phone)
        assertEquals(listOf("a1"), again.body<UploadResponse>().stored)

        val patients = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>()
        val p = patients.single()
        assertEquals("علی رضایی", p.fullName)
        assertEquals(2, p.resultCount)
        assertEquals(nationalId(123456789).takeLast(4), p.nationalIdLast4)

        val detail = env.client.getAuth("/api/doctor/patients/${p.id}", doctor).body<PatientDetail>()
        assertEquals("09123456789", detail.mobile)

        val results = env.client.getAuth("/api/doctor/patients/${p.id}/results", doctor).body<List<ResultDto>>()
        assertEquals(setOf("a1", "a2"), results.map { it.assessmentId }.toSet())
        val tapping = results.single { it.assessmentId == "a1" }
        assertEquals(JsonPrimitive(41), tapping.metrics["tapCount"])
        assertEquals("on", tapping.medicationState)
        val gaitOnly = env.client.getAuth("/api/doctor/patients/${p.id}/results?type=gait", doctor).body<List<ResultDto>>()
        assertEquals(listOf("a2"), gaitOnly.map { it.assessmentId })

        val logged = env.services.db.tx { query("SELECT action FROM access_log WHERE patient_id = ? ORDER BY id", p.id) { it.getString(1) } }
        assertEquals(listOf("view_profile", "view_results", "view_results"), logged)

        val status = env.client.getAuth("/api/device/status", phone).body<DeviceStatus>()
        assertEquals(2, status.resultCount)
        assertEquals(listOf("دکتر 09121111111"), status.doctors)
    }

    @Test
    fun aDoctorCannotSeeAnotherDoctorsPatient() = withServer { env ->
        val mine = env.client.newDoctor("09121111111")
        val other = env.client.newDoctor("09122222222")
        val phone = env.link(mine)
        env.client.postJson("/api/device/results", UploadRequest(listOf(result("a1"))), phone)
        val id = env.client.getAuth("/api/doctor/patients", mine).body<List<PatientSummary>>().single().id

        assertTrue(env.client.getAuth("/api/doctor/patients", other).body<List<PatientSummary>>().isEmpty())
        assertEquals(HttpStatusCode.NotFound, env.client.getAuth("/api/doctor/patients/$id", other).status)
        assertEquals(HttpStatusCode.NotFound, env.client.getAuth("/api/doctor/patients/$id/results", other).status)
    }

    @Test
    fun theSamePersonWithTwoDoctorsIsOnePatient() = withServer { env ->
        val first = env.client.newDoctor("09121111111")
        val second = env.client.newDoctor("09122222222")
        env.link(first)
        env.link(second, patient(firstName = "علی‌اکبر"))

        val a = env.client.getAuth("/api/doctor/patients", first).body<List<PatientSummary>>().single()
        val b = env.client.getAuth("/api/doctor/patients", second).body<List<PatientSummary>>().single()
        assertEquals(a.id, b.id)
        assertEquals("علی‌اکبر رضایی", a.fullName)
    }

    @Test
    fun invitesAreSingleUseAndExpire() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val invite = env.client.postJson("/api/doctor/invites", Unit, doctor).body<InviteDto>()
        // Typed with a dash, in lower case and Persian digits: still the same code.
        val typed = invite.code.lowercase().chunked(4).joinToString("-").map { if (it in '0'..'9') '۰' + (it - '0') else it }.joinToString("")
        assertEquals(HttpStatusCode.OK, env.client.postJson("/api/device/link", LinkRequest(typed, patient())).status)
        val reused = env.client.postJson("/api/device/link", LinkRequest(invite.code, patient(nationalId(987654321))))
        assertEquals("invite_used", reused.body<ErrorDto>().error)
        assertEquals("invalid_invite", env.client.postJson("/api/device/link", LinkRequest("AAAAAAAA", patient())).body<ErrorDto>().error)

        val doctorId = env.client.getAuth("/api/me", doctor).body<UserDto>().id
        val old = env.services.invites.create(doctorId, Instant.now() - Duration.ofDays(8))
        assertEquals("invite_expired", env.client.postJson("/api/device/link", LinkRequest(old.code, patient())).body<ErrorDto>().error)

        val listed = env.client.getAuth("/api/doctor/invites", doctor).body<List<InviteDto>>()
        assertEquals("علی رضایی", listed.single { it.code == invite.code }.patientName)
        assertNull(listed.single { it.code == old.code }.usedAtEpochMs)
    }

    @Test
    fun theNationalIdIsNeverStoredInClear() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val id = nationalId(123456789)
        env.link(doctor, patient(id))
        val row = env.services.db.tx {
            single("SELECT row_to_json(p)::text AS j FROM patients p") { it.getString("j") }!!
        }
        assertFalse(id in row, row)
    }

    @Test
    fun badInputIsRefused() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val invite = env.client.postJson("/api/doctor/invites", Unit, doctor).body<InviteDto>()
        val badId = env.client.postJson("/api/device/link", LinkRequest(invite.code, patient("1234567890")))
        assertEquals("invalid_national_id", badId.body<ErrorDto>().error)
        val badJson = env.client.postJson("/api/device/link", mapOf("inviteCode" to invite.code))
        assertEquals(HttpStatusCode.BadRequest, badJson.status)

        val phone = env.link(doctor)
        val badScore = env.client.postJson("/api/device/results", UploadRequest(listOf(result("a1", pi = 140))), phone)
        assertEquals("invalid_performance_index", badScore.body<ErrorDto>().error)
        val badMed = env.client.postJson("/api/device/results", UploadRequest(listOf(result("a1", med = "maybe"))), phone)
        assertEquals("invalid_medication_state", badMed.body<ErrorDto>().error)
        // Nothing from a refused batch is kept.
        assertEquals(0, env.client.getAuth("/api/device/status", phone).body<DeviceStatus>().resultCount)
    }

    @Test
    fun aRevokedOrUnknownPhoneTokenCannotUpload() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val phone = env.link(doctor)
        val upload = UploadRequest(listOf(result("a1")))
        assertEquals(HttpStatusCode.Unauthorized, env.client.postJson("/api/device/results", upload, "made-up-token").status)
        env.client.delete("/api/device/token") { bearerAuth(phone) }
        assertEquals(HttpStatusCode.Unauthorized, env.client.postJson("/api/device/results", upload, phone).status)
    }

    @Test
    fun thePhoneCanUpdateTheProfileButNotTheNationalId() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val phone = env.link(doctor)
        val changed = patient(nationalId(555555555)).copy(age = 65, takesParkinsonMedication = false)
        val response = env.client.send("/api/device/profile", phone, changed, put = true)
        assertEquals(HttpStatusCode.NoContent, response.status)

        val p = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single()
        assertEquals(65, p.age)
        assertEquals(false, p.takesParkinsonMedication)
        assertEquals(nationalId(123456789).takeLast(4), p.nationalIdLast4)
    }

    @Test
    fun staffCanChangeTheirPassword() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val wrong = env.client.postJson("/api/me/password", ChangePasswordRequest("nope-nope", "new-password-1"), doctor)
        assertEquals("wrong_password", wrong.body<ErrorDto>().error)
        val ok = env.client.postJson("/api/me/password", ChangePasswordRequest("doctor-password", "new-password-1"), doctor)
        assertEquals(HttpStatusCode.NoContent, ok.status)
        assertEquals(HttpStatusCode.OK, env.client.login("09121111111", "new-password-1").status)
    }

    @Test
    fun thePanelIsServed() = withServer { env ->
        val page = env.client.get("/")
        assertEquals(HttpStatusCode.OK, page.status)
        assertTrue("Content-Security-Policy" in page.headers.names())
    }
}

private suspend inline fun <reified T> HttpClient.send(path: String, token: String, body: T, put: Boolean = false) =
    request(path) {
        method = if (put) HttpMethod.Put else HttpMethod.Patch
        contentType(ContentType.Application.Json)
        setBody(body)
        bearerAuth(token)
    }
