package com.example.parkinson.server

import io.ktor.client.call.body
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Nightly backups made by the server, and restoring one into a new database. */
class BackupsTest {
    @Test
    fun aBackupRestoresEverythingIntoANewDatabase() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111", "دکتر احمدی")
        val invite = env.client.postJson("/api/doctor/invites", Unit, doctor).body<InviteDto>()
        env.client.postJson("/api/device/link", LinkRequest(invite.code, patient())).body<LinkResponse>()
        val patientId = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().id
        val path = "/api/doctor/patients/$patientId/prescriptions"
        val first = env.client.postJson(path, PrescriptionIn(PrescriptionKind.MEDICATION, "لوودوپا", times = listOf("08:00")), doctor).body<PrescriptionDto>()

        val dir = Files.createTempDirectory("backups")
        val dataSource = TestDb.current()
        val backups = Backups(dataSource, dir, keepDays = 14, zone = ZoneId.of("Asia/Tehran"))
        val file = backups.backupNow()
        assertTrue(Files.size(file) > 0)

        // Not into a database that has data.
        assertFailsWith<IllegalArgumentException> { Backups.restore(dataSource, file) }

        TestDb.fresh()
        assertTrue(Backups.restore(dataSource, file) > 5)
        // The same accounts, patients and prescriptions; new rows get new ids.
        val again = env.client.token("09121111111", "doctor-password")
        assertEquals(listOf("لوودوپا"), env.client.getAuth(path, again).body<List<PrescriptionDto>>().map { it.name })
        val next = env.client.postJson(path, PrescriptionIn(PrescriptionKind.EXERCISE, "راه رفتن", perWeek = 3), again)
        assertEquals(HttpStatusCode.Created, next.status)
        assertTrue(next.body<PrescriptionDto>().id > first.id)
    }
}
