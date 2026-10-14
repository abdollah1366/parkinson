package com.example.parkinson.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant

// Stage 6: biofeedback sessions. The doctor prescribes an exercise of the app's library (a BIOFEEDBACK prescription,
// see Prescriptions.kt); the phone sends each session with its score (0-100) and measurements, and a completed session
// also counts as the exercise done for adherence.

@Serializable
data class SessionIn(
    val prescriptionId: Long,
    val startedAtEpochMs: Long,
    val durationMs: Int,
    val score: Int,
    /** False when the person stopped early. */
    val completed: Boolean = true,
    val metrics: Map<String, Double> = emptyMap(),
)

@Serializable
data class SessionsUpload(val sessions: List<SessionIn>)

@Serializable
data class SessionDto(
    val id: Long,
    val prescriptionId: Long,
    val exercise: String,
    val level: Int,
    val startedAtEpochMs: Long,
    val durationMs: Int,
    val score: Int,
    val completed: Boolean,
    val metrics: Map<String, Double>,
)

@Serializable
data class ExerciseInfo(val code: String, val title: String, val standing: Boolean)

class BiofeedbackStore(private val db: Db) {

    /** Stores sessions from the patient's phone; a session sent again replaces the earlier copy. Returns how many. */
    fun record(patientId: Long, sessions: List<SessionIn>, now: Instant = Instant.now()): Int = db.tx {
        val mine = query("SELECT id FROM prescriptions WHERE patient_id = ? AND kind = 'BIOFEEDBACK'", patientId) { it.getLong("id") }.toSet()
        var stored = 0
        for (s in sessions) {
            if (s.prescriptionId !in mine) badRequest("unknown_prescription")
            if (s.score !in 0..100 || s.durationMs !in 0..MAX_DURATION_MS) badRequest("invalid_session")
            if (s.metrics.size > MAX_METRICS || s.metrics.any { (k, v) -> !KEY.matches(k) || !v.isFinite() }) badRequest("invalid_session")
            val at = Instant.ofEpochMilli(s.startedAtEpochMs)
            if (at > now.plus(Duration.ofDays(1)) || at < now.minus(Duration.ofDays(400))) badRequest("invalid_time")
            stored += update(
                """
                INSERT INTO biofeedback_sessions (prescription_id, started_at, duration_ms, score, completed, metrics, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (prescription_id, started_at) DO UPDATE SET duration_ms = EXCLUDED.duration_ms, score = EXCLUDED.score,
                    completed = EXCLUDED.completed, metrics = EXCLUDED.metrics, recorded_at = EXCLUDED.recorded_at
                """.trimIndent(),
                s.prescriptionId, at, s.durationMs, s.score, s.completed, ApiJson.encodeToString(METRICS, s.metrics), now,
            )
            if (s.completed) {
                update(
                    """
                    INSERT INTO intakes (prescription_id, scheduled_at, status, recorded_at) VALUES (?, ?, 'DONE', ?)
                    ON CONFLICT (prescription_id, scheduled_at) DO NOTHING
                    """.trimIndent(),
                    s.prescriptionId, at, now,
                )
            }
        }
        stored
    }

    /** The patient's sessions of the last [days] days (only of the [doctorId]'s prescriptions when given), newest first. */
    fun sessions(patientId: Long, doctorId: Long?, days: Int, now: Instant = Instant.now()): List<SessionDto> = db.tx {
        query(
            """
            SELECT s.*, p.exercise, p.level FROM biofeedback_sessions s JOIN prescriptions p ON p.id = s.prescription_id
            WHERE p.patient_id = ? AND (?::bigint IS NULL OR p.doctor_id = ?) AND s.started_at >= ?
            ORDER BY s.started_at DESC LIMIT 1000
            """.trimIndent(),
            patientId, doctorId, doctorId, now.minus(Duration.ofDays(days.toLong())),
        ) { it.toSession() }
    }

    private fun ResultSet.toSession() = SessionDto(
        id = getLong("id"),
        prescriptionId = getLong("prescription_id"),
        exercise = getString("exercise"),
        level = getInt("level"),
        startedAtEpochMs = epochMs("started_at")!!,
        durationMs = getInt("duration_ms"),
        score = getInt("score"),
        completed = getBoolean("completed"),
        metrics = runCatching { ApiJson.decodeFromString(METRICS, getString("metrics")) }.getOrDefault(emptyMap()),
    )

    companion object {
        const val MAX_DURATION_MS = 2 * 60 * 60 * 1000
        const val MAX_METRICS = 30
        private val KEY = Regex("[A-Za-z][A-Za-z0-9_]{0,39}")
        private val METRICS = MapSerializer(String.serializer(), Double.serializer())

        val library: List<ExerciseInfo> = BiofeedbackExercise.entries.map { ExerciseInfo(it.name, it.title, it.standing) }
    }
}
