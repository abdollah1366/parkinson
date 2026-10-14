package com.example.parkinson.server

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.call.body
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import org.testcontainers.containers.PostgreSQLContainer
import javax.sql.DataSource

/**
 * A PostgreSQL for the tests: TEST_DATABASE_URL when set (user and password may be in the URL or in
 * TEST_DATABASE_USER / TEST_DATABASE_PASSWORD), otherwise a throwaway container (needs Docker).
 */
object TestDb {
    private val dataSource: DataSource by lazy {
        val url = System.getenv("TEST_DATABASE_URL")?.takeIf { it.isNotBlank() }
        if (url != null) {
            Database.connect(url, System.getenv("TEST_DATABASE_USER") ?: "postgres", System.getenv("TEST_DATABASE_PASSWORD") ?: "", poolSize = 4)
        } else {
            val container = PostgreSQLContainer("postgres:16-alpine").apply { start() }
            Database.connect(container.jdbcUrl, container.username, container.password, poolSize = 4)
        }
    }

    /** The database the tests use now. */
    fun current(): DataSource = dataSource

    /** An empty, migrated database. */
    fun fresh(): DataSource {
        dataSource.connection.use { c ->
            c.createStatement().use { it.execute("DROP SCHEMA public CASCADE; CREATE SCHEMA public;") }
        }
        Database.migrate(dataSource)
        return dataSource
    }
}

val testConfig = Config(
    port = 0,
    databaseUrl = "",
    databaseUser = "",
    databasePassword = "",
    jwtSecret = "test-secret-test-secret-test-secret-123",
    nationalIdPepper = "test-pepper-test-pepper-test-pepper-123",
    adminMobile = null,
    adminPassword = null,
    adminName = "مدیر",
    trustProxy = false,
)

class TestEnv(val services: Services, val client: HttpClient)

fun withServer(block: suspend ApplicationTestBuilder.(TestEnv) -> Unit) = testApplication {
    val services = Services(TestDb.fresh(), testConfig)
    services.users.ensureAdmin("مدیر", ADMIN_MOBILE, ADMIN_PASSWORD)
    application { module(services, testConfig) }
    val client = createClient { install(ContentNegotiation) { json(ApiJson) } }
    block(TestEnv(services, client))
}

const val ADMIN_MOBILE = "09120000000"
const val ADMIN_PASSWORD = "admin-password"

suspend fun HttpClient.login(mobile: String, password: String): HttpResponse =
    post("/api/auth/login") {
        contentType(ContentType.Application.Json)
        setBody(LoginRequest(mobile, password))
    }

suspend fun HttpClient.token(mobile: String, password: String): String = login(mobile, password).body<LoginResponse>().token

suspend inline fun <reified T> HttpClient.postJson(path: String, body: T, token: String? = null): HttpResponse =
    post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        token?.let { bearerAuth(it) }
    }

suspend fun HttpClient.getAuth(path: String, token: String): HttpResponse = get(path) { bearerAuth(token) }

/** Creates a doctor through the admin API and returns their login token. */
suspend fun HttpClient.newDoctor(mobile: String, name: String = "دکتر $mobile"): String {
    val admin = token(ADMIN_MOBILE, ADMIN_PASSWORD)
    postJson("/api/admin/doctors", NewDoctorRequest(name, mobile, "doctor-password"), admin)
    return token(mobile, "doctor-password")
}

/** A valid national ID (check digit computed) built from [seed]. */
fun nationalId(seed: Int): String {
    val first9 = seed.toString().padStart(9, '1').take(9)
    val r = (0..8).sumOf { (first9[it] - '0') * (10 - it) } % 11
    return first9 + (if (r < 2) r else 11 - r)
}

fun patient(nationalId: String = nationalId(123456789), firstName: String = "علی") = PatientIn(
    firstName = firstName,
    lastName = "رضایی",
    age = 64,
    gender = "male",
    mobile = "۰۹۱۲۳۴۵۶۷۸۹",
    nationalId = nationalId,
    parkinsonDiagnosed = true,
    takesParkinsonMedication = true,
    dominantHand = "right",
    consentEpochMs = 1_760_000_000_000,
)
