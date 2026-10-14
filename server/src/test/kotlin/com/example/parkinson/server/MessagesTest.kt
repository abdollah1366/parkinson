package com.example.parkinson.server

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Stage 4: text and voice messages between a doctor and a linked patient. */
class MessagesTest {

    private suspend fun TestEnv.linkedPhone(doctor: String, p: PatientIn = patient()): String {
        val invite = client.postJson("/api/doctor/invites", Unit, doctor).body<InviteDto>()
        return client.postJson("/api/device/link", LinkRequest(invite.code, p)).body<LinkResponse>().patientToken
    }

    private suspend fun HttpClient.doctorId(doctor: String) = getAuth("/api/me", doctor).body<UserDto>().id

    private suspend fun HttpClient.patientId(doctor: String) =
        getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().id

    private suspend fun HttpClient.voice(path: String, token: String, bytes: ByteArray, ms: Int = 2000): HttpResponse =
        post("$path/messages/voice?ms=$ms") {
            bearerAuth(token)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
        }

    /** The first bytes of an .m4a file as Android records it. */
    private val m4a = byteArrayOf(0, 0, 0, 0x18) + "ftypM4A ".toByteArray() + ByteArray(200) { it.toByte() }
    private val webm = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()) + ByteArray(100)

    @Test
    fun doctorAndPhoneWriteToEachOther() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111", "دکتر احمدی")
        val phone = env.linkedPhone(doctor)
        val doctorId = env.client.doctorId(doctor)
        val patientId = env.client.patientId(doctor)
        val doctorSide = "/api/doctor/patients/$patientId"
        val phoneSide = "/api/device/doctors/$doctorId"

        assertEquals(HttpStatusCode.Created, env.client.postJson("$doctorSide/messages", MessageIn("  سلام، حالتان چطور است؟ "), doctor).status)
        assertEquals("invalid_message", env.client.postJson("$doctorSide/messages", MessageIn("   "), doctor).body<ErrorDto>().error)
        assertEquals("invalid_message", env.client.postJson("$doctorSide/messages", MessageIn("x".repeat(4001)), doctor).body<ErrorDto>().error)

        val conversations = env.client.getAuth("/api/device/conversations", phone).body<List<ConversationDto>>()
        assertEquals(listOf("دکتر احمدی"), conversations.map { it.doctorName })
        assertEquals(1, conversations.single().unread)
        assertNotNull(conversations.single().lastMessageEpochMs)

        val received = env.client.getAuth("$phoneSide/messages", phone).body<List<MessageDto>>()
        assertEquals(listOf("سلام، حالتان چطور است؟"), received.map { it.text })
        assertEquals(true, received.single().fromDoctor)
        assertNull(received.single().readAtEpochMs)

        assertEquals(HttpStatusCode.NoContent, env.client.postJson("$phoneSide/messages/read", Unit, phone).status)
        assertEquals(0, env.client.getAuth("/api/device/conversations", phone).body<List<ConversationDto>>().single().unread)
        assertNotNull(env.client.getAuth("$doctorSide/messages", doctor).body<List<MessageDto>>().single().readAtEpochMs)

        val reply = env.client.postJson("$phoneSide/messages", MessageIn("خوبم، ممنون"), phone).body<MessageDto>()
        assertEquals(false, reply.fromDoctor)
        assertEquals(1, env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().unreadMessages)
        // Only what came after the doctor's last seen message.
        val newer = env.client.getAuth("$doctorSide/messages?after=${received.single().id}", doctor).body<List<MessageDto>>()
        assertEquals(listOf("خوبم، ممنون"), newer.map { it.text })

        // The doctor reading marks only the patient's message, not their own.
        env.client.postJson("$doctorSide/messages/read", Unit, doctor)
        assertEquals(0, env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().unreadMessages)
    }

    @Test
    fun voiceMessagesAreCheckedStoredAndPlayedBack() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val phone = env.linkedPhone(doctor)
        val doctorId = env.client.doctorId(doctor)
        val patientId = env.client.patientId(doctor)
        val doctorSide = "/api/doctor/patients/$patientId"
        val phoneSide = "/api/device/doctors/$doctorId"

        val sent = env.client.voice(phoneSide, phone, m4a, ms = 4500)
        assertEquals(HttpStatusCode.Created, sent.status)
        val message = sent.body<MessageDto>()
        assertEquals(4500, message.audioMs)
        assertNull(message.text)
        assertEquals(HttpStatusCode.Created, env.client.voice(doctorSide, doctor, webm).status)

        val audio = env.client.getAuth("$doctorSide/messages/${message.id}/audio", doctor)
        assertEquals(HttpStatusCode.OK, audio.status)
        assertEquals("audio/mp4", audio.headers["Content-Type"]?.substringBefore(';'))
        assertContentEquals(m4a, audio.readRawBytes())
        assertEquals(HttpStatusCode.OK, env.client.getAuth("$phoneSide/messages/${message.id}/audio", phone).status)

        assertEquals("invalid_voice", env.client.voice(phoneSide, phone, "not a sound".toByteArray()).body<ErrorDto>().error)
        assertEquals("invalid_voice", env.client.voice(phoneSide, phone, m4a, ms = 0).body<ErrorDto>().error)
        assertEquals("invalid_voice", env.client.voice(phoneSide, phone, m4a, ms = Voice.MAX_MS + 1).body<ErrorDto>().error)
        assertEquals(HttpStatusCode.PayloadTooLarge, env.client.voice(phoneSide, phone, m4a + ByteArray(Voice.MAX_BYTES)).status)
        assertEquals(2, env.client.getAuth("$doctorSide/messages", doctor).body<List<MessageDto>>().size)
    }

    @Test
    fun onlyLinkedPairsCanTalk() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val other = env.client.newDoctor("09122222222")
        val phone = env.linkedPhone(doctor)
        val otherPhone = env.linkedPhone(other, patient(nationalId(223456789), "رضا"))
        val doctorId = env.client.doctorId(doctor)
        val otherId = env.client.doctorId(other)
        val patientId = env.client.patientId(doctor)

        val sent = env.client.postJson("/api/doctor/patients/$patientId/messages", MessageIn("سلام"), doctor).body<MessageDto>()
        env.client.voice("/api/doctor/patients/$patientId", doctor, m4a)

        // Another doctor cannot read, write, or play this conversation.
        assertEquals(HttpStatusCode.NotFound, env.client.getAuth("/api/doctor/patients/$patientId/messages", other).status)
        assertEquals(HttpStatusCode.NotFound, env.client.postJson("/api/doctor/patients/$patientId/messages", MessageIn("x"), other).status)
        assertEquals(HttpStatusCode.NotFound, env.client.getAuth("/api/doctor/patients/$patientId/messages/${sent.id + 1}/audio", other).status)
        // A phone cannot write to a doctor it is not linked to, nor read another patient's conversation.
        assertEquals(HttpStatusCode.NotFound, env.client.postJson("/api/device/doctors/$otherId/messages", MessageIn("x"), phone).status)
        assertEquals(HttpStatusCode.NotFound, env.client.getAuth("/api/device/doctors/$doctorId/messages", otherPhone).status)
        assertEquals(HttpStatusCode.NotFound, env.client.getAuth("/api/device/doctors/$doctorId/messages/${sent.id + 1}/audio", otherPhone).status)
        // A text message has no sound.
        assertEquals(HttpStatusCode.NotFound, env.client.getAuth("/api/device/doctors/$doctorId/messages/${sent.id}/audio", phone).status)
        // No token, no messages.
        assertEquals(HttpStatusCode.Unauthorized, env.client.post("/api/device/doctors/$doctorId/messages").status)
    }

    @Test
    fun thePatientPanelSharesTheConversation() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111", "دکتر احمدی")
        val phone = env.linkedPhone(doctor)
        val doctorId = env.client.doctorId(doctor)
        val patientId = env.client.patientId(doctor)
        env.client.put("/api/device/web-password") {
            contentType(ContentType.Application.Json)
            setBody(WebPasswordRequest("patient-pass"))
            bearerAuth(phone)
        }
        val web = env.client.postJson("/api/auth/patient-login", PatientLoginRequest(nationalId(123456789), "patient-pass"))
            .body<PatientLoginResponse>().token

        env.client.postJson("/api/device/doctors/$doctorId/messages", MessageIn("از گوشی"), phone)
        env.client.postJson("/api/patient/doctors/$doctorId/messages", MessageIn("از پنل"), web)
        env.client.postJson("/api/doctor/patients/$patientId/messages", MessageIn("پاسخ پزشک"), doctor)

        assertEquals(1, env.client.getAuth("/api/patient/conversations", web).body<List<ConversationDto>>().single().unread)
        val all = env.client.getAuth("/api/patient/doctors/$doctorId/messages", web).body<List<MessageDto>>()
        assertEquals(listOf("از گوشی", "از پنل", "پاسخ پزشک"), all.map { it.text })
        assertEquals(listOf(false, false, true), all.map { it.fromDoctor })
        // Read on the panel is read on the phone too.
        env.client.postJson("/api/patient/doctors/$doctorId/messages/read", Unit, web)
        assertEquals(0, env.client.getAuth("/api/device/conversations", phone).body<List<ConversationDto>>().single().unread)
        // A staff token does not open the patient's side.
        assertEquals(HttpStatusCode.Unauthorized, env.client.getAuth("/api/patient/doctors/$doctorId/messages", doctor).status)
    }
}
