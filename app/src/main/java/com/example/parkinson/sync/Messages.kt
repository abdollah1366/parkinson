package com.example.parkinson.sync

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** One message between the person and their doctor, as the server keeps it. */
data class ChatMessage(
    val id: Long,
    val fromDoctor: Boolean,
    /** Null for a voice message. */
    val text: String?,
    /** Length of a voice message; its sound is fetched with [MessagesApi.audio] when played. */
    val audioMs: Int?,
    val createdAtEpochMs: Long,
    /** When the other side read it. */
    val readAtEpochMs: Long?,
)

/** One of the person's doctors on the server, with what is unread from them. */
data class Conversation(val doctorId: Long, val doctorName: String, val unread: Int, val lastMessageEpochMs: Long?)

/** A call from a doctor ringing for the person ([video] or voice only). */
data class IncomingCall(val callId: String, val doctorId: Long, val doctorName: String, val video: Boolean)

/** Messages with the doctors of one linked person, with that person's phone token. */
interface MessagesApi {
    suspend fun conversations(serverUrl: String, token: String): List<Conversation>

    /** Messages after [afterId], oldest first (the latest ones when [afterId] is 0). */
    suspend fun messages(serverUrl: String, token: String, doctorId: Long, afterId: Long = 0): List<ChatMessage>

    /** Marks what the doctor sent as read. */
    suspend fun markRead(serverUrl: String, token: String, doctorId: Long)

    suspend fun sendText(serverUrl: String, token: String, doctorId: Long, text: String): ChatMessage

    /** Sends a recorded voice message ([audio] is an .m4a file of [durationMs]). */
    suspend fun sendVoice(serverUrl: String, token: String, doctorId: Long, audio: ByteArray, durationMs: Int): ChatMessage

    suspend fun audio(serverUrl: String, token: String, doctorId: Long, messageId: Long): ByteArray

    /** Calls ringing for the person now. */
    suspend fun ringing(serverUrl: String, token: String): List<IncomingCall>

    suspend fun declineCall(serverUrl: String, token: String, callId: String)
}

class HttpMessagesApi : MessagesApi {

    override suspend fun conversations(serverUrl: String, token: String): List<Conversation> =
        parse { MessagesJson.conversations(JSONArray(get("$serverUrl/api/device/conversations", token))) }

    override suspend fun messages(serverUrl: String, token: String, doctorId: Long, afterId: Long): List<ChatMessage> =
        parse { MessagesJson.messages(JSONArray(get(base(serverUrl, doctorId) + "/messages?after=$afterId", token))) }

    override suspend fun markRead(serverUrl: String, token: String, doctorId: Long) {
        Http.request("POST", base(serverUrl, doctorId) + "/messages/read", token)
    }

    override suspend fun sendText(serverUrl: String, token: String, doctorId: Long, text: String): ChatMessage {
        val body = JSONObject().put("text", text).toString().toByteArray()
        val response = Http.request("POST", base(serverUrl, doctorId) + "/messages", token, Http.JSON to body)
        return parse { MessagesJson.message(JSONObject(String(response))) }
    }

    override suspend fun sendVoice(
        serverUrl: String, token: String, doctorId: Long, audio: ByteArray, durationMs: Int,
    ): ChatMessage {
        val url = base(serverUrl, doctorId) + "/messages/voice?ms=$durationMs"
        val response = Http.request("POST", url, token, "audio/mp4" to audio)
        return parse { MessagesJson.message(JSONObject(String(response))) }
    }

    override suspend fun audio(serverUrl: String, token: String, doctorId: Long, messageId: Long): ByteArray =
        Http.request("GET", base(serverUrl, doctorId) + "/messages/$messageId/audio", token)

    override suspend fun ringing(serverUrl: String, token: String): List<IncomingCall> =
        parse { MessagesJson.calls(JSONArray(get("$serverUrl/api/device/calls", token))) }

    override suspend fun declineCall(serverUrl: String, token: String, callId: String) {
        Http.request("POST", "$serverUrl/api/device/calls/${java.net.URLEncoder.encode(callId, "UTF-8")}/decline", token)
    }

    private fun base(serverUrl: String, doctorId: Long) = "$serverUrl/api/device/doctors/$doctorId"

    private suspend fun get(url: String, token: String) = String(Http.request("GET", url, token))

    private inline fun <T> parse(block: () -> T): T = try {
        block()
    } catch (e: JSONException) {
        throw ServerException("bad_response")
    }
}

/** The server's JSON for messages (server/.../Messages.kt). */
object MessagesJson {
    fun message(json: JSONObject) = ChatMessage(
        id = json.getLong("id"),
        fromDoctor = json.getBoolean("fromDoctor"),
        text = json.optStringOrNull("text"),
        audioMs = if (json.isNull("audioMs")) null else json.getInt("audioMs"),
        createdAtEpochMs = json.getLong("createdAtEpochMs"),
        readAtEpochMs = if (json.isNull("readAtEpochMs")) null else json.getLong("readAtEpochMs"),
    )

    fun messages(array: JSONArray): List<ChatMessage> = List(array.length()) { message(array.getJSONObject(it)) }

    fun conversations(array: JSONArray): List<Conversation> = List(array.length()) {
        val json = array.getJSONObject(it)
        Conversation(
            doctorId = json.getLong("doctorId"),
            doctorName = json.getString("doctorName"),
            unread = json.optInt("unread"),
            lastMessageEpochMs = if (json.isNull("lastMessageEpochMs")) null else json.getLong("lastMessageEpochMs"),
        )
    }

    fun calls(array: JSONArray): List<IncomingCall> = List(array.length()) {
        val json = array.getJSONObject(it)
        IncomingCall(json.getString("callId"), json.getLong("doctorId"), json.getString("doctorName"), json.optBoolean("video"))
    }

    // isNull is also true for a missing key: the server leaves out fields that are null.
    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
}

/** Passes every call to [target], so tests can swap the server after the screens that use it were created. */
class SwitchableMessagesApi(var target: MessagesApi) : MessagesApi {
    override suspend fun conversations(serverUrl: String, token: String) = target.conversations(serverUrl, token)
    override suspend fun messages(serverUrl: String, token: String, doctorId: Long, afterId: Long) =
        target.messages(serverUrl, token, doctorId, afterId)
    override suspend fun markRead(serverUrl: String, token: String, doctorId: Long) = target.markRead(serverUrl, token, doctorId)
    override suspend fun sendText(serverUrl: String, token: String, doctorId: Long, text: String) =
        target.sendText(serverUrl, token, doctorId, text)
    override suspend fun sendVoice(serverUrl: String, token: String, doctorId: Long, audio: ByteArray, durationMs: Int) =
        target.sendVoice(serverUrl, token, doctorId, audio, durationMs)
    override suspend fun audio(serverUrl: String, token: String, doctorId: Long, messageId: Long) =
        target.audio(serverUrl, token, doctorId, messageId)
    override suspend fun ringing(serverUrl: String, token: String) = target.ringing(serverUrl, token)
    override suspend fun declineCall(serverUrl: String, token: String, callId: String) = target.declineCall(serverUrl, token, callId)
}
