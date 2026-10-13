package com.example.parkinson.server

/** Settings read from environment variables (see server/.env.example). */
data class Config(
    val port: Int,
    val databaseUrl: String,
    val databaseUser: String,
    val databasePassword: String,
    /** Signs staff login tokens. At least 32 characters. */
    val jwtSecret: String,
    /** Key for the national-ID hash. At least 32 characters; changing it makes known patients look new. */
    val nationalIdPepper: String,
    /** Creates this admin account on start if no account has the mobile yet. */
    val adminMobile: String?,
    val adminPassword: String?,
    val adminName: String,
    /** Behind a reverse proxy (Caddy): take the client address from X-Forwarded-For. */
    val trustProxy: Boolean,
    /** Create the database on start if it is missing (test computers; Docker creates it by itself). */
    val createDatabase: Boolean = false,
    /** Folder of a PostgreSQL the server starts itself (local testing); null to use [databaseUrl]. */
    val embeddedDataDir: String? = null,
    /** Public IPv4 address for the built-in TURN relay of calls; null turns the relay off. */
    val turnPublicIp: String? = null,
    val turnPort: Int = 3478,
    val turnRelayPorts: IntRange = 49160..49200,
    /** Signs the relay's time-limited passwords; derived from [jwtSecret] when not set. */
    val turnSecret: String? = null,
    /** Where the clinic is: the times of day in prescriptions are read in this zone. */
    val zone: java.time.ZoneId = java.time.ZoneId.of("Asia/Tehran"),
) {
    init {
        require(jwtSecret.length >= 32) { "JWT_SECRET must be at least 32 characters" }
        require(nationalIdPepper.length >= 32) { "NATIONAL_ID_PEPPER must be at least 32 characters" }
    }

    override fun toString(): String = "Config(port=$port, databaseUrl=$databaseUrl, trustProxy=$trustProxy, turn=$turnPublicIp)"

    companion object {
        fun fromEnv(env: Map<String, String> = System.getenv()): Config {
            fun required(name: String) = env[name]?.takeIf { it.isNotBlank() } ?: error("$name is not set")
            val embedded = env["EMBEDDED_DB_DIR"]?.takeIf { it.isNotBlank() }
            return Config(
                port = env["PORT"]?.toIntOrNull() ?: 8080,
                databaseUrl = if (embedded != null) "" else required("DATABASE_URL"),
                databaseUser = if (embedded != null) "" else required("DATABASE_USER"),
                databasePassword = if (embedded != null) "" else required("DATABASE_PASSWORD"),
                jwtSecret = required("JWT_SECRET"),
                nationalIdPepper = required("NATIONAL_ID_PEPPER"),
                adminMobile = env["ADMIN_MOBILE"]?.takeIf { it.isNotBlank() },
                adminPassword = env["ADMIN_PASSWORD"]?.takeIf { it.isNotBlank() },
                adminName = env["ADMIN_NAME"]?.takeIf { it.isNotBlank() } ?: "مدیر سامانه",
                trustProxy = env["TRUST_PROXY"] == "true",
                createDatabase = env["CREATE_DATABASE"] == "true",
                embeddedDataDir = embedded,
                turnPublicIp = env["TURN_PUBLIC_IP"]?.takeIf { it.isNotBlank() },
                turnPort = env["TURN_PORT"]?.toIntOrNull() ?: 3478,
                turnRelayPorts = env["TURN_RELAY_PORTS"]?.let(::portRange) ?: 49160..49200,
                turnSecret = env["TURN_SECRET"]?.takeIf { it.isNotBlank() },
                zone = env["CLINIC_TIME_ZONE"]?.takeIf { it.isNotBlank() }?.let { java.time.ZoneId.of(it) } ?: java.time.ZoneId.of("Asia/Tehran"),
            )
        }
    }
}

/** "49160-49200" as a range; null when it is not one. */
private fun portRange(text: String): IntRange? {
    val (from, to) = text.split('-').map { it.trim().toIntOrNull() ?: return null }.takeIf { it.size == 2 } ?: return null
    return (from..to).takeIf { from in 1024..65535 && to in from..65535 }
}
