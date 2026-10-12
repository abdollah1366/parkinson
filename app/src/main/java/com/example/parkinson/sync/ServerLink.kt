package com.example.parkinson.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject

/**
 * One person's connection to their doctor's server, made with the doctor's invite code. [token] lets this phone send
 * that person's results; it is never shown or logged.
 */
data class ServerLink(
    val serverUrl: String,
    val token: String,
    val doctorName: String,
    val linkedAtEpochMs: Long,
    /** The profile's updatedEpochMs when it was last sent; a later edit goes with the next sync. */
    val profileSentEpochMs: Long,
    val lastSyncEpochMs: Long? = null,
    /** Error code of the last failed sync ([ServerException.code]); null after a success. */
    val lastError: String? = null,
) {
    override fun toString(): String = "ServerLink(server=$serverUrl, lastSync=$lastSyncEpochMs, lastError=$lastError)"

    fun toJson(): JSONObject = JSONObject()
        .put("serverUrl", serverUrl)
        .put("token", token)
        .put("doctorName", doctorName)
        .put("linkedAtEpochMs", linkedAtEpochMs)
        .put("profileSentEpochMs", profileSentEpochMs)
        .put("lastSyncEpochMs", lastSyncEpochMs ?: JSONObject.NULL)
        .put("lastError", lastError ?: JSONObject.NULL)

    companion object {
        fun fromJson(json: JSONObject): ServerLink = ServerLink(
            serverUrl = json.getString("serverUrl"),
            token = json.getString("token"),
            doctorName = json.getString("doctorName"),
            linkedAtEpochMs = json.getLong("linkedAtEpochMs"),
            profileSentEpochMs = json.getLong("profileSentEpochMs"),
            lastSyncEpochMs = if (json.isNull("lastSyncEpochMs")) null else json.getLong("lastSyncEpochMs"),
            lastError = if (json.isNull("lastError")) null else json.getString("lastError"),
        )
    }
}

/** The links by person id, and which results of each person the server already has. */
interface ServerLinkStore {
    val links: StateFlow<Map<Int, ServerLink>>

    /** The server address typed last, offered again for the next person. */
    val lastServerUrl: String?

    fun save(profileId: Int, link: ServerLink)

    /** Forgets the link and the record of what was sent. */
    fun remove(profileId: Int)

    fun sentIds(profileId: Int): Set<String>

    fun markSent(profileId: Int, ids: Collection<String>)
}

class InMemoryServerLinkStore : ServerLinkStore {
    private val state = MutableStateFlow<Map<Int, ServerLink>>(emptyMap())
    private val sent = mutableMapOf<Int, Set<String>>()
    override val links: StateFlow<Map<Int, ServerLink>> = state.asStateFlow()
    override var lastServerUrl: String? = null
        private set

    override fun save(profileId: Int, link: ServerLink) {
        lastServerUrl = link.serverUrl
        state.update { it + (profileId to link) }
    }

    override fun remove(profileId: Int) {
        sent.remove(profileId)
        state.update { it - profileId }
    }

    @Synchronized
    override fun sentIds(profileId: Int): Set<String> = sent[profileId].orEmpty()

    @Synchronized
    override fun markSent(profileId: Int, ids: Collection<String>) {
        sent[profileId] = sentIds(profileId) + ids
    }
}

/** Kept in the app's private storage, like the profiles themselves. */
class PrefsServerLinkStore(context: Context) : ServerLinkStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    override val links: StateFlow<Map<Int, ServerLink>> = state.asStateFlow()

    override val lastServerUrl: String? get() = prefs.getString(KEY_LAST_URL, null)

    override fun save(profileId: Int, link: ServerLink) {
        prefs.edit().putString(LINK + profileId, link.toJson().toString()).putString(KEY_LAST_URL, link.serverUrl).apply()
        state.update { it + (profileId to link) }
    }

    override fun remove(profileId: Int) {
        prefs.edit().remove(LINK + profileId).remove(SENT + profileId).apply()
        state.update { it - profileId }
    }

    @Synchronized
    override fun sentIds(profileId: Int): Set<String> = prefs.getStringSet(SENT + profileId, null)?.toSet().orEmpty()

    @Synchronized
    override fun markSent(profileId: Int, ids: Collection<String>) {
        // A new set each time: SharedPreferences must not be handed back the set it returned.
        prefs.edit().putStringSet(SENT + profileId, HashSet(sentIds(profileId) + ids)).apply()
    }

    private fun load(): Map<Int, ServerLink> = prefs.all.mapNotNull { (key, value) ->
        val id = key.removePrefix(LINK).takeIf { key.startsWith(LINK) }?.toIntOrNull() ?: return@mapNotNull null
        runCatching { id to ServerLink.fromJson(JSONObject(value as String)) }.getOrNull()
    }.toMap()

    private companion object {
        const val PREFS = "server_links"
        const val LINK = "link_"
        const val SENT = "sent_"
        const val KEY_LAST_URL = "last_server_url"
    }
}
