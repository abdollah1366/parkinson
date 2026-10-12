package com.example.parkinson.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.sql.Connection
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant

class UserStore(private val db: Db) {
    data class Account(val user: UserDto, val passwordHash: String)

    fun byMobile(mobile: String): Account? = db.tx {
        single("SELECT * FROM users WHERE mobile = ?", mobile) { Account(it.toUser(), it.getString("password_hash")) }
    }

    fun byId(id: Long): Account? = db.tx {
        single("SELECT * FROM users WHERE id = ?", id) { Account(it.toUser(), it.getString("password_hash")) }
    }

    fun create(role: Role, fullName: String, mobile: String, password: String, medicalCouncilNo: String? = null): UserDto =
        db.tx {
            if (single("SELECT 1 FROM users WHERE mobile = ?", mobile) { 1 } != null) conflict("mobile_taken")
            single(
                "INSERT INTO users (role, full_name, mobile, password_hash, medical_council_no) VALUES (?, ?, ?, ?, ?) RETURNING *",
                role.name, fullName, mobile, Passwords.hash(password), medicalCouncilNo,
            ) { it.toUser() }!!
        }

    fun doctors(): List<UserDto> = db.tx {
        query("SELECT * FROM users WHERE role = 'DOCTOR' ORDER BY full_name, id") { it.toUser() }
    }

    fun update(id: Long, active: Boolean?, password: String?): UserDto = db.tx {
        if (active != null) update("UPDATE users SET active = ? WHERE id = ?", active, id)
        if (password != null) update("UPDATE users SET password_hash = ? WHERE id = ?", Passwords.hash(password), id)
        single("SELECT * FROM users WHERE id = ?", id) { it.toUser() } ?: notFound()
    }

    /** Creates the first admin from the environment; leaves an existing account with that mobile untouched. */
    fun ensureAdmin(fullName: String, mobile: String, password: String) {
        if (byMobile(mobile) == null) create(Role.ADMIN, fullName, mobile, password)
    }

    private fun ResultSet.toUser() = UserDto(
        id = getLong("id"),
        role = Role.valueOf(getString("role")),
        fullName = getString("full_name"),
        mobile = getString("mobile"),
        medicalCouncilNo = getString("medical_council_no"),
        active = getBoolean("active"),
        createdAtEpochMs = epochMs("created_at")!!,
    )
}

class InviteStore(private val db: Db, private val validity: Duration = Duration.ofDays(7)) {
    fun create(doctorId: Long, now: Instant = Instant.now()): InviteDto = db.tx {
        // 32^8 codes: a clash is very unlikely, but retry rather than fail.
        repeat(5) {
            val code = Secrets.inviteCode()
            val inserted = update(
                "INSERT INTO invites (code, doctor_id, created_at, expires_at) VALUES (?, ?, ?, ?) ON CONFLICT DO NOTHING",
                code, doctorId, now, now + validity,
            )
            if (inserted == 1) return@tx InviteDto(code, now.toEpochMilli(), (now + validity).toEpochMilli())
        }
        error("could not create a unique invite code")
    }

    fun ofDoctor(doctorId: Long, limit: Int = 50): List<InviteDto> = db.tx {
        query(
            """
            SELECT i.*, p.first_name || ' ' || p.last_name AS patient_name
            FROM invites i LEFT JOIN patients p ON p.id = i.patient_id
            WHERE i.doctor_id = ? ORDER BY i.created_at DESC LIMIT ?
            """.trimIndent(),
            doctorId, limit,
        ) {
            InviteDto(
                code = it.getString("code"),
                createdAtEpochMs = it.epochMs("created_at")!!,
                expiresAtEpochMs = it.epochMs("expires_at")!!,
                usedAtEpochMs = it.epochMs("used_at"),
                patientName = it.getString("patient_name"),
            )
        }
    }
}

class PatientStore(private val db: Db, private val pepper: String) {

    /**
     * Uses up [inviteCode]: finds or creates the patient by national ID, links them to the code's doctor and issues a
     * new upload token for this phone. Returns the token (shown once) and the doctor's name.
     */
    fun link(inviteCode: String, patient: PatientIn, now: Instant = Instant.now()): LinkResponse = db.tx {
        val invite = single(
            "SELECT i.doctor_id, i.used_at, i.expires_at, u.full_name, u.active FROM invites i JOIN users u ON u.id = i.doctor_id WHERE i.code = ? FOR UPDATE OF i",
            inviteCode,
        ) { Invite(it.getLong("doctor_id"), it.instant("used_at"), it.instant("expires_at")!!, it.getString("full_name"), it.getBoolean("active")) }
            ?: badRequest("invalid_invite")
        if (invite.usedAt != null) badRequest("invite_used")
        if (!invite.expiresAt.isAfter(now) || !invite.doctorActive) badRequest("invite_expired")

        val patientId = upsertPatient(patient, now)
        update(
            "INSERT INTO doctor_patients (doctor_id, patient_id, linked_at) VALUES (?, ?, ?) ON CONFLICT DO NOTHING",
            invite.doctorId, patientId, now,
        )
        update("UPDATE invites SET used_at = ?, patient_id = ? WHERE code = ?", now, patientId, inviteCode)
        val token = Secrets.token()
        update("INSERT INTO device_tokens (patient_id, token_hash, created_at) VALUES (?, ?, ?)", patientId, Secrets.sha256(token), now)
        LinkResponse(patientToken = token, doctorName = invite.doctorName)
    }

    /** The patient a phone's token belongs to, or null when it is unknown or revoked. */
    fun patientForToken(token: String, now: Instant = Instant.now()): Long? = db.tx {
        single(
            "UPDATE device_tokens SET last_seen_at = ? WHERE token_hash = ? AND revoked_at IS NULL RETURNING patient_id",
            now, Secrets.sha256(token),
        ) { it.getLong("patient_id") }
    }

    fun revoke(token: String, now: Instant = Instant.now()) {
        db.tx { update("UPDATE device_tokens SET revoked_at = ? WHERE token_hash = ? AND revoked_at IS NULL", now, Secrets.sha256(token)) }
    }

    fun updateProfile(patientId: Long, p: PatientIn, now: Instant = Instant.now()) {
        db.tx {
            update(
                """
                UPDATE patients SET first_name = ?, last_name = ?, age = ?, gender = ?, mobile = ?, parkinson_diagnosed = ?,
                    takes_medication = ?, dominant_hand = ?, consent_at = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent(),
                p.firstName, p.lastName, p.age, p.gender, p.mobile, p.parkinsonDiagnosed,
                p.takesParkinsonMedication, p.dominantHand, Instant.ofEpochMilli(p.consentEpochMs), now, patientId,
            )
        }
    }

    fun status(patientId: Long): DeviceStatus = db.tx {
        DeviceStatus(
            patientId = patientId,
            doctors = query(
                "SELECT u.full_name FROM doctor_patients dp JOIN users u ON u.id = dp.doctor_id WHERE dp.patient_id = ? ORDER BY dp.linked_at",
                patientId,
            ) { it.getString("full_name") },
            resultCount = single("SELECT count(*) AS n FROM results WHERE patient_id = ?", patientId) { it.getInt("n") }!!,
        )
    }

    fun ofDoctor(doctorId: Long): List<PatientSummary> = db.tx {
        query(
            """
            SELECT p.*, dp.linked_at, count(r.id) AS result_count, max(r.taken_at) AS last_result
            FROM doctor_patients dp
            JOIN patients p ON p.id = dp.patient_id
            LEFT JOIN results r ON r.patient_id = p.id
            WHERE dp.doctor_id = ?
            GROUP BY p.id, dp.linked_at
            ORDER BY last_result DESC NULLS LAST, p.last_name, p.first_name
            """.trimIndent(),
            doctorId,
        ) {
            PatientSummary(
                id = it.getLong("id"),
                fullName = it.getString("first_name") + " " + it.getString("last_name"),
                age = it.getInt("age"),
                gender = it.getString("gender"),
                nationalIdLast4 = it.getString("national_id_last4"),
                parkinsonDiagnosed = it.getBoolean("parkinson_diagnosed"),
                takesParkinsonMedication = it.booleanOrNull("takes_medication"),
                linkedAtEpochMs = it.epochMs("linked_at")!!,
                resultCount = it.getInt("result_count"),
                lastResultEpochMs = it.epochMs("last_result"),
            )
        }
    }

    /** The patient, only when linked to [doctorId]; null otherwise (the caller answers 404, not 403). */
    fun detailFor(doctorId: Long, patientId: Long): PatientDetail? = db.tx {
        single(
            "SELECT p.*, dp.linked_at FROM patients p JOIN doctor_patients dp ON dp.patient_id = p.id WHERE dp.doctor_id = ? AND p.id = ?",
            doctorId, patientId,
        ) {
            PatientDetail(
                id = it.getLong("id"),
                firstName = it.getString("first_name"),
                lastName = it.getString("last_name"),
                age = it.getInt("age"),
                gender = it.getString("gender"),
                mobile = it.getString("mobile"),
                nationalIdLast4 = it.getString("national_id_last4"),
                parkinsonDiagnosed = it.getBoolean("parkinson_diagnosed"),
                takesParkinsonMedication = it.booleanOrNull("takes_medication"),
                dominantHand = it.getString("dominant_hand"),
                consentEpochMs = it.epochMs("consent_at")!!,
                linkedAtEpochMs = it.epochMs("linked_at")!!,
                updatedAtEpochMs = it.epochMs("updated_at")!!,
            )
        }
    }

    fun isLinked(doctorId: Long, patientId: Long): Boolean = db.tx {
        single("SELECT 1 FROM doctor_patients WHERE doctor_id = ? AND patient_id = ?", doctorId, patientId) { 1 } != null
    }

    private fun Connection.upsertPatient(p: PatientIn, now: Instant): Long =
        single(
            """
            INSERT INTO patients (national_id_hash, national_id_last4, first_name, last_name, age, gender, mobile,
                parkinson_diagnosed, takes_medication, dominant_hand, consent_at, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (national_id_hash) DO UPDATE SET
                first_name = EXCLUDED.first_name, last_name = EXCLUDED.last_name, age = EXCLUDED.age,
                gender = EXCLUDED.gender, mobile = EXCLUDED.mobile, parkinson_diagnosed = EXCLUDED.parkinson_diagnosed,
                takes_medication = EXCLUDED.takes_medication, dominant_hand = EXCLUDED.dominant_hand,
                consent_at = EXCLUDED.consent_at, updated_at = EXCLUDED.updated_at
            RETURNING id
            """.trimIndent(),
            Secrets.hmacSha256(pepper, p.nationalId), p.nationalId.takeLast(4), p.firstName, p.lastName, p.age, p.gender,
            p.mobile, p.parkinsonDiagnosed, p.takesParkinsonMedication, p.dominantHand,
            Instant.ofEpochMilli(p.consentEpochMs), now, now,
        ) { it.getLong("id") }!!

    private data class Invite(val doctorId: Long, val usedAt: Instant?, val expiresAt: Instant, val doctorName: String, val doctorActive: Boolean)
}

class ResultStore(private val db: Db) {
    /** Stores new results and skips ones already there; returns every id now on the server. */
    fun store(patientId: Long, results: List<ResultIn>, now: Instant = Instant.now()): List<String> = db.tx {
        for (r in results) {
            update(
                """
                INSERT INTO results (patient_id, assessment_id, type, taken_at, hand, quality_status, quality_score,
                    performance_index, medication_state, algorithm_version, scoring_version, metrics, received_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?)
                ON CONFLICT (patient_id, assessment_id) DO NOTHING
                """.trimIndent(),
                patientId, r.assessmentId, r.type, Instant.ofEpochMilli(r.timestampEpochMs), r.hand, r.qualityStatus,
                r.qualityScore, r.performanceIndex, r.medicationState, r.algorithmVersion, r.scoringVersion,
                Json.encodeToString(JsonObject.serializer(), r.metrics), now,
            )
        }
        results.map { it.assessmentId }.distinct()
    }

    fun ofPatient(patientId: Long, type: String? = null): List<ResultDto> = db.tx {
        query(
            "SELECT * FROM results WHERE patient_id = ? AND (?::text IS NULL OR type = ?) ORDER BY taken_at DESC, id DESC",
            patientId, type, type,
        ) {
            ResultDto(
                assessmentId = it.getString("assessment_id"),
                type = it.getString("type"),
                timestampEpochMs = it.epochMs("taken_at")!!,
                hand = it.getString("hand"),
                qualityStatus = it.getString("quality_status"),
                qualityScore = it.intOrNull("quality_score"),
                performanceIndex = it.intOrNull("performance_index"),
                medicationState = it.getString("medication_state"),
                algorithmVersion = it.getString("algorithm_version"),
                scoringVersion = it.getString("scoring_version"),
                metrics = Json.decodeFromString(JsonObject.serializer(), it.getString("metrics")),
                receivedAtEpochMs = it.epochMs("received_at")!!,
            )
        }
    }
}

class AccessLog(private val db: Db) {
    fun record(userId: Long, patientId: Long?, action: String) {
        db.tx { update("INSERT INTO access_log (user_id, patient_id, action) VALUES (?, ?, ?)", userId, patientId, action) }
    }
}
