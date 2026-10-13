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
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.pingPeriod
import io.ktor.server.websocket.timeout
import java.net.InetAddress
import kotlin.time.Duration.Companion.seconds
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
    val turn = config.turnPublicIp?.let { ip ->
        TurnServer(InetAddress.getByName(ip), config.turnPort, config.turnRelayPorts, config.turnSecret ?: turnSecretFrom(config.jwtSecret))
            .apply { start() }
    }
    val services = Services(dataSource, config, turn)
    if (config.adminMobile != null && config.adminPassword != null) {
        services.users.ensureAdmin(config.adminName, Validation.mobile(config.adminMobile), Validation.password(config.adminPassword))
    }
    log.info("Starting on port {}", config.port)
    embeddedServer(Netty, port = config.port, host = "0.0.0.0") { module(services, config) }.start(wait = true)
}

class Services(dataSource: DataSource, config: Config, turn: TurnServer? = null) {
    val db = Db(dataSource)
    val users = UserStore(db)
    val invites = InviteStore(db)
    val patients = PatientStore(db, config.nationalIdPepper)
    val results = ResultStore(db)
    val accessLog = AccessLog(db)
    val scores = ScoreStore(db)
    val notes = NoteStore(db)
    val messages = MessageStore(db)
    val prescriptions = PrescriptionStore(db, config.zone)
    val tokens = JwtTokens(config.jwtSecret)
    val calls = CallHub(this, turn, config.turnPublicIp?.let { ip -> turnUrls(ip, config.turnPort) }.orEmpty())
}

/** Where browsers and phones reach the relay: STUN, then TURN over UDP and, for networks that block UDP, TCP. */
fun turnUrls(ip: String, port: Int) = listOf("stun:$ip:$port", "turn:$ip:$port?transport=udp", "turn:$ip:$port?transport=tcp")

/** A relay secret of its own, so the relay never learns the key that signs logins. */
private fun turnSecretFrom(jwtSecret: String): String =
    java.util.Base64.getEncoder().encodeToString(Stun.hmacSha1(jwtSecret.toByteArray(), "turn-relay".toByteArray()))

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
    install(WebSockets) {
        pingPeriod = 20.seconds
        timeout = 40.seconds
        maxFrameSize = CallHub.MAX_MESSAGE.toLong()
    }
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
