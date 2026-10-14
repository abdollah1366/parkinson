package com.example.parkinson.plan

import android.content.Context
import com.example.parkinson.biofeedback.BioSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject

/** One person's plan as last read from their doctor's server, so reminders work offline. */
data class SavedPlan(val personName: String, val prescriptions: List<Prescription>)

/**
 * The plans of the people on this phone, what each marked (taken, missed, done) and the marks the server does not have
 * yet. [version] changes on every write, so a screen or a notification sees the same state.
 */
interface PlanStore {
    val version: StateFlow<Int>

    /** People with a plan, by profile id. */
    fun plans(): Map<Int, SavedPlan>

    fun savePlan(profileId: Int, plan: SavedPlan)

    /** Forgets the person's plan, marks and unsent marks (they are no longer connected). */
    fun remove(profileId: Int)

    /** The person's marks by [Intake.key]. */
    fun marks(profileId: Int): Map<String, Intake>

    /** Records [intake] (replacing an earlier mark of the same dose) and queues it for the server. */
    fun mark(profileId: Int, intake: Intake)

    fun pending(profileId: Int): List<Intake>

    /** The server has [sent]: drop them from the queue (unless marked again since). */
    fun sent(profileId: Int, sent: List<Intake>)

    /** Keeps a biofeedback session (the recent ones are shown again) and queues it for the server. */
    fun addSession(profileId: Int, session: BioSession)

    /** The person's sessions of the last weeks, newest first. */
    fun sessions(profileId: Int): List<BioSession>

    fun pendingSessions(profileId: Int): List<BioSession>

    fun sessionsSent(profileId: Int, sent: List<BioSession>)
}

/** Marks older than this are dropped from the phone; the server keeps them. */
private const val KEEP_MS = 40 * 24 * 60 * 60 * 1000L

class InMemoryPlanStore(private val clock: () -> Long = System::currentTimeMillis) : PlanStore {
    private val _version = MutableStateFlow(0)
    override val version: StateFlow<Int> = _version.asStateFlow()
    private val plans = mutableMapOf<Int, SavedPlan>()
    private val marks = mutableMapOf<Int, Map<String, Intake>>()
    private val pending = mutableMapOf<Int, Map<String, Intake>>()
    private val sessions = mutableMapOf<Int, List<BioSession>>()
    private val pendingSessions = mutableMapOf<Int, Map<String, BioSession>>()

    @Synchronized override fun plans(): Map<Int, SavedPlan> = plans.toMap()

    @Synchronized override fun savePlan(profileId: Int, plan: SavedPlan) {
        plans[profileId] = plan
        _version.update { it + 1 }
    }

    @Synchronized override fun remove(profileId: Int) {
        plans.remove(profileId); marks.remove(profileId); pending.remove(profileId)
        sessions.remove(profileId); pendingSessions.remove(profileId)
        _version.update { it + 1 }
    }

    @Synchronized override fun marks(profileId: Int): Map<String, Intake> = marks[profileId].orEmpty()

    @Synchronized override fun mark(profileId: Int, intake: Intake) {
        val old = clock() - KEEP_MS
        marks[profileId] = (marks(profileId) + (intake.key to intake)).filterValues { it.scheduledAtEpochMs >= old }
        pending[profileId] = pending[profileId].orEmpty() + (intake.key to intake)
        _version.update { it + 1 }
    }

    @Synchronized override fun pending(profileId: Int): List<Intake> = pending[profileId].orEmpty().values.toList()

    @Synchronized override fun sent(profileId: Int, sent: List<Intake>) {
        pending[profileId] = pending[profileId].orEmpty().filter { (key, i) -> sent.none { it.key == key && it == i } }
        _version.update { it + 1 }
    }

    @Synchronized override fun addSession(profileId: Int, session: BioSession) {
        val old = clock() - KEEP_MS
        sessions[profileId] = (listOf(session) + sessions(profileId).filter { it.key != session.key })
            .filter { it.startedAtEpochMs >= old }.sortedByDescending { it.startedAtEpochMs }
        pendingSessions[profileId] = pendingSessions[profileId].orEmpty() + (session.key to session)
        _version.update { it + 1 }
    }

    @Synchronized override fun sessions(profileId: Int): List<BioSession> = sessions[profileId].orEmpty()

    @Synchronized override fun pendingSessions(profileId: Int): List<BioSession> = pendingSessions[profileId].orEmpty().values.toList()

    @Synchronized override fun sessionsSent(profileId: Int, sent: List<BioSession>) {
        pendingSessions[profileId] = pendingSessions[profileId].orEmpty().filter { (_, s) -> s !in sent }
        _version.update { it + 1 }
    }
}

/** Kept in the app's private storage, next to the people and their links. */
class PrefsPlanStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) : PlanStore {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val _version = MutableStateFlow(0)
    override val version: StateFlow<Int> = _version.asStateFlow()

    @Synchronized override fun plans(): Map<Int, SavedPlan> = prefs.all.mapNotNull { (key, value) ->
        val id = key.removePrefix(PLAN).takeIf { key.startsWith(PLAN) }?.toIntOrNull() ?: return@mapNotNull null
        runCatching {
            val json = JSONObject(value as String)
            id to SavedPlan(json.getString("name"), PlanJson.prescriptions(json.getJSONArray("items")))
        }.getOrNull()
    }.toMap()

    @Synchronized override fun savePlan(profileId: Int, plan: SavedPlan) {
        val json = JSONObject().put("name", plan.personName).put("items", PlanJson.toJson(plan.prescriptions))
        prefs.edit().putString(PLAN + profileId, json.toString()).apply()
        _version.update { it + 1 }
    }

    @Synchronized override fun remove(profileId: Int) {
        prefs.edit().remove(PLAN + profileId).remove(MARKS + profileId).remove(PENDING + profileId)
            .remove(SESSIONS + profileId).remove(PENDING_SESSIONS + profileId).apply()
        _version.update { it + 1 }
    }

    @Synchronized override fun marks(profileId: Int): Map<String, Intake> = read(MARKS + profileId)

    @Synchronized override fun mark(profileId: Int, intake: Intake) {
        val old = clock() - KEEP_MS
        val marks = (marks(profileId) + (intake.key to intake)).filterValues { it.scheduledAtEpochMs >= old }
        val pending = read(PENDING + profileId) + (intake.key to intake)
        prefs.edit().putString(MARKS + profileId, write(marks)).putString(PENDING + profileId, write(pending)).apply()
        _version.update { it + 1 }
    }

    @Synchronized override fun pending(profileId: Int): List<Intake> = read(PENDING + profileId).values.toList()

    @Synchronized override fun sent(profileId: Int, sent: List<Intake>) {
        val left = read(PENDING + profileId).filter { (key, i) -> sent.none { it.key == key && it == i } }
        prefs.edit().putString(PENDING + profileId, write(left)).apply()
        _version.update { it + 1 }
    }

    @Synchronized override fun addSession(profileId: Int, session: BioSession) {
        val old = clock() - KEEP_MS
        val all = (listOf(session) + sessions(profileId).filter { it.key != session.key })
            .filter { it.startedAtEpochMs >= old }.sortedByDescending { it.startedAtEpochMs }
        val pending = pendingSessions(profileId).filter { it.key != session.key } + session
        prefs.edit().putString(SESSIONS + profileId, PlanJson.sessions(all).toString())
            .putString(PENDING_SESSIONS + profileId, PlanJson.sessions(pending).toString()).apply()
        _version.update { it + 1 }
    }

    @Synchronized override fun sessions(profileId: Int): List<BioSession> = readSessions(SESSIONS + profileId)

    @Synchronized override fun pendingSessions(profileId: Int): List<BioSession> = readSessions(PENDING_SESSIONS + profileId)

    @Synchronized override fun sessionsSent(profileId: Int, sent: List<BioSession>) {
        val left = pendingSessions(profileId).filter { it !in sent }
        prefs.edit().putString(PENDING_SESSIONS + profileId, PlanJson.sessions(left).toString()).apply()
        _version.update { it + 1 }
    }

    private fun readSessions(key: String): List<BioSession> =
        runCatching { PlanJson.parseSessions(JSONArray(prefs.getString(key, "[]"))) }.getOrDefault(emptyList())

    private fun read(key: String): Map<String, Intake> = runCatching {
        PlanJson.parseIntakes(JSONArray(prefs.getString(key, "[]"))).associateBy { it.key }
    }.getOrDefault(emptyMap())

    private fun write(intakes: Map<String, Intake>): String = PlanJson.intakes(intakes.values.toList()).toString()

    private companion object {
        const val PREFS = "treatment_plan"
        const val PLAN = "plan_"
        const val MARKS = "marks_"
        const val PENDING = "pending_"
        const val SESSIONS = "sessions_"
        const val PENDING_SESSIONS = "pending_sessions_"
    }
}
