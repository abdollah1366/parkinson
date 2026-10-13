package com.example.parkinson.server

import io.ktor.client.call.body
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Stage 4: ringing, answering and passing WebRTC messages between a doctor and a linked patient. */
class CallsTest {

    private suspend fun TestEnv.linkedPhone(doctor: String, p: PatientIn = patient()): String {
        val invite = client.postJson("/api/doctor/invites", Unit, doctor).body<InviteDto>()
        return client.postJson("/api/device/link", LinkRequest(invite.code, p)).body<LinkResponse>().patientToken
    }

    private class Peer(val ws: DefaultClientWebSocketSession) {
        suspend fun send(type: String, fill: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
            ws.send(Frame.Text(buildJsonObject { put("type", type); fill() }.toString()))

        suspend fun next(): JsonObject = withTimeout(5000) {
            val frame = ws.incoming.receive() as Frame.Text
            ApiJson.parseToJsonElement(frame.readText()).jsonObject
        }

        suspend fun expect(type: String): JsonObject {
            val m = next()
            assertEquals(type, m["type"]!!.jsonPrimitive.content, m.toString())
            return m
        }
    }

    private suspend fun ApplicationTestBuilder.connect(token: String): Peer {
        val client = createClient { install(WebSockets) }
        val peer = Peer(client.webSocketSession("/api/calls/ws"))
        peer.send("auth") { put("token", token) }
        return peer
    }

    private val JsonObject.type get() = this["type"]!!.jsonPrimitive.content
    private val JsonObject.callId get() = this["callId"]!!.jsonPrimitive.content

    @Test
    fun aDoctorCallsAPatientWhoAnswers() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111", "دکتر احمدی")
        val phone = env.linkedPhone(doctor)
        val patientId = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().id

        val d = connect(doctor)
        val ready = d.expect("ready")
        assertEquals(true, ready["me"]!!.jsonObject["doctor"]!!.jsonPrimitive.boolean)
        assertEquals(0, ready["iceServers"]!!.jsonArray.size) // No relay in tests.
        val p = connect(phone)
        p.expect("ready")

        d.send("call") { put("to", patientId); put("video", true) }
        val callId = d.expect("calling").callId
        val ring = p.expect("ring")
        assertEquals(callId, ring.callId)
        assertEquals("دکتر احمدی", ring["from"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        // The app sees it ringing too, before it opens its call screen.
        assertEquals(listOf(callId), env.client.getAuth("/api/device/calls", phone).body<List<RingDto>>().map { it.callId })

        // Someone else's phone cannot turn it down.
        val otherDoctor = env.client.newDoctor("09122222222")
        val otherPhone = env.linkedPhone(otherDoctor, patient(nationalId(223456789), "رضا"))
        assertEquals(HttpStatusCode.NotFound, env.client.postJson("/api/device/calls/$callId/decline", Unit, otherPhone).status)

        p.send("accept") { put("callId", callId) }
        d.expect("accepted")
        d.send("signal") { put("callId", callId); putJsonObject("data") { put("sdp", "offer") } }
        assertEquals("offer", p.expect("signal")["data"]!!.jsonObject["sdp"]!!.jsonPrimitive.content)
        p.send("signal") { put("callId", callId); putJsonObject("data") { put("sdp", "answer") } }
        assertEquals("answer", d.expect("signal")["data"]!!.jsonObject["sdp"]!!.jsonPrimitive.content)

        p.send("hangup") { put("callId", callId) }
        assertEquals("hangup", d.expect("ended")["reason"]!!.jsonPrimitive.content)
        // An answered call leaves no "missed call" line.
        assertEquals(0, env.services.messages.list(env.client.doctorIdOf(doctor), patientId).size)
    }

    @Test
    fun aCallNobodyAnswersIsNotedInTheConversation() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val phone = env.linkedPhone(doctor)
        val doctorId = env.client.doctorIdOf(doctor)
        val p = connect(phone)
        val patientId = p.expect("ready")["me"]!!.jsonObject["id"]!!.jsonPrimitive.long

        p.send("call") { put("to", doctorId); put("video", false) }
        val callId = p.expect("calling").callId
        // The doctor opens the panel while it rings: the call reaches them then.
        val d = connect(doctor)
        d.expect("ready")
        assertEquals(callId, d.expect("ring").callId)
        d.send("decline") { put("callId", callId) }
        assertEquals("declined", p.expect("ended")["reason"]!!.jsonPrimitive.content)

        // The doctor calls back; the app turns it down from its ringing screen.
        d.send("call") { put("to", patientId); put("video", true) }
        val back = d.expect("calling").callId
        assertEquals(back, p.expect("ring").callId)
        assertEquals(HttpStatusCode.NoContent, env.client.postJson("/api/device/calls/$back/decline", Unit, phone).status)
        assertEquals("declined", d.expect("ended")["reason"]!!.jsonPrimitive.content)
        assertEquals("declined", p.expect("ended")["reason"]!!.jsonPrimitive.content)
        val noted = env.services.messages.list(doctorId, patientId).first()
        assertEquals(false, noted.fromDoctor)
        assertTrue(noted.text!!.contains("تماس صوتی"))
    }

    @Test
    fun onlyLinkedPeopleCanCall() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val other = env.client.newDoctor("09122222222")
        env.linkedPhone(doctor)
        val patientId = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().id

        val o = connect(other)
        o.expect("ready")
        o.send("call") { put("to", patientId); put("video", true) }
        assertEquals("not_found", o.expect("ended")["reason"]!!.jsonPrimitive.content)

        val stranger = connect("not-a-token")
        assertEquals("unauthorized", stranger.expect("error")["error"]!!.jsonPrimitive.content)
    }

    @Test
    fun hangingUpWhileRingingStopsEveryRing() = withServer { env ->
        val doctor = env.client.newDoctor("09121111111")
        val phone = env.linkedPhone(doctor)
        val patientId = env.client.getAuth("/api/doctor/patients", doctor).body<List<PatientSummary>>().single().id
        val d = connect(doctor)
        d.expect("ready")
        val p1 = connect(phone)
        p1.expect("ready")
        val p2 = connect(phone)
        p2.expect("ready")

        d.send("call") { put("to", patientId); put("video", true) }
        val callId = d.expect("calling").callId
        p1.expect("ring"); p2.expect("ring")
        p2.send("accept") { put("callId", callId) }
        assertEquals("answered_elsewhere", p1.expect("ended")["reason"]!!.jsonPrimitive.content)
        d.expect("accepted")
        // A second call to someone already in a call is busy.
        d.send("call") { put("to", patientId); put("video", false) }
        assertEquals("busy", d.expect("ended")["reason"]!!.jsonPrimitive.content)
        d.ws.close()
        assertEquals("hangup", p2.expect("ended")["reason"]!!.jsonPrimitive.content)
    }

    private suspend fun io.ktor.client.HttpClient.doctorIdOf(token: String) = getAuth("/api/me", token).body<UserDto>().id
}
