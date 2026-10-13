package com.example.parkinson.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray
import kotlinx.serialization.Serializable
import java.sql.ResultSet
import java.time.Instant

// Stage 4: text and voice messages between a doctor and each linked patient. A conversation is one (doctor, patient)
// pair; the patient writes from the app (device token) or from their web panel.

@Serializable
data class MessageIn(val text: String)

@Serializable
data class MessageDto(
    val id: Long,
    val fromDoctor: Boolean,
    val text: String? = null,
    /** Length of a voice message; null for text. Its sound is fetched from the matching .../audio address. */
    val audioMs: Int? = null,
    val createdAtEpochMs: Long,
    /** When the other side opened the conversation after this message arrived. */
    val readAtEpochMs: Long? = null,
)

/** One of the patient's doctors, as the patient's conversation list shows it. */
@Serializable
data class ConversationDto(
    val doctorId: Long,
    val doctorName: String,
    val unread: Int,
    val lastMessageEpochMs: Long? = null,
)

object Voice {
    /** About 3 minutes of speech in AAC or Opus at the bitrates phones and browsers record with. */
    const val MAX_BYTES = 2 * 1024 * 1024
    const val MAX_MS = 3 * 60 * 1000

    /** The sound format from the file's first bytes (never trust the sender's header); null when it is not one. */
    fun typeOf(bytes: ByteArray): String? {
        fun at(i: Int) = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        fun ascii(from: Int, text: String) = text.indices.all { at(from + it) == text[it].code }
        return when {
            ascii(4, "ftyp") -> "audio/mp4"
            at(0) == 0x1A && at(1) == 0x45 && at(2) == 0xDF && at(3) == 0xA3 -> "audio/webm"
            ascii(0, "OggS") -> "audio/ogg"
            ascii(0, "ID3") || (at(0) == 0xFF && at(1) and 0xE0 == 0xE0 && at(1) and 0x06 != 0) -> "audio/mpeg"
            at(0) == 0xFF && at(1) and 0xF6 == 0xF0 -> "audio/aac"
            else -> null
        }
    }
}

class MessageStore(private val db: Db) {
    /** Messages of one conversation with an id above [afterId], oldest first; the newest [limit] when [afterId] is 0. */
    fun list(doctorId: Long, patientId: Long, afterId: Long = 0, limit: Int = 200): List<MessageDto> = db.tx {
        query(
            """
            SELECT * FROM (
                SELECT $COLUMNS FROM messages WHERE doctor_id = ? AND patient_id = ? AND id > ? ORDER BY id DESC LIMIT ?
            ) m ORDER BY id
            """.trimIndent(),
            doctorId, patientId, afterId, limit,
        ) { it.toMessage() }
    }

    fun addText(doctorId: Long, patientId: Long, fromDoctor: Boolean, text: String, now: Instant = Instant.now()): MessageDto =
        db.tx {
            single(
                "INSERT INTO messages (doctor_id, patient_id, from_doctor, body, created_at) VALUES (?, ?, ?, ?, ?) RETURNING $COLUMNS",
                doctorId, patientId, fromDoctor, text, now,
            ) { it.toMessage() }!!
        }

    fun addVoice(
        doctorId: Long, patientId: Long, fromDoctor: Boolean, audio: ByteArray, type: String, ms: Int,
        now: Instant = Instant.now(),
    ): MessageDto = db.tx {
        single(
            """
            INSERT INTO messages (doctor_id, patient_id, from_doctor, audio, audio_type, audio_ms, created_at)
            VALUES (?, ?, ?, ?, ?, ?, ?) RETURNING $COLUMNS
            """.trimIndent(),
            doctorId, patientId, fromDoctor, audio, type, ms, now,
        ) { it.toMessage() }!!
    }

    /** Marks what the other side sent as read by this side. */
    fun markRead(doctorId: Long, patientId: Long, byDoctor: Boolean, now: Instant = Instant.now()) {
        db.tx {
            update(
                "UPDATE messages SET read_at = ? WHERE doctor_id = ? AND patient_id = ? AND from_doctor = ? AND read_at IS NULL",
                now, doctorId, patientId, !byDoctor,
            )
        }
    }

    /** The sound of a voice message in the conversation, with its type; null when there is none. */
    fun audio(doctorId: Long, patientId: Long, messageId: Long): Pair<ByteArray, String>? = db.tx {
        single(
            "SELECT audio, audio_type FROM messages WHERE id = ? AND doctor_id = ? AND patient_id = ? AND audio IS NOT NULL",
            messageId, doctorId, patientId,
        ) { it.getBytes("audio") to it.getString("audio_type") }
    }

    /** Unread messages from each of the doctor's patients. */
    fun unreadByPatient(doctorId: Long): Map<Long, Int> = db.tx {
        query(
            "SELECT patient_id, count(*) AS n FROM messages WHERE doctor_id = ? AND NOT from_doctor AND read_at IS NULL GROUP BY patient_id",
            doctorId,
        ) { it.getLong("patient_id") to it.getInt("n") }
    }.toMap()

    fun conversationsOf(patientId: Long): List<ConversationDto> = db.tx {
        query(
            """
            SELECT u.id, u.full_name,
                (SELECT count(*) FROM messages m WHERE m.doctor_id = u.id AND m.patient_id = dp.patient_id
                    AND m.from_doctor AND m.read_at IS NULL) AS unread,
                (SELECT max(m.created_at) FROM messages m WHERE m.doctor_id = u.id AND m.patient_id = dp.patient_id) AS last_at
            FROM doctor_patients dp JOIN users u ON u.id = dp.doctor_id
            WHERE dp.patient_id = ? ORDER BY dp.linked_at
            """.trimIndent(),
            patientId,
        ) { ConversationDto(it.getLong("id"), it.getString("full_name"), it.getInt("unread"), it.epochMs("last_at")) }
    }

    private fun ResultSet.toMessage() = MessageDto(
        id = getLong("id"),
        fromDoctor = getBoolean("from_doctor"),
        text = getString("body"),
        audioMs = intOrNull("audio_ms"),
        createdAtEpochMs = epochMs("created_at")!!,
        readAtEpochMs = epochMs("read_at"),
    )

    private companion object {
        // Never the audio itself: lists stay small and the sound is fetched only when played.
        const val COLUMNS = "id, from_doctor, body, audio_ms, created_at, read_at"
    }
}

/**
 * The routes of one conversation under the current route, shared by the doctor and the patient sides.
 * [conversation] names the (doctor, patient) pair from the request and must refuse pairs that are not linked;
 * [fromDoctor] says which side is writing.
 */
fun Route.conversationRoutes(
    services: Services,
    fromDoctor: Boolean,
    conversation: suspend ApplicationCall.() -> Pair<Long, Long>,
) {
    get("/messages") {
        val (doctorId, patientId) = call.conversation()
        val after = call.request.queryParameters["after"]?.toLongOrNull() ?: 0
        call.respond(io { services.messages.list(doctorId, patientId, after) })
    }
    post("/messages/read") {
        val (doctorId, patientId) = call.conversation()
        io { services.messages.markRead(doctorId, patientId, byDoctor = fromDoctor) }
        call.respond(HttpStatusCode.NoContent)
    }
    get("/messages/{messageId}/audio") {
        val (doctorId, patientId) = call.conversation()
        val messageId = call.parameters["messageId"]?.toLongOrNull() ?: notFound()
        val (bytes, type) = io { services.messages.audio(doctorId, patientId, messageId) } ?: notFound()
        call.response.header(HttpHeaders.CacheControl, "private, max-age=86400")
        call.respondBytes(bytes, ContentType.parse(type))
    }
    rateLimit(MESSAGE_LIMIT) {
        post("/messages") {
            val (doctorId, patientId) = call.conversation()
            val text = call.receive<MessageIn>().text.trim().takeIf { it.length in 1..MAX_TEXT } ?: badRequest("invalid_message")
            call.respond(HttpStatusCode.Created, io { services.messages.addText(doctorId, patientId, fromDoctor, text) })
        }
        post("/messages/voice") {
            val (doctorId, patientId) = call.conversation()
            val ms = call.request.queryParameters["ms"]?.toIntOrNull()?.takeIf { it in 1..Voice.MAX_MS } ?: badRequest("invalid_voice")
            if ((call.request.contentLength() ?: 0) > Voice.MAX_BYTES) throw ApiException(413, "too_large")
            val bytes = call.receiveChannel().readRemaining(Voice.MAX_BYTES + 1L).readByteArray()
            if (bytes.size > Voice.MAX_BYTES) throw ApiException(413, "too_large")
            val type = Voice.typeOf(bytes) ?: badRequest("invalid_voice")
            call.respond(HttpStatusCode.Created, io { services.messages.addVoice(doctorId, patientId, fromDoctor, bytes, type, ms) })
        }
    }
}

private const val MAX_TEXT = 4000
