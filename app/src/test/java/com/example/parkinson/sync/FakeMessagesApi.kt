package com.example.parkinson.sync

/** A doctor's server with one conversation per doctor, kept in memory. */
class FakeMessagesApi(private val doctors: Map<Long, String> = mapOf(7L to "دکتر آزمایشی")) : MessagesApi {
    val messages = mutableMapOf<Long, MutableList<ChatMessage>>()
    val voices = mutableMapOf<Long, ByteArray>()
    val readBy = mutableListOf<Long>()
    var failWith: String? = null
    val rings = mutableListOf<IncomingCall>()
    val declined = mutableListOf<String>()
    private var nextId = 1L

    private fun maybeFail() {
        failWith?.let { throw ServerException(it, if (it == ServerException.NETWORK) 0 else 400) }
    }

    /** A message from doctor [doctorId], as if written on the panel. */
    fun fromDoctor(doctorId: Long, text: String) = add(doctorId, fromDoctor = true, text = text, audioMs = null)

    private fun add(doctorId: Long, fromDoctor: Boolean, text: String?, audioMs: Int?): ChatMessage {
        val m = ChatMessage(nextId++, fromDoctor, text, audioMs, 1_760_000_000_000L + nextId, null)
        messages.getOrPut(doctorId) { mutableListOf() } += m
        return m
    }

    override suspend fun conversations(serverUrl: String, token: String): List<Conversation> {
        maybeFail()
        return doctors.map { (id, name) ->
            val list = messages[id].orEmpty()
            Conversation(id, name, list.count { it.fromDoctor && it.readAtEpochMs == null }, list.lastOrNull()?.createdAtEpochMs)
        }
    }

    override suspend fun messages(serverUrl: String, token: String, doctorId: Long, afterId: Long): List<ChatMessage> {
        maybeFail()
        return messages[doctorId].orEmpty().filter { it.id > afterId }
    }

    override suspend fun markRead(serverUrl: String, token: String, doctorId: Long) {
        maybeFail()
        readBy += doctorId
        messages[doctorId]?.replaceAll { if (it.fromDoctor && it.readAtEpochMs == null) it.copy(readAtEpochMs = 1L) else it }
    }

    override suspend fun sendText(serverUrl: String, token: String, doctorId: Long, text: String): ChatMessage {
        maybeFail()
        return add(doctorId, fromDoctor = false, text = text, audioMs = null)
    }

    override suspend fun sendVoice(serverUrl: String, token: String, doctorId: Long, audio: ByteArray, durationMs: Int): ChatMessage {
        maybeFail()
        return add(doctorId, fromDoctor = false, text = null, audioMs = durationMs).also { voices[it.id] = audio }
    }

    override suspend fun audio(serverUrl: String, token: String, doctorId: Long, messageId: Long): ByteArray {
        maybeFail()
        return voices[messageId] ?: throw ServerException("not_found", 404)
    }

    override suspend fun ringing(serverUrl: String, token: String): List<IncomingCall> {
        maybeFail()
        return rings.toList()
    }

    override suspend fun declineCall(serverUrl: String, token: String, callId: String) {
        maybeFail()
        declined += callId
        rings.removeAll { it.callId == callId }
    }
}
