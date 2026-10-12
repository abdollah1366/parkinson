package com.example.parkinson.sync

import com.example.parkinson.data.ResultRow
import com.example.parkinson.data.StoredResult
import com.example.parkinson.profile.UserProfile
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Sends each linked person's profile and results to their doctor's server. Nothing leaves the phone for a person
 * who has not been linked with an invite code. A result is sent once; one the server did not confirm is sent again
 * next time (the server keeps one copy).
 */
class ResultSync(
    private val api: ServerApi,
    private val store: ServerLinkStore,
    /** The stored rows of one person, by assessmentId (see [com.example.parkinson.data.ResultRowReader]). */
    private val rowsOf: suspend (profileId: Int) -> Map<String, ResultRow>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val mutex = Mutex()
    private val _running = MutableStateFlow<Set<Int>>(emptySet())

    /** People whose results are being sent right now. */
    val running: StateFlow<Set<Int>> = _running.asStateFlow()

    val links: StateFlow<Map<Int, ServerLink>> get() = store.links

    val lastServerUrl: String? get() = store.lastServerUrl

    /** Connects [profile] to the doctor who gave [inviteCode]. Throws [ServerException] when refused. */
    suspend fun link(serverUrl: String, inviteCode: String, profile: UserProfile): ServerLink {
        val result = api.link(serverUrl, inviteCode, SyncJson.patient(profile))
        val link = ServerLink(
            serverUrl = serverUrl,
            token = result.token,
            doctorName = result.doctorName,
            linkedAtEpochMs = clock(),
            profileSentEpochMs = profile.updatedEpochMs,
        )
        mutex.withLock {
            // A new link for the same person: what the old server had says nothing about this one.
            store.remove(profile.id)
            store.save(profile.id, link)
        }
        return link
    }

    /** How many of [history] (one person's results) the server does not have yet. */
    fun pendingCount(profileId: Int, history: List<StoredResult>): Int {
        val sent = store.sentIds(profileId)
        return history.count { it.result.assessmentId !in sent }
    }

    /**
     * Sends what the server of [profile] does not have yet from [history]: a newer profile edit and unsent results.
     * Returns how many results were sent; 0 when the person is not linked. A failure is recorded on the link
     * ([ServerLink.lastError]) and thrown.
     */
    suspend fun sync(profile: UserProfile, history: List<StoredResult>): Int = mutex.withLock {
        val link = store.links.value[profile.id] ?: return@withLock 0
        _running.update { it + profile.id }
        try {
            var current = link
            if (profile.updatedEpochMs > link.profileSentEpochMs) {
                api.updateProfile(link.serverUrl, link.token, SyncJson.patient(profile))
                current = current.copy(profileSentEpochMs = profile.updatedEpochMs)
                store.save(profile.id, current)
            }
            val sent = store.sentIds(profile.id)
            val waiting = history.filter { it.result.assessmentId !in sent }
            var count = 0
            if (waiting.isNotEmpty()) {
                // Only results that are stored under this person: never send someone else's by mistake.
                val rows = rowsOf(profile.id)
                val payloads = waiting.mapNotNull { stored -> rows[stored.result.assessmentId]?.let { SyncJson.result(stored, it) } }
                for (batch in batches(payloads)) {
                    val stored = api.upload(link.serverUrl, link.token, batch)
                    store.markSent(profile.id, stored)
                    count += stored.size
                }
            }
            store.save(profile.id, current.copy(lastSyncEpochMs = clock(), lastError = null))
            count
        } catch (e: ServerException) {
            store.links.value[profile.id]?.let { store.save(profile.id, it.copy(lastError = e.code)) }
            throw e
        } finally {
            _running.update { it - profile.id }
        }
    }

    /** Disconnects [profileId] from the doctor's server: tells the server (if it can be reached) and forgets the link. */
    suspend fun unlink(profileId: Int) {
        val link = store.links.value[profileId] ?: return
        // Best effort: a phone offline or a server gone must not keep a person from disconnecting.
        runCatching { withTimeoutOrNull(5_000) { api.revoke(link.serverUrl, link.token) } }
        mutex.withLock { store.remove(profileId) }
    }

    private fun batches(payloads: List<JSONObject>): List<List<JSONObject>> {
        val out = mutableListOf<List<JSONObject>>()
        var batch = mutableListOf<JSONObject>()
        var size = 0
        for (p in payloads) {
            val length = p.toString().length
            if (batch.isNotEmpty() && (batch.size >= MAX_BATCH || size + length > MAX_BATCH_CHARS)) {
                out += batch
                batch = mutableListOf()
                size = 0
            }
            batch += p
            size += length
        }
        if (batch.isNotEmpty()) out += batch
        return out
    }

    private companion object {
        const val MAX_BATCH = 20

        /** Well under the server's 8 MB request limit, also on slow mobile connections. */
        const val MAX_BATCH_CHARS = 1_000_000
    }
}
