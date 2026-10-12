package com.example.parkinson.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant

/** Checks on what phones and staff send. Each failure is a stable error code. */
object Validation {
    const val MAX_RESULTS_PER_UPLOAD = 200
    /** Raw series (frame times, tap times) make some tests large. */
    const val MAX_METRICS_CHARS = 1_000_000

    private val GENDERS = setOf("male", "female")
    private val HANDS = setOf("right", "left")
    private val QUALITY = setOf("VALID", "LOW_QUALITY", "INSUFFICIENT_DATA", "INVALID")
    private val MEDICATION = setOf("on", "off", "unsure")
    private val TYPE = Regex("[a-z][a-z0-9_]{0,39}")
    private val SHORT = Regex("[\\p{L}\\p{N} ._:+-]{1,64}")

    fun name(value: String, code: String): String =
        value.trim().takeIf { it.length in 1..60 } ?: badRequest(code)

    fun password(value: String): String =
        value.takeIf { it.length in Passwords.MIN_LENGTH..128 } ?: badRequest("weak_password")

    fun mobile(value: String): String = Text.normalizeMobile(value) ?: badRequest("invalid_mobile")

    fun patient(p: PatientIn, now: Instant = Instant.now()): PatientIn {
        if (!Text.isValidNationalId(p.nationalId)) badRequest("invalid_national_id")
        return checked(p, now)
    }

    /** The profile fields that may change after linking (everything but the national ID). */
    fun checked(p: PatientIn, now: Instant = Instant.now()): PatientIn {
        if (p.age !in 18..110) badRequest("invalid_age")
        if (p.gender !in GENDERS) badRequest("invalid_gender")
        if (p.dominantHand !in HANDS) badRequest("invalid_hand")
        if (!p.parkinsonDiagnosed && p.takesParkinsonMedication != null) badRequest("invalid_medication")
        if (p.consentEpochMs <= 0 || p.consentEpochMs > (now + Duration.ofDays(1)).toEpochMilli()) badRequest("invalid_consent")
        return p.copy(
            firstName = name(p.firstName, "invalid_name"),
            lastName = name(p.lastName, "invalid_name"),
            mobile = mobile(p.mobile),
        )
    }

    fun result(r: ResultIn, now: Instant = Instant.now()): ResultIn {
        if (r.assessmentId.isBlank() || !SHORT.matches(r.assessmentId)) badRequest("invalid_assessment_id")
        if (!TYPE.matches(r.type)) badRequest("invalid_type")
        if (r.timestampEpochMs < EARLIEST || r.timestampEpochMs > (now + Duration.ofDays(1)).toEpochMilli()) badRequest("invalid_timestamp")
        if (r.hand != null && r.hand !in HANDS) badRequest("invalid_hand")
        if (r.qualityStatus !in QUALITY) badRequest("invalid_quality")
        if (r.qualityScore != null && r.qualityScore !in 0..100) badRequest("invalid_quality")
        if (r.performanceIndex != null && r.performanceIndex !in 0..100) badRequest("invalid_performance_index")
        if (r.medicationState != null && r.medicationState !in MEDICATION) badRequest("invalid_medication_state")
        if (!SHORT.matches(r.algorithmVersion) || !SHORT.matches(r.scoringVersion)) badRequest("invalid_version")
        if (Json.encodeToString(JsonObject.serializer(), r.metrics).length > MAX_METRICS_CHARS) badRequest("metrics_too_large")
        return r
    }

    /** 2024-01-01: nothing older can come from this app. */
    private const val EARLIEST = 1_704_067_200_000L
}
