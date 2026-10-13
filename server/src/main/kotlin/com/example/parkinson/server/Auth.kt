package com.example.parkinson.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.auth.jwt.jwt
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.ratelimit.RateLimit
import io.ktor.server.plugins.ratelimit.RateLimitName
import io.ktor.server.response.respond
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.minutes

const val STAFF = "staff"
const val DEVICE = "device"
const val PATIENT = "patient"
val LOGIN_LIMIT = RateLimitName("login")
val LINK_LIMIT = RateLimitName("link")
val MESSAGE_LIMIT = RateLimitName("message")

/** A logged-in doctor or admin, re-read from the database on every request so a disabled account stops at once. */
data class StaffPrincipal(val user: UserDto)

/** A phone sending for one patient. */
data class DevicePrincipal(val patientId: Long, val token: String)

/** A patient logged in to their own panel. */
data class PatientPrincipal(val patientId: Long)

/** JDBC blocks; keep it off the request threads. */
suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

fun Application.installSecurity(services: Services) {
    install(Authentication) {
        jwt(STAFF) {
            verifier(services.tokens.verifier)
            validate { credential ->
                // Patient and staff ids come from different tables: never let one pass as the other.
                if (credential.payload.getClaim("role").asString() == Role.PATIENT.name) return@validate null
                val id = credential.payload.subject?.toLongOrNull() ?: return@validate null
                val account = io { services.users.byId(id) } ?: return@validate null
                account.user.takeIf { it.active }?.let(::StaffPrincipal)
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")) }
        }
        jwt(PATIENT) {
            verifier(services.tokens.verifier)
            validate { credential ->
                if (credential.payload.getClaim("role").asString() != Role.PATIENT.name) return@validate null
                val id = credential.payload.subject?.toLongOrNull() ?: return@validate null
                // A patient whose panel password was removed (or who no longer exists) is logged out.
                io { services.patients.passwordHash(id) }?.let { PatientPrincipal(id) }
            }
            challenge { _, _ -> call.respond(HttpStatusCode.Unauthorized, ErrorDto("unauthorized")) }
        }
        bearer(DEVICE) {
            authenticate { credential ->
                io { services.patients.patientForToken(credential.token) }?.let { DevicePrincipal(it, credential.token) }
            }
        }
    }
    install(RateLimit) {
        // Per client address: slows password and invite-code guessing.
        register(LOGIN_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { it.request.origin.remoteHost }
        }
        register(LINK_LIMIT) {
            rateLimiter(limit = 10, refillPeriod = 1.minutes)
            requestKey { it.request.origin.remoteHost }
        }
        // Per sender (their token), so one noisy account cannot slow everyone else behind the same proxy.
        register(MESSAGE_LIMIT) {
            rateLimiter(limit = 60, refillPeriod = 1.minutes)
            requestKey { it.request.headers["Authorization"] ?: it.request.origin.remoteHost }
        }
    }
}

fun ApplicationCall.staff(role: Role): UserDto {
    val user = principal<StaffPrincipal>()?.user ?: throw ApiException(401, "unauthorized")
    if (user.role != role) forbidden()
    return user
}

fun ApplicationCall.patient(): Long = principal<PatientPrincipal>()?.patientId ?: throw ApiException(401, "unauthorized")

fun ApplicationCall.device(): DevicePrincipal = principal<DevicePrincipal>() ?: throw ApiException(401, "unauthorized")
