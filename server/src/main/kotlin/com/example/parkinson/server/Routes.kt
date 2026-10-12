package com.example.parkinson.server

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.principal
import io.ktor.server.http.content.staticResources
import io.ktor.server.plugins.ratelimit.rateLimit
import io.ktor.server.request.contentLength
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import io.ktor.server.routing.routing

/** Largest upload body a phone may send in one request. */
private const val MAX_UPLOAD_BYTES = 8L * 1024 * 1024

fun Application.routes(services: Services) {
    routing {
        get("/health") {
            io { services.db.tx { single("SELECT 1") { 1 } } }
            call.respondText("ok")
        }
        staticResources("/", "static")

        route("/api") {
            authRoutes(services)
            authenticate(STAFF) {
                meRoutes(services)
                route("/admin") { adminRoutes(services) }
                route("/doctor") { doctorRoutes(services) }
            }
            route("/device") { deviceRoutes(services) }
        }
    }
}

private fun Route.authRoutes(services: Services) {
    rateLimit(LOGIN_LIMIT) {
        post("/auth/login") {
            val request = call.receive<LoginRequest>()
            val mobile = Text.normalizeMobile(request.mobile)
            val account = mobile?.let { io { services.users.byMobile(it) } }
            // Check a password even for an unknown mobile, so the answer time does not reveal which mobiles exist.
            val ok = io { Passwords.matches(request.password, account?.passwordHash ?: Passwords.DUMMY_HASH) }
            if (account == null || !ok) throw ApiException(401, "invalid_credentials")
            if (!account.user.active) forbidden("account_disabled")
            call.respond(LoginResponse(services.tokens.issue(account.user.id, account.user.role), account.user))
        }
    }
}

private fun Route.meRoutes(services: Services) {
    get("/me") {
        call.respond(call.principal<StaffPrincipal>()!!.user)
    }
    post("/me/password") {
        val user = call.principal<StaffPrincipal>()!!.user
        val request = call.receive<ChangePasswordRequest>()
        val account = io { services.users.byId(user.id) } ?: notFound()
        if (!io { Passwords.matches(request.currentPassword, account.passwordHash) }) badRequest("wrong_password")
        io { services.users.update(user.id, active = null, password = Validation.password(request.newPassword)) }
        call.respond(HttpStatusCode.NoContent)
    }
}

private fun Route.adminRoutes(services: Services) {
    get("/doctors") {
        call.staff(Role.ADMIN)
        call.respond(io { services.users.doctors() })
    }
    post("/doctors") {
        call.staff(Role.ADMIN)
        val r = call.receive<NewDoctorRequest>()
        val doctor = io {
            services.users.create(
                role = Role.DOCTOR,
                fullName = Validation.name(r.fullName, "invalid_name"),
                mobile = Validation.mobile(r.mobile),
                password = Validation.password(r.password),
                medicalCouncilNo = r.medicalCouncilNo?.trim()?.takeIf { it.isNotEmpty() }?.let { Text.asciiDigits(it).take(20) },
            )
        }
        call.respond(HttpStatusCode.Created, doctor)
    }
    patch("/doctors/{id}") {
        call.staff(Role.ADMIN)
        val id = call.parameters["id"]?.toLongOrNull() ?: notFound()
        val r = call.receive<UpdateDoctorRequest>()
        val target = io { services.users.byId(id) }?.user ?: notFound()
        if (target.role != Role.DOCTOR) notFound()
        call.respond(io { services.users.update(id, r.active, r.password?.let(Validation::password)) })
    }
}

private fun Route.doctorRoutes(services: Services) {
    get("/invites") {
        val doctor = call.staff(Role.DOCTOR)
        call.respond(io { services.invites.ofDoctor(doctor.id) })
    }
    post("/invites") {
        val doctor = call.staff(Role.DOCTOR)
        call.respond(HttpStatusCode.Created, io { services.invites.create(doctor.id) })
    }
    get("/patients") {
        val doctor = call.staff(Role.DOCTOR)
        call.respond(io { services.patients.ofDoctor(doctor.id) })
    }
    get("/patients/{id}") {
        val doctor = call.staff(Role.DOCTOR)
        val id = call.parameters["id"]?.toLongOrNull() ?: notFound()
        // Someone else's patient answers exactly like a missing one.
        val patient = io { services.patients.detailFor(doctor.id, id) } ?: notFound()
        io { services.accessLog.record(doctor.id, id, "view_profile") }
        call.respond(patient)
    }
    get("/patients/{id}/results") {
        val doctor = call.staff(Role.DOCTOR)
        val id = call.parameters["id"]?.toLongOrNull() ?: notFound()
        if (!io { services.patients.isLinked(doctor.id, id) }) notFound()
        val type = call.request.queryParameters["type"]?.takeIf { it.isNotBlank() }
        io { services.accessLog.record(doctor.id, id, "view_results") }
        call.respond(io { services.results.ofPatient(id, type) })
    }
}

private fun Route.deviceRoutes(services: Services) {
    rateLimit(LINK_LIMIT) {
        post("/link") {
            val r = call.receive<LinkRequest>()
            val patient = Validation.patient(r.patient)
            val code = Secrets.normalizeInviteCode(r.inviteCode)
            call.respond(io { services.patients.link(code, patient) })
        }
    }
    authenticate(DEVICE) {
        get("/status") {
            call.respond(io { services.patients.status(call.device().patientId) })
        }
        put("/profile") {
            val device = call.device()
            val p = Validation.checked(call.receive<PatientIn>())
            io { services.patients.updateProfile(device.patientId, p) }
            call.respond(HttpStatusCode.NoContent)
        }
        post("/results") {
            val device = call.device()
            if ((call.request.contentLength() ?: 0) > MAX_UPLOAD_BYTES) throw ApiException(413, "too_large")
            val r = call.receive<UploadRequest>()
            if (r.results.size > Validation.MAX_RESULTS_PER_UPLOAD) throw ApiException(413, "too_many_results")
            val results = r.results.map { Validation.result(it) }
            call.respond(UploadResponse(io { services.results.store(device.patientId, results) }))
        }
        delete("/token") {
            io { services.patients.revoke(call.device().token) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
