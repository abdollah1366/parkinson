package com.example.parkinson.server

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/** ADMIN and DOCTOR are staff accounts (users table); PATIENT logs in to their own panel (patients table). */
enum class Role { ADMIN, DOCTOR, PATIENT }

/** A refusal the client can act on; [code] is stable and the web panel and the app translate it. */
class ApiException(val status: Int, val code: String) : RuntimeException(code)

fun badRequest(code: String): Nothing = throw ApiException(400, code)
fun notFound(code: String = "not_found"): Nothing = throw ApiException(404, code)
fun forbidden(code: String = "forbidden"): Nothing = throw ApiException(403, code)
fun conflict(code: String): Nothing = throw ApiException(409, code)

@Serializable
data class ErrorDto(val error: String)

// --- Staff ---

@Serializable
data class LoginRequest(val mobile: String, val password: String)

@Serializable
data class LoginResponse(val token: String, val user: UserDto)

@Serializable
data class UserDto(
    val id: Long,
    val role: Role,
    val fullName: String,
    val mobile: String,
    val medicalCouncilNo: String? = null,
    val active: Boolean,
    val createdAtEpochMs: Long,
)

@Serializable
data class NewDoctorRequest(
    val fullName: String,
    val mobile: String,
    val password: String,
    val medicalCouncilNo: String? = null,
)

@Serializable
data class UpdateDoctorRequest(val active: Boolean? = null, val password: String? = null)

@Serializable
data class ChangePasswordRequest(val currentPassword: String, val newPassword: String)

@Serializable
data class InviteDto(
    val code: String,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    val usedAtEpochMs: Long? = null,
    val patientName: String? = null,
)

@Serializable
data class PatientSummary(
    val id: Long,
    val fullName: String,
    val age: Int,
    val gender: String,
    val nationalIdLast4: String,
    val parkinsonDiagnosed: Boolean,
    val takesParkinsonMedication: Boolean? = null,
    val linkedAtEpochMs: Long,
    val resultCount: Int,
    val lastResultEpochMs: Long? = null,
    /** Some test has dropped clearly since the first results (see [Analysis]). */
    val attention: Boolean = false,
    /** Messages from the patient the doctor has not opened yet. */
    val unreadMessages: Int = 0,
)

@Serializable
data class PatientDetail(
    val id: Long,
    val firstName: String,
    val lastName: String,
    val age: Int,
    val gender: String,
    val mobile: String,
    val nationalIdLast4: String,
    val parkinsonDiagnosed: Boolean,
    val takesParkinsonMedication: Boolean? = null,
    val dominantHand: String,
    val consentEpochMs: Long,
    val linkedAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Serializable
data class ResultDto(
    val assessmentId: String,
    val type: String,
    val timestampEpochMs: Long,
    val hand: String? = null,
    val qualityStatus: String,
    val qualityScore: Int? = null,
    val performanceIndex: Int? = null,
    val medicationState: String? = null,
    val algorithmVersion: String,
    val scoringVersion: String,
    val metrics: JsonObject,
    val receivedAtEpochMs: Long,
)

// --- Phone ---

/** The profile as the app keeps it ([nationalId] already normalised to 10 digits). */
@Serializable
data class PatientIn(
    val firstName: String,
    val lastName: String,
    val age: Int,
    val gender: String,
    val mobile: String,
    val nationalId: String,
    val parkinsonDiagnosed: Boolean,
    val takesParkinsonMedication: Boolean? = null,
    val dominantHand: String,
    val consentEpochMs: Long,
)

@Serializable
data class LinkRequest(val inviteCode: String, val patient: PatientIn)

@Serializable
data class LinkResponse(val patientToken: String, val doctorName: String)

/** One saved test. [metrics] holds the test's own measurements, as the app's result type defines them. */
@Serializable
data class ResultIn(
    val assessmentId: String,
    val type: String,
    val timestampEpochMs: Long,
    val hand: String? = null,
    val qualityStatus: String,
    val qualityScore: Int? = null,
    val performanceIndex: Int? = null,
    val medicationState: String? = null,
    val algorithmVersion: String,
    val scoringVersion: String,
    val metrics: JsonObject = JsonObject(emptyMap()),
)

@Serializable
data class UploadRequest(val results: List<ResultIn>)

/** Ids now on the server, including ones that were already there; the app can mark all of them as sent. */
@Serializable
data class UploadResponse(val stored: List<String>)

@Serializable
data class DeviceStatus(val patientId: Long, val doctors: List<String>, val resultCount: Int)

// --- Notes and the patient's panel ---

@Serializable
data class NoteIn(val text: String, val sharedWithPatient: Boolean = false)

@Serializable
data class NoteDto(
    val id: Long,
    val doctorName: String,
    val text: String,
    val sharedWithPatient: Boolean,
    val createdAtEpochMs: Long,
)

@Serializable
data class PatientLoginRequest(val nationalId: String, val password: String)

@Serializable
data class PatientMe(val id: Long, val firstName: String, val lastName: String, val doctors: List<String>)

@Serializable
data class PatientLoginResponse(val token: String, val patient: PatientMe)

/** Sent by the phone (with its token) to let the patient log in to their panel. */
@Serializable
data class WebPasswordRequest(val password: String)
