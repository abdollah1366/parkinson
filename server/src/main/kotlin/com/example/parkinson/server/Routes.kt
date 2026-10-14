package com.example.parkinson.server

import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.withCharset
import io.ktor.server.response.header
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
import io.ktor.server.websocket.webSocket

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
            authenticate(PATIENT) {
                route("/patient") { patientRoutes(services) }
            }
            route("/device") { deviceRoutes(services) }
            // Calls: logs in with the first message (browsers cannot send headers on a WebSocket).
            webSocket("/calls/ws") { services.calls.serve(this) }
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
        post("/auth/patient-login") {
            val request = call.receive<PatientLoginRequest>()
            val nationalId = Text.asciiDigits(request.nationalId).filter { it.isDigit() }.padStart(10, '0')
            val found = if (Text.isValidNationalId(nationalId)) io { services.patients.forLogin(nationalId) } else null
            val ok = io { Passwords.matches(request.password, found?.second ?: Passwords.DUMMY_HASH) }
            if (found == null || found.second == null || !ok) throw ApiException(401, "invalid_credentials")
            val me = io { services.patients.me(found.first) } ?: throw ApiException(401, "invalid_credentials")
            call.respond(PatientLoginResponse(services.tokens.issue(found.first, Role.PATIENT), me))
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
        val patients = io { services.patients.ofDoctor(doctor.id) }
        val scores = io { services.scores.ofDoctor(doctor.id) }
        val unread = io { services.messages.unreadByPatient(doctor.id) }
        call.respond(
            patients.map { p ->
                p.copy(attention = Analysis.summarize(scores[p.id].orEmpty()).any { it.attention }, unreadMessages = unread[p.id] ?: 0)
            },
        )
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
    get("/patients/{id}/analysis") {
        val doctor = call.staff(Role.DOCTOR)
        val id = linkedPatient(services, doctor.id)
        call.respond(Analysis.summarize(io { services.scores.ofPatient(id) }))
    }
    get("/patients/{id}/results.csv") {
        val doctor = call.staff(Role.DOCTOR)
        val id = linkedPatient(services, doctor.id)
        io { services.accessLog.record(doctor.id, id, "export_results") }
        call.response.header(HttpHeaders.ContentDisposition, "attachment; filename=\"patient-$id-results.csv\"")
        call.respondText(Csv.results(io { services.results.ofPatient(id) }), ContentType.Text.CSV.withCharset(Charsets.UTF_8))
    }
    get("/patients/{id}/notes") {
        val doctor = call.staff(Role.DOCTOR)
        val id = linkedPatient(services, doctor.id)
        call.respond(io { services.notes.ofDoctor(doctor.id, id) })
    }
    post("/patients/{id}/notes") {
        val doctor = call.staff(Role.DOCTOR)
        val id = linkedPatient(services, doctor.id)
        val note = call.receive<NoteIn>()
        val text = note.text.trim().takeIf { it.length in 1..4000 } ?: badRequest("invalid_note")
        call.respond(HttpStatusCode.Created, io { services.notes.add(doctor.id, id, note.copy(text = text)) })
    }
    route("/patients/{id}") {
        conversationRoutes(services, fromDoctor = true) {
            val doctor = staff(Role.DOCTOR)
            val id = parameters["id"]?.toLongOrNull() ?: notFound()
            if (!io { services.patients.isLinked(doctor.id, id) }) notFound()
            doctor.id to id
        }
    }
    doctorPrescriptionRoutes(services) {
        val doctor = staff(Role.DOCTOR)
        val id = parameters["id"]?.toLongOrNull() ?: notFound()
        if (!io { services.patients.isLinked(doctor.id, id) }) notFound()
        doctor.id to id
    }
    get("/biofeedback/exercises") {
        call.staff(Role.DOCTOR)
        call.respond(BiofeedbackStore.library)
    }
    post("/prescriptions/{prescriptionId}/stop") {
        val doctor = call.staff(Role.DOCTOR)
        val id = call.parameters["prescriptionId"]?.toLongOrNull() ?: notFound()
        if (!io { services.prescriptions.stop(doctor.id, id) }) notFound()
        call.respond(HttpStatusCode.NoContent)
    }
    delete("/notes/{noteId}") {
        val doctor = call.staff(Role.DOCTOR)
        val noteId = call.parameters["noteId"]?.toLongOrNull() ?: notFound()
        if (!io { services.notes.delete(doctor.id, noteId) }) notFound()
        call.respond(HttpStatusCode.NoContent)
    }
}

/** The {id} patient when linked to [doctorId]; otherwise 404, exactly like a missing patient. */
private suspend fun io.ktor.server.routing.RoutingContext.linkedPatient(services: Services, doctorId: Long): Long {
    val id = call.parameters["id"]?.toLongOrNull() ?: notFound()
    if (!io { services.patients.isLinked(doctorId, id) }) notFound()
    return id
}

private fun Route.patientRoutes(services: Services) {
    get("/me") {
        call.respond(io { services.patients.me(call.patient()) } ?: notFound())
    }
    get("/results") {
        call.respond(io { services.results.ofPatient(call.patient()) })
    }
    get("/analysis") {
        call.respond(Analysis.summarize(io { services.scores.ofPatient(call.patient()) }))
    }
    get("/notes") {
        call.respond(io { services.notes.sharedWith(call.patient()) })
    }
    get("/conversations") {
        call.respond(io { services.messages.conversationsOf(call.patient()) })
    }
    get("/prescriptions") {
        call.respond(io { services.prescriptions.ofPatient(call.patient(), activeOnly = true) })
    }
    get("/biofeedback") {
        call.respond(io { services.biofeedback.sessions(call.patient(), null, 90) })
    }
    route("/doctors/{doctorId}") {
        conversationRoutes(services, fromDoctor = false) { patientConversation(services, patient()) }
    }
    post("/password") {
        val id = call.patient()
        val request = call.receive<ChangePasswordRequest>()
        val hash = io { services.patients.passwordHash(id) } ?: notFound()
        if (!io { Passwords.matches(request.currentPassword, hash) }) badRequest("wrong_password")
        io { services.patients.setPassword(id, Validation.password(request.newPassword)) }
        call.respond(HttpStatusCode.NoContent)
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
        put("/web-password") {
            val device = call.device()
            val password = Validation.password(call.receive<WebPasswordRequest>().password)
            io { services.patients.setPassword(device.patientId, password) }
            call.respond(HttpStatusCode.NoContent)
        }
        get("/conversations") {
            call.respond(io { services.messages.conversationsOf(call.device().patientId) })
        }
        route("/doctors/{doctorId}") {
            conversationRoutes(services, fromDoctor = false) { patientConversation(services, device().patientId) }
        }
        get("/prescriptions") {
            call.respond(io { services.prescriptions.ofPatient(call.device().patientId, activeOnly = true) })
        }
        post("/intakes") {
            val device = call.device()
            val upload = call.receive<IntakesUpload>()
            if (upload.intakes.size > 500) badRequest("too_many")
            call.respond(mapOf("stored" to io { services.prescriptions.record(device.patientId, upload.intakes) }))
        }
        post("/biofeedback/sessions") {
            val device = call.device()
            val upload = call.receive<SessionsUpload>()
            if (upload.sessions.size > 200) badRequest("too_many")
            call.respond(mapOf("stored" to io { services.biofeedback.record(device.patientId, upload.sessions) }))
        }
        get("/calls") {
            call.respond(services.calls.ringingFor(call.device().patientId))
        }
        post("/calls/{callId}/decline") {
            val callId = call.parameters["callId"] ?: notFound()
            if (!services.calls.declineFor(call.device().patientId, callId)) notFound()
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/token") {
            io { services.patients.revoke(call.device().token) }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** The {doctorId} doctor and [patientId], when they are linked; otherwise 404. */
private suspend fun io.ktor.server.application.ApplicationCall.patientConversation(services: Services, patientId: Long): Pair<Long, Long> {
    val doctorId = parameters["doctorId"]?.toLongOrNull() ?: notFound()
    if (!io { services.patients.isLinked(doctorId, patientId) }) notFound()
    return doctorId to patientId
}
