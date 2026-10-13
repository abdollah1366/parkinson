package com.example.parkinson.plan

import com.example.parkinson.sync.ServerException
import com.example.parkinson.sync.ServerLink
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Keeps each connected person's plan from their doctor's server on the phone and sends back what they marked. Marks
 * are kept on the phone first, so a dose marked offline (or from a notification) reaches the doctor later.
 */
class PlanSync(
    private val api: PlanApi,
    val store: PlanStore,
    private val links: StateFlow<Map<Int, ServerLink>>,
    /** Called after the plans changed, to set the next reminder. */
    private val onPlansChanged: () -> Unit = {},
) {
    private val mutex = Mutex()

    /**
     * Reads the plan of [profileId] again and sends unsent marks. Throws [ServerException] when the server cannot be
     * reached (the saved plan stays); a link the server refused (disconnected by the doctor) drops the plan.
     */
    suspend fun refresh(profileId: Int, personName: String) {
        val link = links.value[profileId]
        if (link == null) {
            forget(profileId)
            return
        }
        try {
            flush(profileId, link)
            val plan = api.prescriptions(link.serverUrl, link.token)
            mutex.withLock { if (links.value[profileId] != null) store.savePlan(profileId, SavedPlan(personName, plan)) }
        } catch (e: ServerException) {
            if (e.code == ServerException.UNAUTHORIZED || e.httpStatus == 401) forget(profileId)
            throw e
        }
        onPlansChanged()
    }

    /** Drops plans of people who are no longer connected (or no longer on the phone). */
    fun prune() {
        val stale = store.plans().keys - links.value.keys
        stale.forEach { store.remove(it) }
        if (stale.isNotEmpty()) onPlansChanged()
    }

    /** Records a mark on the phone, then tries to send it; false when it is waiting to be sent. */
    suspend fun mark(profileId: Int, intake: Intake): Boolean {
        store.mark(profileId, intake)
        val link = links.value[profileId] ?: return false
        return try {
            flush(profileId, link)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    /** Sends the person's unsent marks. */
    suspend fun flush(profileId: Int, link: ServerLink? = links.value[profileId]) {
        if (link == null) return
        val pending = store.pending(profileId)
        if (pending.isEmpty()) return
        try {
            pending.chunked(MAX_BATCH).forEach { batch ->
                api.record(link.serverUrl, link.token, batch)
                store.sent(profileId, batch)
            }
        } catch (e: ServerException) {
            // A dose the doctor has since stopped (or a mark the server cannot take) would block the rest for good.
            if (e.httpStatus == 400) store.sent(profileId, pending) else throw e
        }
    }

    private fun forget(profileId: Int) {
        if (profileId in store.plans() || store.pending(profileId).isNotEmpty()) {
            store.remove(profileId)
            onPlansChanged()
        }
    }

    companion object {
        const val MAX_BATCH = 200
    }
}
