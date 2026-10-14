package com.example.parkinson.server

import com.auth0.jwt.exceptions.JWTVerificationException
import io.ktor.server.websocket.DefaultWebSocketServerSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

// Stage 4: voice and video calls between a doctor and a linked patient. Browsers (the panels, and the app through a
// WebView) talk WebRTC directly; this hub only introduces them over a WebSocket (ring, answer, the WebRTC offer, answer
// and network candidates) and hands out logins for the TURN relay. Calls are not recorded.

/** Someone who can call: a doctor (staff id) or a patient (patient id), with the name the other side sees. */
data class Caller(val doctor: Boolean, val id: Long, val name: String) {
    val key get() = (if (doctor) "d" else "p") + id
}

/** A call ringing for a patient's phone, which the app checks while it is open. */
@Serializable
data class RingDto(val callId: String, val doctorId: Long, val doctorName: String, val video: Boolean)

class CallHub(
    private val services: Services,
    private val turn: TurnServer?,
    private val turnUrls: List<String>,
    private val ringMs: Long = 40_000,
) {
    private val log = LoggerFactory.getLogger("calls")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Any()
    private val sessions = HashMap<String, MutableSet<Session>>()
    private val calls = HashMap<String, Call>()
    private val nextSession = AtomicLong()

    private inner class Session(val who: Caller) {
        val id = nextSession.incrementAndGet()
        val out = Channel<String>(256)
        fun send(message: JsonObject) {
            out.trySend(message.toString())
        }
    }

    private inner class Call(val id: String, val caller: Session, val callee: Caller, val video: Boolean) {
        /** The callee's session that answered; null while ringing. */
        var answered: Session? = null
        fun other(s: Session) = if (s === caller) answered else caller
    }

    /** Calls ringing for this patient (for the app, which has no open connection while it only shows its screens). */
    fun ringingFor(patientId: Long): List<RingDto> = synchronized(lock) {
        calls.values.filter { it.answered == null && !it.callee.doctor && it.callee.id == patientId }
            .map { RingDto(it.id, it.caller.who.id, it.caller.who.name, it.video) }
    }

    /** The app turns down a call from its ringing screen, without opening a call screen. */
    suspend fun declineFor(patientId: Long, callId: String): Boolean {
        val ringingHere = synchronized(lock) { calls[callId]?.let { it.answered == null && it.callee.key == "p$patientId" } ?: false }
        if (ringingHere) end(callId, null, "declined", onlyWhileRinging = true)
        return ringingHere
    }

    /** Runs one WebSocket: the first message must log in with any token of the panels or the app. */
    suspend fun serve(ws: DefaultWebSocketServerSession) {
        val first = withTimeoutOrNull(15_000) { ws.incoming.receiveCatching().getOrNull() } as? Frame.Text
        val token = first?.let { parse(it.readText()) }?.str("token")
        val who = token?.let { identify(it) }
        if (who == null) {
            ws.send(Frame.Text(event("error") { put("error", "unauthorized") }.toString()))
            ws.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "unauthorized"))
            return
        }
        val session = Session(who)
        val writer = ws.launch { for (text in session.out) ws.send(Frame.Text(text)) }
        session.send(ready(who))
        val pending = synchronized(lock) {
            sessions.getOrPut(who.key) { mutableSetOf() } += session
            calls.values.filter { it.answered == null && it.callee.key == who.key }
        }
        pending.forEach { session.send(ring(it)) }
        try {
            for (frame in ws.incoming) {
                if (frame is Frame.Text) parse(frame.readText())?.let { handle(session, it) }
            }
        } catch (e: CancellationException) {
            // The connection went away (closed tab, lost network, server stopping): the same as hanging up.
        } finally {
            withContext(NonCancellable) { leave(session) }
            session.out.close()
            writer.cancel()
        }
    }

    private suspend fun identify(token: String): Caller? = io {
        val staffOrPatient = try {
            services.tokens.verifier.verify(token)
        } catch (e: JWTVerificationException) {
            null
        }
        if (staffOrPatient != null) {
            val id = staffOrPatient.subject?.toLongOrNull() ?: return@io null
            if (staffOrPatient.getClaim("role").asString() == Role.PATIENT.name) {
                if (services.patients.passwordHash(id) == null) return@io null
                services.patients.me(id)?.let { Caller(false, id, it.firstName + " " + it.lastName) }
            } else {
                services.users.byId(id)?.user?.takeIf { it.active && it.role == Role.DOCTOR }?.let { Caller(true, id, it.fullName) }
            }
        } else {
            val id = services.patients.patientForToken(token) ?: return@io null
            services.patients.me(id)?.let { Caller(false, id, it.firstName + " " + it.lastName) }
        }
    }

    private suspend fun handle(s: Session, m: JsonObject) {
        when (m.str("type")) {
            "call" -> startCall(s, m)
            "accept" -> accept(s, m.str("callId") ?: return)
            "decline" -> end(m.str("callId") ?: return, s, "declined", onlyWhileRinging = true)
            "hangup" -> end(m.str("callId") ?: return, s, "hangup")
            "signal" -> {
                val callId = m.str("callId") ?: return
                val data = m["data"] ?: return
                val target = synchronized(lock) { calls[callId]?.takeIf { it.caller === s || it.answered === s }?.other(s) }
                target?.send(event("signal") { put("callId", callId); put("data", data) })
            }
        }
    }

    private suspend fun startCall(s: Session, m: JsonObject) {
        val to = m["to"]?.jsonPrimitive?.longOrNull ?: return
        val video = m["video"]?.jsonPrimitive?.booleanOrNull ?: false
        val me = s.who
        val linked = io { if (me.doctor) services.patients.isLinked(me.id, to) else services.patients.isLinked(to, me.id) }
        if (!linked) return s.send(event("ended") { put("reason", "not_found") })
        val callee = io {
            if (me.doctor) services.patients.me(to)?.let { Caller(false, to, it.firstName + " " + it.lastName) }
            else services.users.byId(to)?.user?.let { Caller(true, to, it.fullName) }
        } ?: return s.send(event("ended") { put("reason", "not_found") })
        val call = Call(UUID.randomUUID().toString(), s, callee, video)
        val ringing = synchronized(lock) {
            val busy = calls.values.any { c -> listOf(c.caller.who.key, c.callee.key).any { it == callee.key || it == me.key } }
            if (busy) null else {
                calls[call.id] = call
                sessions[callee.key].orEmpty().toList()
            }
        }
        if (ringing == null) return s.send(event("ended") { put("reason", "busy") })
        s.send(event("calling") { put("callId", call.id); put("name", callee.name); put("video", video) })
        ringing.forEach { it.send(ring(call)) }
        scope.launch {
            delay(ringMs)
            end(call.id, null, "no_answer", onlyWhileRinging = true)
        }
    }

    private fun accept(s: Session, callId: String) {
        val others = synchronized(lock) {
            val call = calls[callId]?.takeIf { it.answered == null && it.callee.key == s.who.key } ?: return
            call.answered = s
            call.caller.send(event("accepted") { put("callId", callId) })
            sessions[s.who.key].orEmpty().filter { it !== s }
        }
        others.forEach { it.send(event("ended") { put("callId", callId); put("reason", "answered_elsewhere") }) }
    }

    /** Ends a call (by [by], or by the server when [by] is null) and tells everyone still in it. */
    private suspend fun end(callId: String, by: Session?, reason: String, onlyWhileRinging: Boolean = false) {
        val (call, notify) = synchronized(lock) {
            val call = calls[callId] ?: return
            if (onlyWhileRinging && call.answered != null) return
            if (by != null && by !== call.caller && by !== call.answered && by.who.key != call.callee.key) return
            calls.remove(callId)
            val notify = if (call.answered != null) listOf(call.caller, call.answered!!) else listOf(call.caller) + sessions[call.callee.key].orEmpty()
            call to notify.filter { it !== by }
        }
        if (call.answered == null && reason in MISSED) missed(call)
        notify.forEach { it.send(event("ended") { put("callId", callId); put("reason", reason) }) }
    }

    /** Leaves a line in the conversation, so the other side sees the call they did not answer. */
    private suspend fun missed(call: Call) {
        val caller = call.caller.who
        val (doctorId, patientId) = if (caller.doctor) caller.id to call.callee.id else call.callee.id to caller.id
        val text = if (call.video) "📞 تماس تصویری بی‌پاسخ" else "📞 تماس صوتی بی‌پاسخ"
        runCatching { io { services.messages.addText(doctorId, patientId, caller.doctor, text) } }
            .onFailure { log.warn("Could not note a missed call", it) }
    }

    private suspend fun leave(s: Session) {
        val involved = synchronized(lock) {
            sessions[s.who.key]?.let { it -= s; if (it.isEmpty()) sessions.remove(s.who.key) }
            calls.values.filter { it.caller === s || it.answered === s }.map { it.id }
        }
        involved.forEach { end(it, s, "hangup") }
    }

    private fun ready(who: Caller) = event("ready") {
        putJsonObject("me") { put("doctor", who.doctor); put("id", who.id); put("name", who.name) }
        putJsonArray("iceServers") {
            if (turn != null && turnUrls.isNotEmpty()) {
                val login = turn.credentials(who.key)
                addJsonObject {
                    putJsonArray("urls") { turnUrls.forEach { add(JsonPrimitive(it)) } }
                    put("username", login.username)
                    put("credential", login.password)
                }
            }
        }
    }

    private fun ring(call: Call) = event("ring") {
        put("callId", call.id)
        put("video", call.video)
        putJsonObject("from") { put("doctor", call.caller.who.doctor); put("id", call.caller.who.id); put("name", call.caller.who.name) }
    }

    private fun event(type: String, fill: JsonObjectBuilder.() -> Unit) =
        buildJsonObject { put("type", type); fill() }

    private fun parse(text: String): JsonObject? =
        if (text.length > MAX_MESSAGE) null else runCatching { ApiJson.parseToJsonElement(text).jsonObject }.getOrNull()

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    companion object {
        private val MISSED = setOf("no_answer", "declined", "hangup")
        const val MAX_MESSAGE = 32 * 1024
    }
}
