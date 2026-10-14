package com.example.parkinson.plan

import com.example.parkinson.sync.Http
import com.example.parkinson.biofeedback.BioSession
import com.example.parkinson.sync.ServerException
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * What the doctor prescribed: a medication taken at set times of day, an exercise done so many times a week, or a
 * biofeedback exercise of the app's library (so many times a week, done in the app with live feedback).
 */
enum class PlanKind { MEDICATION, EXERCISE, BIOFEEDBACK }

/** One prescription from the doctor's server (server/.../Prescriptions.kt). */
data class Prescription(
    val id: Long,
    val doctorName: String,
    val kind: PlanKind,
    val name: String,
    val dose: String?,
    /** HH:mm, sorted; medications only. */
    val times: List<String>,
    /** Exercises only. */
    val perWeek: Int?,
    val instructions: String?,
    /** yyyy-MM-dd. */
    val startDate: String,
    val endDate: String?,
    val createdAtEpochMs: Long,
    /** Biofeedback only: the exercise's code ([com.example.parkinson.biofeedback.BioExercise]) and level 1-3. */
    val exercise: String? = null,
    val level: Int? = null,
) {
    /** Done a number of times a week (not at set times). */
    val weekly: Boolean get() = kind != PlanKind.MEDICATION
}

/** A dose taken or missed, or an exercise done. */
enum class IntakeStatus { TAKEN, MISSED, DONE }

/** One record for the server: [scheduledAtEpochMs] is the dose time (for an exercise, when it was done). */
data class Intake(val prescriptionId: Long, val scheduledAtEpochMs: Long, val status: IntakeStatus) {
    val key: String get() = "$prescriptionId@$scheduledAtEpochMs"
}

/** The person's active prescriptions and their records, with that person's phone token. */
interface PlanApi {
    suspend fun prescriptions(serverUrl: String, token: String): List<Prescription>

    suspend fun record(serverUrl: String, token: String, intakes: List<Intake>)

    /** Sends biofeedback sessions with their scores. */
    suspend fun sendSessions(serverUrl: String, token: String, sessions: List<BioSession>)
}

class HttpPlanApi : PlanApi {
    override suspend fun prescriptions(serverUrl: String, token: String): List<Prescription> {
        val body = String(Http.request("GET", "$serverUrl/api/device/prescriptions", token))
        return try {
            PlanJson.prescriptions(JSONArray(body))
        } catch (e: JSONException) {
            throw ServerException("bad_response")
        }
    }

    override suspend fun record(serverUrl: String, token: String, intakes: List<Intake>) {
        val body = JSONObject().put("intakes", PlanJson.intakes(intakes)).toString().toByteArray()
        Http.request("POST", "$serverUrl/api/device/intakes", token, Http.JSON to body)
    }

    override suspend fun sendSessions(serverUrl: String, token: String, sessions: List<BioSession>) {
        val body = JSONObject().put("sessions", PlanJson.sessions(sessions)).toString().toByteArray()
        Http.request("POST", "$serverUrl/api/device/biofeedback/sessions", token, Http.JSON to body)
    }
}

/** Passes every call to [target], so tests can swap the server after the screens that use it were created. */
class SwitchablePlanApi(var target: PlanApi) : PlanApi {
    override suspend fun prescriptions(serverUrl: String, token: String) = target.prescriptions(serverUrl, token)
    override suspend fun record(serverUrl: String, token: String, intakes: List<Intake>) = target.record(serverUrl, token, intakes)
    override suspend fun sendSessions(serverUrl: String, token: String, sessions: List<BioSession>) =
        target.sendSessions(serverUrl, token, sessions)
}

/** The server's JSON for prescriptions, also used to keep them on the phone. */
object PlanJson {
    fun prescription(json: JSONObject): Prescription {
        val times = json.optJSONArray("times")
        return Prescription(
            id = json.getLong("id"),
            doctorName = json.optString("doctorName"),
            kind = PlanKind.valueOf(json.getString("kind")),
            name = json.getString("name"),
            dose = json.optStringOrNull("dose"),
            times = if (times == null) emptyList() else List(times.length()) { times.getString(it) }.sorted(),
            perWeek = if (json.isNull("perWeek")) null else json.getInt("perWeek"),
            instructions = json.optStringOrNull("instructions"),
            startDate = json.getString("startDate"),
            endDate = json.optStringOrNull("endDate"),
            createdAtEpochMs = json.optLong("createdAtEpochMs"),
            exercise = json.optStringOrNull("exercise"),
            level = if (json.isNull("level")) null else json.getInt("level"),
        )
    }

    fun prescriptions(array: JSONArray): List<Prescription> = List(array.length()) { prescription(array.getJSONObject(it)) }

    fun toJson(p: Prescription): JSONObject = JSONObject()
        .put("id", p.id)
        .put("doctorName", p.doctorName)
        .put("kind", p.kind.name)
        .put("name", p.name)
        .put("dose", p.dose ?: JSONObject.NULL)
        .put("times", JSONArray(p.times))
        .put("perWeek", p.perWeek ?: JSONObject.NULL)
        .put("instructions", p.instructions ?: JSONObject.NULL)
        .put("startDate", p.startDate)
        .put("endDate", p.endDate ?: JSONObject.NULL)
        .put("createdAtEpochMs", p.createdAtEpochMs)
        .put("exercise", p.exercise ?: JSONObject.NULL)
        .put("level", p.level ?: JSONObject.NULL)

    fun toJson(list: List<Prescription>): JSONArray = JSONArray().apply { list.forEach { put(toJson(it)) } }

    fun intake(i: Intake): JSONObject = JSONObject()
        .put("prescriptionId", i.prescriptionId)
        .put("scheduledAtEpochMs", i.scheduledAtEpochMs)
        .put("status", i.status.name)

    fun intakes(list: List<Intake>): JSONArray = JSONArray().apply { list.forEach { put(intake(it)) } }

    fun parseIntakes(array: JSONArray): List<Intake> = List(array.length()) {
        val json = array.getJSONObject(it)
        Intake(json.getLong("prescriptionId"), json.getLong("scheduledAtEpochMs"), IntakeStatus.valueOf(json.getString("status")))
    }

    fun session(s: BioSession): JSONObject = JSONObject()
        .put("prescriptionId", s.prescriptionId)
        .put("startedAtEpochMs", s.startedAtEpochMs)
        .put("durationMs", s.durationMs)
        .put("score", s.score)
        .put("completed", s.completed)
        .put("metrics", JSONObject().apply { s.metrics.forEach { (k, v) -> put(k, v) } })

    fun sessions(list: List<BioSession>): JSONArray = JSONArray().apply { list.forEach { put(session(it)) } }

    fun parseSessions(array: JSONArray): List<BioSession> = List(array.length()) {
        val json = array.getJSONObject(it)
        val metrics = json.optJSONObject("metrics")
        BioSession(
            prescriptionId = json.getLong("prescriptionId"),
            startedAtEpochMs = json.getLong("startedAtEpochMs"),
            durationMs = json.getInt("durationMs"),
            score = json.getInt("score"),
            completed = json.getBoolean("completed"),
            metrics = metrics?.keys()?.asSequence()?.associateWith { k -> metrics.getDouble(k) }.orEmpty(),
        )
    }

    // isNull is also true for a missing key: the server leaves out fields that are null.
    private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else getString(key)
}
