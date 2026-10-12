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
val LOGIN_LIMIT = RateLimitName("login")
val LINK_LIMIT = RateLimitName("link")

/** A logged-in doctor or admin, re-read from the database on every request so a disabled account stops at once. */
data class StaffPrincipal(val user: UserDto)

/** A phone sending for one patient. */
data class DevicePrincipal(val patientId: Long, val token: String)

/** JDBC blocks; keep it off the request threads. */
suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

fun Application.installSecurity(services: Services) {
    install(Authentication) {
        jwt(STAFF) {
            verifier(services.tokens.verifier)
            validate { credential ->
                val id = credential.payload.subject?.toLongOrNull() ?: return@validate null
                val account = io { services.users.byId(id) } ?: return@validate null
                account.user.takeIf { it.active }?.let(::StaffPrincipal)
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
    }
}

fun ApplicationCall.staff(role: Role): UserDto {
    val user = principal<StaffPrincipal>()?.user ?: throw ApiException(401, "unauthorized")
    if (user.role != role) forbidden()
    return user
}

fun ApplicationCall.device(): DevicePrincipal = principal<DevicePrincipal>() ?: throw ApiException(401, "unauthorized")
