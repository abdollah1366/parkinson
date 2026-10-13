package com.example.parkinson.server

import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.BadRequestException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.defaultheaders.DefaultHeaders
import io.ktor.server.plugins.forwardedheaders.XForwardedHeaders
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import javax.sql.DataSource

private val log = LoggerFactory.getLogger("parkinson-server")

fun main() {
    var config = Config.fromEnv()
    config.embeddedDataDir?.let { dir ->
        val url = Database.startEmbedded(dir)
        config = config.copy(databaseUrl = url, databaseUser = "postgres", databasePassword = "", createDatabase = true)
    }
    if (config.createDatabase) Database.createIfMissing(config.databaseUrl, config.databaseUser, config.databasePassword)
    val dataSource = Database.connect(config.databaseUrl, config.databaseUser, config.databasePassword)
    Database.migrate(dataSource)
    val services = Services(dataSource, config)
    if (config.adminMobile != null && config.adminPassword != null) {
        services.users.ensureAdmin(config.adminName, Validation.mobile(config.adminMobile), Validation.password(config.adminPassword))
    }
    log.info("Starting on port {}", config.port)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(services, config) }.start(wait = true)
}

class Services(dataSource: DataSource, config: Config) {
    val db = Db(dataSource)
    val users = UserStore(db)
    val invites = InviteStore(db)
    val patients = PatientStore(db, config.nationalIdPepper)
    val results = ResultStore(db)
    val accessLog = AccessLog(db)
    val scores = ScoreStore(db)
    val notes = NoteStore(db)
    val messages = MessageStore(db)
    val tokens = JwtTokens(config.jwtSecret)
}

val ApiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
}

fun Application.module(services: Services, config: Config) {
    if (config.trustProxy) install(XForwardedHeaders)
    install(DefaultHeaders) {
        header("X-Content-Type-Options", "nosniff")
        header("X-Frame-Options", "DENY")
        header("Referrer-Policy", "no-referrer")
        header(
            "Content-Security-Policy",
            "default-src 'self'; img-src 'self' data:; media-src 'self' blob:; style-src 'self'; script-src 'self'; frame-ancestors 'none'",
        )
    }
    install(ContentNegotiation) { json(ApiJson) }
    install(StatusPages) {
        exception<ApiException> { call, e -> call.respond(HttpStatusCode.fromValue(e.status), ErrorDto(e.code)) }
        exception<BadRequestException> { call, _ -> call.respond(HttpStatusCode.BadRequest, ErrorDto("bad_request")) }
        exception<Throwable> { call, e ->
            log.error("Unhandled error on {}", call.request.local.uri, e)
            call.respond(HttpStatusCode.InternalServerError, ErrorDto("server_error"))
        }
    }
    installSecurity(services)
    routes(services)
}
